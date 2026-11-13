package ai.awe.registry.consumption.jdbc;

import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import ai.awe.registry.source.postgres.PostgresWarehouse.Catalog;
import ai.awe.registry.source.postgres.PostgresWarehouse.Column;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 固定 Dataset 与行模式驱动的原生只读提取，范围外、部分结果和未配置均不冒充成功。 */
@Component
public final class PostgresDatasetExtractor {
    private static final int MAX_BYTES = 1048576, MAX_FIELDS = 64;
    private final PostgresWarehouse warehouse;
    private final JsonDocuments json;

    public PostgresDatasetExtractor(PostgresWarehouse warehouse, JsonDocuments json) {
        this.warehouse = warehouse;
        this.json = json;
    }

    public record Plan(
            SemanticModel.Dataset dataset,
            ObjectType rowSchema,
            JsonNode binding,
            List<String> fields,
            Instant from,
            Instant to,
            int maxRows,
            int maxBytes) {
        public Plan {
            fields = List.copyOf(fields);
        }
    }

    public record Result(
            String status,
            String dataSnapshotRef,
            List<Map<String, Object>> rows,
            List<String> limitations) {
        public Result {
            rows =
                    rows.stream()
                            .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row)))
                            .toList();
            limitations = List.copyOf(limitations);
        }
    }

    public Result execute(Plan plan) {
        String snapshot = "";
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try {
            validatePlan(plan);
            if (!warehouse.configured())
                return result(
                        warehouse.status(),
                        "",
                        List.of(),
                        "Native PostgreSQL extraction is not configured or supported");
            String external = plan.binding().path("externalRef").asText();
            warehouse.relation(external);
            try (Connection connection = warehouse.openReadOnly()) {
                try {
                    // 关系锁、目录版本和数据快照在同一只读事务中，阻止来源 Gate 后的 DDL 漂移。
                    Catalog catalog = warehouse.inspect(connection, external, deadline);
                    snapshot = warehouse.snapshotRef(connection, external, deadline);
                    String fingerprint = json.sha256(warehouse.metadata(catalog, external));
                    if (!fingerprint.equals(plan.binding().path("fingerprint").asText()))
                        return result(
                                "BLOCKED",
                                snapshot,
                                List.of(),
                                "The actual PostgreSQL schema/projection fingerprint differs from the pinned binding");
                    var columns = new HashMap<String, Column>();
                    catalog.columns().forEach(c -> columns.put(c.name(), c));
                    var properties = new HashMap<String, ObjectType.Property>();
                    plan.rowSchema().properties().forEach(p -> properties.put(p.name(), p));
                    checkSchema(plan, catalog, columns);
                    String event = plan.binding().path("timeFields").path("EVENT_TIME").asText();
                    if (!columns.containsKey(event)
                            || !columns.get(event).nativeType().equals("timestamptz"))
                        return result(
                                "BLOCKED",
                                snapshot,
                                List.of(),
                                "EVENT_TIME requires an actual PostgreSQL timestamptz column");
                    String sql = sql(plan, catalog.relation(), event);
                    var rows = new ArrayList<Map<String, Object>>();
                    int bytes = 2;
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        PostgresWarehouse.constrain(statement, deadline);
                        statement.setFetchSize(Math.min(plan.maxRows() + 1, 128));
                        statement.setMaxRows(plan.maxRows() + 1);
                        statement.setObject(1, plan.from().atOffset(ZoneOffset.UTC));
                        statement.setObject(2, plan.to().atOffset(ZoneOffset.UTC));
                        try (ResultSet rs = statement.executeQuery()) {
                            while (rs.next()) {
                                if (System.nanoTime() > deadline)
                                    return result(
                                            "INCONCLUSIVE",
                                            snapshot,
                                            List.of(),
                                            "The extraction deadline exceeded; no partial rows are returned");
                                if (rows.size() == plan.maxRows())
                                    return result(
                                            "INCONCLUSIVE",
                                            snapshot,
                                            List.of(),
                                            "The extraction row budget exceeded; no partial rows are returned");
                                if (rs.getBoolean(plan.fields().size() + 1))
                                    return result(
                                            "INCONCLUSIVE",
                                            snapshot,
                                            List.of(),
                                            "A source cell exceeds the bounded SQL-side transfer budget; no partial rows are returned");
                                var row = new LinkedHashMap<String, Object>();
                                for (int i = 0; i < plan.fields().size(); i++) {
                                    String name = plan.fields().get(i);
                                    row.put(
                                            name,
                                            readCell(
                                                    rs,
                                                    i + 1,
                                                    properties.get(name),
                                                    columns.get(name)));
                                }
                                int rowBytes =
                                        json.canonical(json.parse(json.write(row)))
                                                .getBytes(StandardCharsets.UTF_8)
                                                .length;
                                bytes += rowBytes + (rows.isEmpty() ? 0 : 1);
                                if (bytes > plan.maxBytes())
                                    return result(
                                            "INCONCLUSIVE",
                                            snapshot,
                                            List.of(),
                                            "Canonical JSON UTF-8 byte budget exceeded; no partial rows are returned");
                                rows.add(row);
                            }
                        }
                    }
                    // 包括空结果和最后一次 rs.next()，截止时间之后绝不交付完整成功。
                    if (System.nanoTime() > deadline)
                        return result(
                                "INCONCLUSIVE",
                                snapshot,
                                List.of(),
                                "The extraction deadline exceeded before completion; no partial rows are returned");
                    return result(
                            "EXTRACTED",
                            snapshot,
                            rows,
                            rows.isEmpty()
                                    ? "No rows exist in the observed half-open event-time range; this is NO_RECORD, not zero"
                                    : "Rows belong only to the observed transaction and requested half-open event-time range");
                } finally {
                    connection.rollback();
                }
            }
        } catch (PostgresWarehouse.Failure ex) {
            return result(ex.status(), snapshot, List.of(), ex.getMessage());
        } catch (SQLException | IllegalArgumentException ex) {
            return result(
                    "UNAVAILABLE",
                    snapshot,
                    List.of(),
                    "PostgreSQL extraction could not complete with the required transport, privileges, schema and value types");
        }
    }

    private void validatePlan(Plan plan) {
        if (plan == null
                || plan.dataset() == null
                || plan.rowSchema() == null
                || plan.binding() == null)
            throw new PostgresWarehouse.Failure(
                    "BLOCKED", "An explicit Dataset, row schema and physical binding are required");
        if (!"postgres".equals(plan.binding().path("sourceSystem").asText())
                || plan.binding().path("externalRef").asText().isBlank())
            throw new PostgresWarehouse.Failure(
                    "BLOCKED",
                    "This extractor supports an explicit native postgres source binding only");
        if (plan.fields().isEmpty()
                || plan.fields().size() > MAX_FIELDS
                || new HashSet<>(plan.fields()).size() != plan.fields().size())
            throw new PostgresWarehouse.Failure(
                    "BLOCKED", "Select a bounded nonempty set of unique row-schema fields");
        if (plan.maxRows() < 1
                || plan.maxRows() > 100000
                || plan.maxRows() > warehouse.maxRows()
                || plan.maxBytes() < 1
                || plan.maxBytes() > MAX_BYTES)
            throw new PostgresWarehouse.Failure(
                    "BLOCKED", "Requested extraction exceeds the server row or byte budget");
        if (plan.maxBytes() < 2)
            throw new PostgresWarehouse.Failure(
                    "INCONCLUSIVE",
                    "Even the canonical empty row array exceeds the requested byte budget");
        if (plan.from() == null
                || plan.to() == null
                || !plan.from().isBefore(plan.to())
                || plan.from().getNano() % 1000 != 0
                || plan.to().getNano() % 1000 != 0)
            throw new PostgresWarehouse.Failure(
                    "BLOCKED",
                    "A positive half-open range aligned to PostgreSQL microsecond precision is required");
        if (plan.from().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || !plan.to().isBefore(Instant.parse("+10000-01-01T00:00:00Z")))
            throw new PostgresWarehouse.Failure(
                    "BLOCKED", "Only AD timestamps in years 0001 through 9999 are supported");
        if (!plan.dataset().time().roles().contains(TemporalContract.TimeRole.EVENT_TIME))
            throw new PostgresWarehouse.Failure(
                    "BLOCKED", "The Dataset must explicitly define EVENT_TIME");
        plan.fields().forEach(PostgresWarehouse::quoteIdentifier);
        plan.dataset().grain().keys().forEach(PostgresWarehouse::quoteIdentifier);
    }

    private void checkSchema(Plan plan, Catalog catalog, Map<String, Column> columns) {
        var grain = new TreeSet<>(plan.dataset().grain().keys());
        if (catalog.primaryKeys().isEmpty()
                || !grain.equals(new TreeSet<>(catalog.primaryKeys()))
                || !grain.equals(new TreeSet<>(plan.rowSchema().identityKeys())))
            throw new PostgresWarehouse.Failure(
                    "BLOCKED",
                    "Dataset grain and row-schema identity must match the observed physical primary key");
        var properties = new HashMap<String, ObjectType.Property>();
        for (var property : plan.rowSchema().properties()) {
            PostgresWarehouse.quoteIdentifier(property.name());
            properties.put(property.name(), property);
            Column column = columns.get(property.name());
            if (column == null
                    || !compatible(property.type(), column.nativeType())
                    || property.required() == column.nullable())
                throw new PostgresWarehouse.Failure(
                        "BLOCKED",
                        "The actual row-schema property type or nullability differs from the pinned model");
            if (!column.deterministic())
                throw new PostgresWarehouse.Failure(
                        "BLOCKED",
                        "Nondeterministic source collations require a supported equality/ordering policy");
        }
        for (String name : plan.fields()) {
            var property = properties.get(name);
            if (property == null)
                throw new PostgresWarehouse.Failure(
                        "BLOCKED", "A requested field is not declared by the pinned row schema");
            if (Set.of(ObjectType.DataType.JSON, ObjectType.DataType.ENTITY_REF)
                    .contains(property.type()))
                throw new PostgresWarehouse.Failure(
                        "BLOCKED",
                        "This extract profile does not define JSON or entity-reference cell encoding");
            if (property.type() == ObjectType.DataType.TIMESTAMP
                    && !columns.get(name).nativeType().equals("timestamptz"))
                throw new PostgresWarehouse.Failure(
                        "BLOCKED",
                        "Timestamp extraction requires timezone-aware PostgreSQL timestamptz values");
        }
    }

    static boolean compatible(ObjectType.DataType type, String nativeType) {
        return switch (type) {
            case STRING, ENTITY_REF -> Set.of("text", "varchar").contains(nativeType);
            case BOOLEAN -> nativeType.equals("bool");
            case INTEGER -> Set.of("int2", "int4", "int8").contains(nativeType);
            case DECIMAL -> nativeType.equals("numeric");
            case DATE -> nativeType.equals("date");
            case TIMESTAMP -> Set.of("timestamp", "timestamptz").contains(nativeType);
            case JSON -> Set.of("json", "jsonb").contains(nativeType);
        };
    }

    static String sql(Plan plan, String relation, String event) {
        int cellLimit = Math.min(4096, plan.maxBytes());
        var selected = new ArrayList<String>();
        var overflow = new ArrayList<String>();
        for (String field : plan.fields()) {
            String quoted = PostgresWarehouse.quoteIdentifier(field);
            String oversized =
                    "pg_catalog.octet_length(" + quoted + "::pg_catalog.text)>" + cellLimit;
            // SQL 先拦截超大 TOAST 单元，再传输到 JVM；不是先加载完整大值后再截断。
            selected.add(
                    "CASE WHEN " + oversized + " THEN NULL ELSE " + quoted + " END AS " + quoted);
            overflow.add("COALESCE(" + oversized + ",false)");
        }
        selected.add("(" + String.join(" OR ", overflow) + ") AS \"__awe_overflow\"");
        return "SELECT "
                + String.join(",", selected)
                + " FROM "
                + PostgresWarehouse.quoteRelation(relation)
                + " WHERE "
                + PostgresWarehouse.quoteIdentifier(event)
                + ">=? AND "
                + PostgresWarehouse.quoteIdentifier(event)
                + "<? ORDER BY "
                + String.join(
                        ",",
                        plan.dataset().grain().keys().stream()
                                .map(PostgresWarehouse::quoteIdentifier)
                                .toList())
                + " LIMIT "
                + (plan.maxRows() + 1);
    }

    private Object readCell(ResultSet rs, int index, ObjectType.Property property, Column column)
            throws SQLException {
        Object value =
                switch (property.type()) {
                    case STRING -> rs.getString(index);
                    case BOOLEAN -> {
                        boolean v = rs.getBoolean(index);
                        yield rs.wasNull() ? null : v;
                    }
                    case INTEGER -> {
                        long v = rs.getLong(index);
                        yield rs.wasNull() ? null : BigInteger.valueOf(v);
                    }
                    case DECIMAL -> {
                        BigDecimal v = rs.getBigDecimal(index);
                        if (v != null
                                && (v.precision() > 4096 || Math.abs((long) v.scale()) > 4096))
                            throw new PostgresWarehouse.Failure(
                                    "INCONCLUSIVE",
                                    "A numeric value exceeds the supported cell budget");
                        yield v;
                    }
                    case DATE -> {
                        LocalDate v = rs.getObject(index, LocalDate.class);
                        if (v != null && (v.getYear() < 1 || v.getYear() > 9999))
                            throw new PostgresWarehouse.Failure(
                                    "BLOCKED", "A date lies outside the supported AD date profile");
                        yield v == null ? null : v.toString();
                    }
                    case TIMESTAMP -> {
                        if (!column.nativeType().equals("timestamptz"))
                            throw new PostgresWarehouse.Failure(
                                    "BLOCKED",
                                    "Local timestamps have no supported extraction encoding");
                        OffsetDateTime v = rs.getObject(index, OffsetDateTime.class);
                        if (v != null && (v.getYear() < 1 || v.getYear() > 9999))
                            throw new PostgresWarehouse.Failure(
                                    "BLOCKED",
                                    "A timestamp lies outside the supported AD date profile");
                        yield v == null ? null : v.toInstant().toString();
                    }
                    default ->
                            throw new PostgresWarehouse.Failure(
                                    "BLOCKED",
                                    "Unsupported cell encoding for this extraction profile");
                };
        if (value == null && property.required())
            throw new PostgresWarehouse.Failure(
                    "BLOCKED", "The source returned null for a required row-schema field");
        return value;
    }

    private Result result(
            String status, String snapshot, List<Map<String, Object>> rows, String limitation) {
        return new Result(
                status,
                snapshot,
                rows,
                List.of(
                        limitation,
                        "Only an observed transaction snapshot is established; accounting-period readiness and independent business correctness are not verified",
                        "Transaction snapshot hashes cannot restore historical rows after the read-only transaction ends"));
    }
}
