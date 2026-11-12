package ai.awe.registry.runtime.jdbc;

import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Bounded, read-only execution of built-in data obligations against an administrator-bound
 * PostgreSQL relation.
 */
@Component
public final class JdbcContractExecutor {
    public static final Set<String> RULE_IDS =
            Set.of(
                    "check.grain.unique",
                    "check.grain.not_null",
                    "check.time.available_after_event");
    private static final String UNIQUE = "check.grain.unique",
            NOT_NULL = "check.grain.not_null",
            AVAILABLE = "check.time.available_after_event";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final int MAX_COLUMNS = 64, MAX_CELL_LENGTH = 4096;
    static final long MAX_RETAINED_KEY_BYTES = 16L * 1024 * 1024;
    private final String url, user, password;
    private final JsonDocuments json;
    private final Map<String, String> relations;
    private final int maxRows;
    private final String configurationState;
    private final boolean loopback;

    public record Result(
            String status,
            String dataSnapshotRef,
            int rowsChecked,
            Map<String, Long> violations,
            List<String> limitations) {
        public Result {
            violations = Collections.unmodifiableMap(new TreeMap<>(violations));
            limitations = List.copyOf(limitations);
        }
    }

    public JdbcContractExecutor(Environment environment, JsonDocuments json) {
        this.json = json;
        url = environment.getProperty("AWE_WAREHOUSE_JDBC_URL", "").trim();
        user = environment.getProperty("AWE_WAREHOUSE_JDBC_USER", "").trim();
        password = environment.getProperty("AWE_WAREHOUSE_JDBC_PASSWORD", "");
        String rawBindings = environment.getProperty("AWE_WAREHOUSE_ASSET_BINDINGS", "");
        int rows = 10_000;
        Map<String, String> parsed = new TreeMap<>();
        String state = "CONFIGURED";
        boolean local = false;
        try {
            String value = environment.getProperty("AWE_WAREHOUSE_MAX_ROWS", "10000");
            if (!value.matches("[1-9][0-9]{0,5}")) throw new IllegalArgumentException();
            rows = Integer.parseInt(value);
            if (rows > 100_000) throw new IllegalArgumentException();
            if (!rawBindings.isBlank()) {
                JsonNode bindings = json.object(rawBindings);
                if (bindings.size() > 1000) throw new IllegalArgumentException();
                var fields = bindings.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (!field.getKey().matches("[a-z][a-z0-9_-]*:[^\\p{Cntrl}]{1,500}")
                            || !field.getValue().isTextual()) throw new IllegalArgumentException();
                    String relation = field.getValue().asText();
                    quoteRelation(relation);
                    parsed.put(field.getKey(), relation);
                }
            }
            if (!url.isBlank()) local = validateUrl(url);
            if (url.isBlank() || user.isBlank() || password.isEmpty() || parsed.isEmpty())
                state = "NOT_CONFIGURED";
        } catch (IllegalArgumentException ex) {
            state = "UNAVAILABLE";
        }
        relations = Collections.unmodifiableMap(parsed);
        maxRows = rows;
        configurationState = state;
        loopback = local;
    }

    /**
     * Presence of usable supported configuration, not a connectivity or authorization guarantee.
     */
    public boolean configured() {
        return "CONFIGURED".equals(configurationState);
    }

    public Result execute(SemanticModel.Dataset model, JsonNode binding, Set<String> ruleIds) {
        Objects.requireNonNull(model, "Dataset model required");
        Set<String> rules = checkedRules(ruleIds);
        var counts = new TreeMap<String, Long>();
        rules.forEach(rule -> counts.put(rule, 0L));
        if (!configured())
            return result(
                    configurationState,
                    "",
                    0,
                    counts,
                    "NOT_CONFIGURED".equals(configurationState)
                            ? "Warehouse JDBC connection and administrator asset mapping are required"
                            : "Warehouse JDBC configuration is invalid or unsupported");
        if (binding == null
                || !binding.isObject()
                || !binding.path("sourceSystem").isTextual()
                || !binding.path("externalRef").isTextual())
            return result("UNAVAILABLE", "", 0, counts, "An explicit source binding is required");
        String relation =
                relations.get(
                        binding.path("sourceSystem").asText()
                                + ":"
                                + binding.path("externalRef").asText());
        if (relation == null)
            return result(
                    "NOT_CONFIGURED",
                    "",
                    0,
                    counts,
                    "This source asset has no administrator-configured validation relation");
        Plan plan;
        try {
            plan = plan(model, binding, rules, relation, maxRows);
        } catch (UnsupportedContract ex) {
            return result("INCONCLUSIVE", "", 0, counts, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return result(
                    "UNAVAILABLE",
                    "",
                    0,
                    counts,
                    "Model or binding uses unsupported physical identifiers");
        }
        var properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        properties.setProperty("readOnlyMode", "transaction");
        // URL sslmode is separately allowlisted; set a safe default even if a database/path
        // contains that text.
        properties.setProperty("sslmode", loopback ? "disable" : "verify-full");
        String snapshot = "";
        int rows = 0;
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            // 只读可重复读事务同时约束数据扫描和证据快照；完成或失败均回滚，不写入来源。
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                // 同一事务获取真实 MVCC 快照；仅保留有界哈希，不能据此恢复历史数据。
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(15);
                    try (ResultSet rs =
                            statement.executeQuery(
                                    "SELECT pg_catalog.txid_current_snapshot()::text")) {
                        if (!rs.next())
                            return result(
                                    "UNAVAILABLE",
                                    "",
                                    0,
                                    counts,
                                    "The data transaction did not expose a snapshot");
                        String observedSnapshot = rs.getString(1);
                        if (observedSnapshot == null
                                || observedSnapshot.isBlank()
                                || observedSnapshot.length() > 1_048_576)
                            throw new UnsupportedContract(
                                    "The transaction snapshot exceeded the supported evidence budget");
                        snapshot =
                                snapshotReference(
                                        observedSnapshot,
                                        relation,
                                        binding.path("sourceSystem").asText(),
                                        binding.path("externalRef").asText());
                    }
                }
                if (!unrestrictedRowScope(connection, relation))
                    throw new UnsupportedContract(
                            "Row-level-security relations require an explicit supported row-scope policy before whole-relation checks");
                if (rules.contains(UNIQUE)
                        && !deterministicGrainCollations(
                                connection, relation, model.grain().keys()))
                    throw new UnsupportedContract(
                            "Nondeterministic source collation prevents exact client-side grain uniqueness verification");
                // 行数、执行时限和唯一键缓存共同限额；任何不完整扫描都不能返回 PASSED。
                var seen = new HashSet<List<Object>>();
                long retainedKeyBytes = 0;
                long scanDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                boolean complete = true;
                try (Statement statement =
                        connection.createStatement(
                                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    statement.setQueryTimeout(15);
                    statement.setFetchSize(Math.min(maxRows + 1, 256));
                    statement.setMaxRows(maxRows + 1);
                    try (ResultSet rs = statement.executeQuery(plan.sql())) {
                        ResultSetMetaData metadata = rs.getMetaData();
                        while (rs.next()) {
                            if (System.nanoTime() > scanDeadline)
                                throw new UnsupportedContract(
                                        "Data scan deadline exceeded; bounded partial counts cannot establish a complete pass");
                            if (rows == maxRows) {
                                complete = false;
                                break;
                            }
                            rows++;
                            if (rules.contains(UNIQUE) || rules.contains(NOT_NULL)) {
                                var key = new ArrayList<Object>();
                                boolean missing = false;
                                for (String field : model.grain().keys()) {
                                    Object value = rs.getObject(plan.indexOf(field));
                                    missing |= value == null;
                                    key.add(normalizeKey(value));
                                }
                                if (rules.contains(NOT_NULL) && missing)
                                    counts.merge(NOT_NULL, 1L, Long::sum);
                                if (rules.contains(UNIQUE)) {
                                    List<Object> stableKey = Collections.unmodifiableList(key);
                                    if (seen.contains(stableKey))
                                        counts.merge(UNIQUE, 1L, Long::sum);
                                    else {
                                        retainedKeyBytes =
                                                retainKeyBudget(retainedKeyBytes, stableKey);
                                        seen.add(stableKey);
                                    }
                                }
                            }
                            if (rules.contains(AVAILABLE)) {
                                Instant event =
                                        instant(
                                                rs,
                                                metadata,
                                                plan.indexOf(plan.eventField()),
                                                model.time().businessZone());
                                Instant available =
                                        instant(
                                                rs,
                                                metadata,
                                                plan.indexOf(plan.availabilityField()),
                                                model.time().businessZone());
                                if (event == null || available == null || available.isBefore(event))
                                    counts.merge(AVAILABLE, 1L, Long::sum);
                            }
                        }
                    }
                }
                if (!complete)
                    return result(
                            "INCONCLUSIVE",
                            snapshot,
                            rows,
                            counts,
                            "Row budget exceeded; counts cover only the bounded prefix and cannot establish a whole-relation pass");
                if (rows == 0)
                    return result(
                            "INCONCLUSIVE",
                            snapshot,
                            0,
                            counts,
                            "No source rows were available to establish data-level evidence");
                boolean failed = counts.values().stream().anyMatch(value -> value > 0);
                return result(
                        failed ? "FAILED" : "PASSED",
                        snapshot,
                        rows,
                        counts,
                        "Checks apply only to this configured PostgreSQL relation and transaction snapshot; they do not prove general business truth");
            } finally {
                connection.rollback();
            }
        } catch (UnsupportedContract ex) {
            return result("INCONCLUSIVE", snapshot, rows, counts, ex.getMessage());
        } catch (SQLException | IllegalArgumentException ex) {
            // JDBC messages can include URLs, SQL, credentials or data values; keep the boundary
            // value-free.
            return result(
                    "UNAVAILABLE",
                    snapshot,
                    rows,
                    counts,
                    "The configured read-only PostgreSQL check could not complete; verify connectivity, privileges, schema and budgets");
        }
    }

    /**
     * Fixed-size provenance binds the administrator projection and actual transaction without
     * connection details.
     */
    String snapshotReference(
            String observedSnapshot, String relation, String sourceSystem, String externalRef) {
        // 配置身份进入指纹，避免换用不同权限身份后误把另一数据范围当作原修复证据。
        String projectionHash =
                json.sha256(
                        json.object(
                                json.write(
                                        Map.of(
                                                "jdbcURL",
                                                url,
                                                "jdbcUser",
                                                user,
                                                "relation",
                                                relation,
                                                "sourceSystem",
                                                sourceSystem,
                                                "externalRef",
                                                externalRef))));
        String transactionHash = json.sha256(json.parse(json.write(observedSnapshot)));
        return "postgresql:binding:" + projectionHash + ":tx_snapshot_sha256:" + transactionHash;
    }

    private Result result(
            String status, String snapshot, int rows, Map<String, Long> counts, String limitation) {
        return new Result(
                status,
                snapshot,
                rows,
                counts,
                List.of(
                        limitation,
                        "Transaction snapshot identifiers cannot restore historical rows after this read-only transaction ends",
                        "Duplicate counts are rows beyond the first equal whole-grain key; null timestamps fail the requested availability obligation"));
    }

    /** 未定义行级策略作用域时，拒绝把 RLS 可见子集冒充整张绑定关系的数据。 */
    static boolean unrestrictedRowScope(Connection connection, String relation)
            throws SQLException {
        String quotedRelation = quoteRelation(relation);
        try (Statement lock = connection.createStatement()) {
            lock.setQueryTimeout(15);
            lock.execute("LOCK TABLE " + quotedRelation + " IN ACCESS SHARE MODE");
        }
        String sql =
                "SELECT relrowsecurity OR relforcerowsecurity FROM pg_catalog.pg_class "
                        + "WHERE oid=pg_catalog.to_regclass(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(15);
            statement.setString(1, quotedRelation);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && !rs.getBoolean(1);
            }
        }
    }

    /** 非确定性排序规则可能令 SQL 的 A=a，而 Java 字符串不等；此时拒绝宣称唯一性通过。 */
    static boolean deterministicGrainCollations(
            Connection connection, String relation, List<String> grainKeys) throws SQLException {
        String quotedRelation = quoteRelation(relation);
        // ACCESS SHARE 是只读锁，防止检查排序规则后、扫描前并发 DDL 改变列定义。
        try (Statement lock = connection.createStatement()) {
            lock.setQueryTimeout(15);
            lock.execute("LOCK TABLE " + quotedRelation + " IN ACCESS SHARE MODE");
        }
        String sql =
                "SELECT 1 FROM pg_catalog.pg_attribute a "
                        + "JOIN pg_catalog.pg_collation c ON c.oid=a.attcollation "
                        + "WHERE a.attrelid=pg_catalog.to_regclass(?) AND a.attname::text=ANY(?::text[]) "
                        + "AND a.attnum>0 AND NOT a.attisdropped AND NOT c.collisdeterministic LIMIT 1";
        java.sql.Array names = connection.createArrayOf("text", grainKeys.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(15);
            statement.setString(1, quotedRelation);
            statement.setArray(2, names);
            try (ResultSet rs = statement.executeQuery()) {
                return !rs.next();
            }
        } finally {
            names.free();
        }
    }

    static Set<String> checkedRules(Set<String> rules) {
        if (rules == null
                || rules.isEmpty()
                || rules.stream().anyMatch(rule -> rule == null || !RULE_IDS.contains(rule)))
            throw new IllegalArgumentException(
                    "Select one or more supported built-in EXECUTE data rules");
        return Set.copyOf(rules);
    }

    static String quoteIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches())
            throw new IllegalArgumentException("Unsupported SQL identifier");
        return '"' + value + '"';
    }

    static String quoteRelation(String value) {
        if (value == null)
            throw new IllegalArgumentException("Explicit schema.table mapping required");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2)
            throw new IllegalArgumentException("Explicit schema.table mapping required");
        return quoteIdentifier(parts[0]) + "." + quoteIdentifier(parts[1]);
    }

    record Plan(String sql, List<String> columns, String eventField, String availabilityField) {
        int indexOf(String name) {
            return columns.indexOf(name) + 1;
        }
    }

    static Plan plan(
            SemanticModel.Dataset model,
            JsonNode binding,
            Set<String> rules,
            String relation,
            int maxRows) {
        var columns = new LinkedHashSet<String>();
        if (rules.contains(UNIQUE) || rules.contains(NOT_NULL))
            columns.addAll(model.grain().keys());
        String event = "", available = "";
        if (rules.contains(AVAILABLE)) {
            if (!model.time().roles().contains(TemporalContract.TimeRole.EVENT_TIME)
                    || Collections.disjoint(
                            model.time().roles(),
                            Set.of(
                                    TemporalContract.TimeRole.AVAILABLE_AT,
                                    TemporalContract.TimeRole.SYSTEM_TIME)))
                throw new UnsupportedContract(
                        "Availability ordering requires explicit event and available/system time roles");
            event = binding.path("timeFields").path("EVENT_TIME").asText();
            String role =
                    model.time().roles().contains(TemporalContract.TimeRole.AVAILABLE_AT)
                            ? "AVAILABLE_AT"
                            : "SYSTEM_TIME";
            available = binding.path("timeFields").path(role).asText();
            if (event.isBlank() || available.isBlank() || event.equals(available))
                throw new UnsupportedContract(
                        "Availability ordering requires distinct explicit event and availability field mappings");
            columns.add(event);
            columns.add(available);
        }
        if (columns.isEmpty() || columns.size() > MAX_COLUMNS)
            throw new UnsupportedContract(
                    "Runtime column budget exceeded or no checkable columns selected");
        String fields =
                String.join(
                        ",", columns.stream().map(JdbcContractExecutor::quoteIdentifier).toList());
        return new Plan(
                "SELECT " + fields + " FROM " + quoteRelation(relation) + " LIMIT " + (maxRows + 1),
                List.copyOf(columns),
                event,
                available);
    }

    /**
     * Conservative retained-key budget includes tuple/hash-node/reference overhead and scalar
     * payloads.
     */
    static long retainKeyBudget(long retainedBytes, List<Object> key) {
        long bytes = 128L + 16L * key.size();
        for (Object value : key) {
            if (value instanceof String text) bytes += 64L + 2L * text.length();
            else if (value instanceof BigDecimal number)
                bytes += 128L + (number.unscaledValue().bitLength() + 7L) / 8L;
            else if (value instanceof byte[] raw) bytes += 64L + raw.length;
            else bytes += 128L;
        }
        if (retainedBytes < 0
                || retainedBytes > MAX_RETAINED_KEY_BYTES
                || bytes > MAX_RETAINED_KEY_BYTES - retainedBytes)
            throw new UnsupportedContract(
                    "Retained grain-key memory budget exceeded; partial counts cannot establish a complete pass");
        return retainedBytes + bytes;
    }

    private Object normalizeKey(Object value) {
        if (value == null) return null;
        if (value instanceof String text) {
            if (text.length() > MAX_CELL_LENGTH)
                throw new UnsupportedContract(
                        "A grain value exceeds the runtime cell budget; no complete pass can be established");
            return text;
        }
        if (value instanceof BigDecimal number) return number.stripTrailingZeros();
        if (value instanceof Float number) return number == 0f ? 0f : number;
        if (value instanceof Double number) return number == 0d ? 0d : number;
        if (value instanceof byte[] bytes) {
            if (bytes.length > MAX_CELL_LENGTH)
                throw new UnsupportedContract(
                        "A grain value exceeds the runtime cell budget; no complete pass can be established");
            return HexFormat.of().formatHex(bytes);
        }
        if (value instanceof Number
                || value instanceof Boolean
                || value instanceof UUID
                || value instanceof java.util.Date
                || value instanceof java.time.temporal.TemporalAccessor) return value;
        throw new UnsupportedContract(
                "A grain column uses an unsupported JDBC value type; no complete pass can be established");
    }

    private Instant instant(ResultSet rs, ResultSetMetaData metadata, int column, ZoneId zone)
            throws SQLException {
        String type = metadata.getColumnTypeName(column).toLowerCase(Locale.ROOT);
        if (type.equals("timestamptz")) {
            OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }
        if (type.equals("timestamp")) {
            LocalDateTime value = rs.getObject(column, LocalDateTime.class);
            if (value == null) return null;
            var offsets = zone.getRules().getValidOffsets(value);
            if (offsets.size() != 1)
                throw new UnsupportedContract(
                        "Ambiguous or invalid local event timestamps prevent an availability-order conclusion");
            return value.toInstant(offsets.getFirst());
        }
        throw new UnsupportedContract(
                "Availability ordering supports PostgreSQL timestamp and timestamptz columns only");
    }

    private static boolean validateUrl(String value) {
        if (!value.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
        URI uri = URI.create(value.substring(5));
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPath() == null
                || uri.getPath().length() < 2
                || uri.getPort() == 0
                || uri.getPort() > 65535) throw new IllegalArgumentException();
        boolean local =
                Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                        .contains(uri.getHost().toLowerCase(Locale.ROOT));
        Set<String> options = Set.of("sslmode", "sslrootcert", "sslcert", "sslkey");
        if (uri.getRawQuery() != null)
            for (String entry : uri.getRawQuery().split("&")) {
                String[] pair = entry.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String option =
                        pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if (!options.contains(key) || option.isBlank())
                    throw new IllegalArgumentException();
                if (key.equals("sslmode")
                        && !(option.equals("verify-full") || (local && option.equals("disable"))))
                    throw new IllegalArgumentException();
            }
        return local;
    }

    private static final class UnsupportedContract extends RuntimeException {
        UnsupportedContract(String message) {
            super(message);
        }
    }
}
