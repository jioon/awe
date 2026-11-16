package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.LinkType;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.model.LogicalDatasetBindings;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import ai.awe.registry.support.JsonDocuments;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** PostgreSQL 执行已发布的类型化数据计划；实际计算仍在数据库，调用方不能提交 SQL。 */
@Component
public final class PostgresMetricExecutor {
    public record Plan(
            MetricContract contract,
            Map<String, BoundQuerySource> sources,
            Map<EntityRef, LinkType> links,
            Instant from,
            Instant to,
            int maxRows,
            int maxBytes,
            Map<String, RelationalIR.Predicate> mandatoryScopes) {
        public Plan {
            Objects.requireNonNull(contract);
            sources = Map.copyOf(sources);
            links = Map.copyOf(links);
            mandatoryScopes = Map.copyOf(mandatoryScopes);
        }

        public Plan(
                MetricContract contract,
                Map<String, BoundQuerySource> sources,
                Map<EntityRef, LinkType> links,
                Instant from,
                Instant to,
                int maxRows,
                int maxBytes) {
            this(contract, sources, links, from, to, maxRows, maxBytes, Map.of());
        }
    }

    public record Result(
            String status,
            List<Map<String, Object>> rows,
            Map<String, String> snapshots,
            List<String> limitations) {
        public Result {
            rows =
                    rows.stream()
                            .map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r)))
                            .toList();
            snapshots = Map.copyOf(snapshots);
            limitations = List.copyOf(limitations);
        }
    }

    private final PostgresWarehouse warehouse;
    private final JsonDocuments json;
    private final ObjectProvider<LogicalDatasetBindings> realizations;

    public PostgresMetricExecutor(PostgresWarehouse warehouse, JsonDocuments json) {
        this(warehouse, json, null);
    }

    @Autowired
    public PostgresMetricExecutor(
            PostgresWarehouse warehouse,
            JsonDocuments json,
            ObjectProvider<LogicalDatasetBindings> realizations) {
        this.warehouse = warehouse;
        this.json = json;
        this.realizations = realizations;
    }

    public Result execute(Plan plan) {
        var snapshots = new TreeMap<String, String>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try {
            validate(plan);
            if (!warehouse.configured())
                return result(
                        warehouse.status(),
                        List.of(),
                        snapshots,
                        "Native PostgreSQL metric execution is not configured or supported");
            try (Connection connection = warehouse.openReadOnly()) {
                try {
                    var catalogs = new TreeMap<String, PostgresWarehouse.Catalog>();
                    // 固定锁顺序，并在同一个事务中检查每个物理来源及快照，禁止混用历史版本或另一目标的成功。
                    var ordered =
                            plan.sources().values().stream()
                                    .sorted(
                                            Comparator.comparing(
                                                    s -> s.binding().path("externalRef").asText()))
                                    .toList();
                    for (var source : ordered) {
                        if (!"postgres".equals(source.binding().path("sourceSystem").asText()))
                            throw blocked(
                                    "Native metric executor cannot silently switch a non-PostgreSQL binding");
                        String external = source.binding().path("externalRef").asText();
                        var catalog = warehouse.inspect(connection, external, deadline);
                        snapshots.put(
                                source.alias(),
                                warehouse.snapshotRef(connection, external, deadline));
                        if (!json.sha256(warehouse.metadata(catalog, external))
                                .equals(source.binding().path("fingerprint").asText()))
                            throw blocked(
                                    "Source schema/projection fingerprint changed after the published binding");
                        checkSchema(source, catalog);
                        catalogs.put(source.alias(), catalog);
                    }
                    // RR 数据快照先固定，再检查当前生产 fence；之后发生的合法部署不能改变本次可见行。
                    currentRealizations(plan.sources(), realizations);
                    var compiler =
                            new RelationalSqlCompiler(
                                    plan.contract(),
                                    plan.sources(),
                                    catalogs,
                                    plan.links(),
                                    plan.mandatoryScopes(),
                                    plan.from(),
                                    plan.to());
                    var compiled = compiler.compile();
                    int total = 0;
                    for (var scan : compiler.scanChecks()) {
                        try (PreparedStatement stmt =
                                connection.prepareStatement(scan.countSql())) {
                            PostgresWarehouse.constrain(stmt, deadline);
                            bind(stmt, scan.parameters());
                            stmt.setInt(
                                    scan.parameters().size() + 1, warehouse.maxRows() - total + 1);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (!rs.next())
                                    throw blocked("Source row budget could not be established");
                                total += rs.getInt(1);
                                if (total > warehouse.maxRows())
                                    return result(
                                            "INCONCLUSIVE",
                                            List.of(),
                                            snapshots,
                                            "Matching source-row budget exceeded; no partial aggregate is returned");
                            }
                        }
                        if (plan.contract().identityNullPolicy()
                                        == MetricContract.IdentityNullPolicy.REJECT
                                && !scan.nullIdentitySql().isEmpty()) {
                            try (PreparedStatement stmt =
                                    connection.prepareStatement(scan.nullIdentitySql())) {
                                PostgresWarehouse.constrain(stmt, deadline);
                                bind(stmt, scan.parameters());
                                try (ResultSet rs = stmt.executeQuery()) {
                                    if (rs.next())
                                        throw blocked(
                                                "A statistical identity is NULL inside the authorized source scan under REJECT policy");
                                }
                            }
                        }
                    }
                    String sql = boundedResultSql(compiled, plan.maxRows(), plan.maxBytes());
                    var rows = new ArrayList<Map<String, Object>>();
                    var grain = new HashSet<List<Object>>();
                    int bytes = 2;
                    if (bytes > plan.maxBytes())
                        return result(
                                "INCONCLUSIVE",
                                List.of(),
                                snapshots,
                                "Even an empty JSON result exceeds the requested byte budget");
                    try (PreparedStatement stmt =
                            connection.prepareStatement(
                                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        PostgresWarehouse.constrain(stmt, deadline);
                        stmt.setFetchSize(Math.min(plan.maxRows() + 1, 128));
                        stmt.setMaxRows(plan.maxRows() + 1);
                        bind(stmt, compiled.parameters());
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                if (System.nanoTime() > deadline)
                                    return result(
                                            "INCONCLUSIVE",
                                            List.of(),
                                            snapshots,
                                            "Metric execution deadline exceeded; no partial result is accepted");
                                if (rows.size() == plan.maxRows())
                                    return result(
                                            "INCONCLUSIVE",
                                            List.of(),
                                            snapshots,
                                            "Output-row budget exceeded; no partial group set is accepted");
                                if (rs.getBoolean(compiled.columns().size() + 1))
                                    return result(
                                            "INCONCLUSIVE",
                                            List.of(),
                                            snapshots,
                                            "A result cell exceeded the SQL-side transfer budget");
                                var row = new LinkedHashMap<String, Object>();
                                for (int i = 0; i < compiled.columns().size(); i++) {
                                    var column = compiled.columns().get(i);
                                    row.put(column.name(), cell(rs, i + 1, column));
                                }
                                var key = new ArrayList<Object>();
                                for (String name : plan.contract().grain()) {
                                    Object value = row.get(name);
                                    key.add(
                                            value instanceof BigDecimal n
                                                    ? n.stripTrailingZeros()
                                                    : value);
                                }
                                if (!grain.add(Collections.unmodifiableList(key)))
                                    throw blocked(
                                            "Actual result rows violate the declared output grain");
                                bytes +=
                                        json.canonical(json.parse(json.write(row)))
                                                        .getBytes(StandardCharsets.UTF_8)
                                                        .length
                                                + (rows.isEmpty() ? 0 : 1);
                                if (bytes > plan.maxBytes())
                                    return result(
                                            "INCONCLUSIVE",
                                            List.of(),
                                            snapshots,
                                            "Canonical JSON byte budget exceeded; no partial result is accepted");
                                rows.add(row);
                            }
                        }
                    }
                    if (rows.isEmpty()
                            && plan.contract().emptyPolicy() == MetricContract.EmptyPolicy.ZERO) {
                        if (!plan.contract().grain().isEmpty()
                                || plan.contract().outputs().stream()
                                        .anyMatch(
                                                o ->
                                                        o.type() != ObjectType.DataType.INTEGER
                                                                && o.type()
                                                                        != ObjectType.DataType
                                                                                .DECIMAL))
                            throw blocked(
                                    "ZERO empty policy cannot invent dimension values or nonnumeric outputs");
                        var zero = new LinkedHashMap<String, Object>();
                        plan.contract()
                                .outputs()
                                .forEach(
                                        o ->
                                                zero.put(
                                                        o.name(),
                                                        o.type() == ObjectType.DataType.INTEGER
                                                                ? java.math.BigInteger.ZERO
                                                                : BigDecimal.ZERO));
                        if (json.canonical(json.parse(json.write(List.of(zero))))
                                        .getBytes(StandardCharsets.UTF_8)
                                        .length
                                > plan.maxBytes())
                            return result(
                                    "INCONCLUSIVE",
                                    List.of(),
                                    snapshots,
                                    "Explicit empty-output ZERO policy exceeds the result budget");
                        rows.add(zero);
                    }
                    if (System.nanoTime() > deadline)
                        return result(
                                "INCONCLUSIVE",
                                List.of(),
                                snapshots,
                                "Metric execution exceeded its deadline before completion");
                    return result(
                            "QUERIED",
                            rows,
                            snapshots,
                            "The published relational plan was evaluated against one read-only PostgreSQL transaction; source-row and complete result budgets were enforced");
                } finally {
                    connection.rollback();
                }
            }
        } catch (PostgresWarehouse.Failure ex) {
            return result(ex.status(), List.of(), snapshots, ex.getMessage());
        } catch (SQLException ex) {
            if ("22012".equals(ex.getSQLState()))
                return result(
                        "BLOCKED",
                        List.of(),
                        snapshots,
                        "Division by zero violated the published REJECT policy");
            if ("57014".equals(ex.getSQLState()))
                return result(
                        "INCONCLUSIVE",
                        List.of(),
                        snapshots,
                        "Source statement deadline exceeded; no partial result is accepted");
            return result(
                    "UNAVAILABLE",
                    List.of(),
                    snapshots,
                    "The source could not complete the typed plan using the configured transport, privileges and exact value encodings");
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException ex) {
            return result(
                    "BLOCKED",
                    List.of(),
                    snapshots,
                    "The metric plan or values exceed the supported exact type/time profile");
        }
    }

    private void validate(Plan plan) {
        if (plan == null
                || plan.from() == null
                || plan.to() == null
                || !plan.from().isBefore(plan.to())
                || plan.from().getNano() % 1000 != 0
                || plan.to().getNano() % 1000 != 0
                || plan.maxRows() < 1
                || plan.maxRows() > 100_000
                || plan.maxBytes() < 1
                || plan.maxBytes() > 1_048_576)
            throw blocked(
                    "Metric request requires exact microsecond time boundaries and bounded result budgets");
        if (plan.from().atZone(plan.contract().businessZone()).getYear() < 1
                || plan.to().atZone(plan.contract().businessZone()).getYear() > 9999)
            throw blocked("Metric time range is outside the supported AD date profile");
        if (!plan.sources()
                .keySet()
                .equals(
                        plan.contract().sources().stream()
                                .map(MetricContract.Source::alias)
                                .collect(java.util.stream.Collectors.toSet())))
            throw blocked("Metric sources are not the exact declared set");
        for (var entry : plan.sources().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().alias()))
                throw blocked("Resolved source aliases differ from the declared plan");
            validateRealization(entry.getValue());
        }
        var schemas = new TreeMap<String, ObjectType>();
        plan.sources().forEach((alias, source) -> schemas.put(alias, source.rowSchema()));
        SemanticPlanTypes.validate(plan.contract(), schemas);
    }

    /** 逻辑目标仅能使用可信生产 SPI 给出的精确实现证明，不能由请求里的 flag 升格为物理源。 */
    static void validateRealization(BoundQuerySource source) {
        if (source.datasetContract()
                .map(c -> c.mode() == DatasetContract.Mode.LOGICAL_TARGET)
                .orElse(false)) {
            var proof =
                    source.realization()
                            .orElseThrow(
                                    () ->
                                            blocked(
                                                    "Logical target has no trusted exact implementation proof"));
            if (!source.dataset().ref().equals(proof.dataset())
                    || !source.releaseId().equals(proof.datasetReleaseId())
                    || !source.contentHash().equals(proof.datasetContentHash())
                    || !source.rowSchema().ref().equals(proof.rowSchema())
                    || !source.binding().equals(proof.binding())
                    || !source.binding().path("fingerprint").asText().matches("[a-f0-9]{64}")
                    || proof.realizationId() == null
                    || proof.consumptionId() == null
                    || proof.sourceSnapshotId() == null
                    || proof.artifactHash() == null
                    || !proof.artifactHash().matches("[a-f0-9]{64}")
                    || proof.deploymentRef() == null
                    || proof.deploymentRef().isBlank()
                    || proof.runRef() == null
                    || proof.runRef().isBlank()
                    || proof.attemptRef() == null
                    || proof.attemptRef().isBlank())
                throw blocked(
                        "Logical source realization does not bind this exact Dataset, schema, deployment and source fingerprint");
        } else if (source.realization().isPresent()) {
            throw blocked(
                    "An observed source cannot borrow another logical target's realization proof");
        }
    }

    static void currentRealizations(
            Map<String, BoundQuerySource> sources,
            ObjectProvider<LogicalDatasetBindings> providers) {
        for (var source : sources.values())
            if (source.datasetContract()
                    .map(c -> c.mode() == DatasetContract.Mode.LOGICAL_TARGET)
                    .orElse(false)) {
                validateRealization(source);
                if (providers == null)
                    throw blocked("No trusted current realization resolver is available");
                var current =
                        providers
                                .orderedStream()
                                .map(p -> p.realization(source.dataset().ref()))
                                .flatMap(Optional::stream)
                                .toList();
                if (current.size() != 1
                        || !current.getFirst().equals(source.realization().orElseThrow()))
                    throw blocked(
                            "Logical source was fenced or changed after accepted-plan resolution");
            }
    }

    private void checkSchema(BoundQuerySource source, PostgresWarehouse.Catalog catalog) {
        validateRealization(source);
        Set<String> keys = Set.copyOf(catalog.primaryKeys());
        if (keys.isEmpty()
                || !keys.equals(Set.copyOf(source.dataset().grain().keys()))
                || !keys.equals(Set.copyOf(source.rowSchema().identityKeys())))
            throw blocked(
                    "Actual source primary key does not prove the declared Dataset and row-schema grain");
        var columns = new HashMap<String, PostgresWarehouse.Column>();
        catalog.columns().forEach(c -> columns.put(c.name(), c));
        if (source.rowSchema().properties().size() > 64)
            throw blocked("Source row schema exceeds the supported column budget");
        for (var property : source.rowSchema().properties()) {
            PostgresWarehouse.quoteIdentifier(property.name());
            var column = columns.get(property.name());
            if (column == null
                    || !compatible(property.type(), column.nativeType())
                    || property.required() && column.nullable()
                    || !column.deterministic())
                throw blocked(
                        "Actual source fields differ from the pinned row-schema type, nullability or equality contract");
        }
        if (!source.staticSnapshot()) {
            String time =
                    source.binding().path("timeFields").path(source.rangeRole().name()).asText();
            var column = columns.get(time);
            if (column == null
                    || !(column.nativeType().equals("timestamptz")
                            || column.nativeType().equals("date")
                                    && source.rangeRole()
                                            == TemporalContract.TimeRole.BUSINESS_DATE))
                throw blocked("Window source has no exact supported time-role mapping");
        }
    }

    private static boolean compatible(ObjectType.DataType type, String nativeType) {
        return switch (type) {
            case STRING -> Set.of("text", "varchar").contains(nativeType);
            case BOOLEAN -> nativeType.equals("bool");
            case INTEGER -> Set.of("int2", "int4", "int8").contains(nativeType);
            case DECIMAL -> nativeType.equals("numeric");
            case DATE -> nativeType.equals("date");
            case TIMESTAMP -> nativeType.equals("timestamptz");
            default -> false;
        };
    }

    private static String boundedResultSql(
            RelationalSqlCompiler.Compiled compiled, int maxRows, int maxBytes) {
        var selected = new ArrayList<String>();
        var overflow = new ArrayList<String>();
        for (var column : compiled.columns()) {
            String ref = "\"metric_result\"." + PostgresWarehouse.quoteIdentifier(column.name());
            String excessive =
                    "pg_catalog.octet_length("
                            + ref
                            + "::pg_catalog.text)>"
                            + Math.min(maxBytes, 4096);
            selected.add(
                    "CASE WHEN "
                            + excessive
                            + " THEN NULL ELSE "
                            + ref
                            + " END AS "
                            + PostgresWarehouse.quoteIdentifier(column.name()));
            overflow.add("COALESCE(" + excessive + ",false)");
        }
        selected.add("(" + String.join(" OR ", overflow) + ") AS \"__awe_overflow\"");
        return "SELECT "
                + String.join(",", selected)
                + " FROM ("
                + compiled.sql()
                + ") \"metric_result\" LIMIT "
                + (maxRows + 1);
    }

    public static void bind(PreparedStatement stmt, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) stmt.setObject(i + 1, parameters.get(i));
    }

    private static Object cell(ResultSet rs, int index, RelationalSqlCompiler.Column column)
            throws SQLException {
        Object value =
                switch (column.type()) {
                    case STRING -> rs.getString(index);
                    case BOOLEAN -> {
                        boolean v = rs.getBoolean(index);
                        yield rs.wasNull() ? null : v;
                    }
                    case INTEGER -> {
                        BigDecimal n = rs.getBigDecimal(index);
                        yield n == null ? null : n.toBigIntegerExact();
                    }
                    case DECIMAL -> rs.getBigDecimal(index);
                    case DATE -> {
                        LocalDate d = rs.getObject(index, LocalDate.class);
                        if (d != null && (d.getYear() < 1 || d.getYear() > 9999))
                            throw blocked("Unsupported DATE value");
                        yield d == null ? null : d.toString();
                    }
                    case TIMESTAMP -> {
                        OffsetDateTime t = rs.getObject(index, OffsetDateTime.class);
                        if (t != null && (t.getYear() < 1 || t.getYear() > 9999))
                            throw blocked("Unsupported TIMESTAMP value");
                        yield t == null ? null : t.toInstant().toString();
                    }
                    default -> throw blocked("Unsupported metric output encoding");
                };
        if (value == null && !column.nullable())
            throw blocked("Actual metric output violated the published non-null contract");
        return value;
    }

    private Result result(
            String status,
            List<Map<String, Object>> rows,
            Map<String, String> snapshots,
            String note) {
        return new Result(
                status,
                rows,
                snapshots,
                List.of(
                        note,
                        "Observed transaction results do not establish accounting-period completeness; independent readiness evidence is required for PERIOD_COMPLETE",
                        "Structural plan execution is not independent numerical reconciliation or overall business-task acceptance",
                        "Snapshot hashes identify the observed target/transaction and cannot restore historical rows"));
    }

    private static PostgresWarehouse.Failure blocked(String message) {
        return new PostgresWarehouse.Failure("BLOCKED", message);
    }
}
