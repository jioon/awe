package ai.awe.registry.source.postgres;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 原生 PostgreSQL 来源共享边界：管理员配置、只读连接、目录和有界证据，不接受请求 SQL。 */
@Component
public final class PostgresWarehouse {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private final String url, user, password, status;
    private final Map<String, String> relations;
    private final int maxRows;
    private final boolean local;
    private final JsonDocuments json;

    public PostgresWarehouse(Environment env, JsonDocuments json) {
        this.json = json;
        url = env.getProperty("AWE_WAREHOUSE_JDBC_URL", "").trim();
        user = env.getProperty("AWE_WAREHOUSE_JDBC_USER", "").trim();
        password = env.getProperty("AWE_WAREHOUSE_JDBC_PASSWORD", "");
        String state = "CONFIGURED";
        int rows = 10000;
        boolean loopback = false;
        var mapping = new TreeMap<String, String>();
        try {
            String limit = env.getProperty("AWE_WAREHOUSE_MAX_ROWS", "10000");
            if (!limit.matches("[1-9][0-9]{0,5}")) throw new IllegalArgumentException();
            rows = Integer.parseInt(limit);
            if (rows > 100000) throw new IllegalArgumentException();
            String raw = env.getProperty("AWE_WAREHOUSE_ASSET_BINDINGS", "");
            if (!raw.isBlank()) {
                JsonNode parsed = json.object(raw);
                if (parsed.size() > 1000) throw new IllegalArgumentException();
                var fields = parsed.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (!field.getKey().matches("[a-z][a-z0-9_-]*:[^\\p{Cntrl}]{1,500}")
                            || !field.getValue().isTextual()) throw new IllegalArgumentException();
                    quoteRelation(field.getValue().asText());
                    if (field.getKey().startsWith("postgres:"))
                        mapping.put(field.getKey().substring(9), field.getValue().asText());
                }
            }
            if (!url.isBlank()) loopback = validateUrl(url);
            if (url.isBlank() || user.isBlank() || password.isEmpty() || mapping.isEmpty())
                state = "NOT_CONFIGURED";
        } catch (IllegalArgumentException | Failure ex) {
            state = "UNAVAILABLE";
        }
        status = state;
        maxRows = rows;
        local = loopback;
        relations = Map.copyOf(mapping);
    }

    public boolean configured() {
        return "CONFIGURED".equals(status);
    }

    public String status() {
        return status;
    }

    public int maxRows() {
        return maxRows;
    }

    public String relation(String externalRef) {
        if (!configured())
            throw new Failure(
                    status,
                    "Native PostgreSQL connection and administrator asset mapping are required");
        String relation = relations.get(externalRef);
        if (relation == null)
            throw new Failure(
                    "NOT_CONFIGURED",
                    "This PostgreSQL asset has no administrator-configured relation");
        return relation;
    }

    public Connection openReadOnly() throws SQLException {
        if (!configured())
            throw new Failure(
                    status,
                    "Native PostgreSQL connection and administrator asset mapping are required");
        var properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        properties.setProperty("readOnlyMode", "transaction");
        properties.setProperty("sslmode", local ? "disable" : "verify-full");
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            // 同一只读可重复读事务包含目录检查和实际读取，任何结果都不写来源。
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException ex) {
            connection.close();
            throw ex;
        }
    }

    public record Column(
            String name,
            String type,
            String nativeType,
            boolean nullable,
            boolean deterministic,
            String collation) {}

    public record Catalog(
            String relation, List<Column> columns, List<String> primaryKeys, String sourceVersion) {
        public Catalog {
            columns = List.copyOf(columns);
            primaryKeys = List.copyOf(primaryKeys);
        }
    }

    public Catalog inspect(Connection connection, String externalRef) throws SQLException {
        return inspect(
                connection,
                externalRef,
                System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos());
    }

    public Catalog inspect(Connection connection, String externalRef, long deadline)
            throws SQLException {
        String relation = relation(externalRef), quoted = quoteRelation(relation);
        // 显式锁住真实关系，防止目录核对后并发 DDL 改口径；ACCESS SHARE 在只读事务中合法。
        try (Statement lock = connection.createStatement()) {
            constrain(lock, deadline);
            lock.execute("LOCK TABLE " + quoted + " IN ACCESS SHARE MODE");
        }
        String kind;
        try (PreparedStatement stmt =
                connection.prepareStatement(
                        "SELECT relkind::text,relrowsecurity,relforcerowsecurity FROM pg_catalog.pg_class WHERE oid=pg_catalog.to_regclass(?)")) {
            constrain(stmt, deadline);
            stmt.setString(1, quoted);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next())
                    throw new Failure("NOT_FOUND", "Configured PostgreSQL relation was not found");
                kind = rs.getString(1);
                if (!"r".equals(kind))
                    throw new Failure(
                            "BLOCKED",
                            "Only flat native PostgreSQL tables are supported; partitioned roots, views and foreign tables are unsupported");
                if (rs.getBoolean(2) || rs.getBoolean(3))
                    throw new Failure(
                            "BLOCKED",
                            "Row-level-security sources require an explicit supported row-scope policy");
            }
        }
        // 首版不执行分区根和传统继承父表，避免并发 ATTACH 与父 PK 不约束子行的范围漏洞。
        try (PreparedStatement stmt =
                connection.prepareStatement(
                        "WITH RECURSIVE children(oid) AS (SELECT pg_catalog.to_regclass(?)::oid UNION SELECT i.inhrelid FROM pg_catalog.pg_inherits i JOIN children c ON i.inhparent=c.oid) SELECT 1 FROM children d JOIN pg_catalog.pg_class c ON c.oid=d.oid WHERE c.relkind NOT IN ('r','p') OR c.relrowsecurity OR c.relforcerowsecurity OR (c.relkind='r' AND EXISTS(SELECT 1 FROM pg_catalog.pg_inherits child WHERE child.inhparent=c.oid)) LIMIT 1")) {
            constrain(stmt, deadline);
            stmt.setString(1, quoted);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next())
                    throw new Failure(
                            "BLOCKED",
                            "Unsupported foreign, row-security or ordinary-inheritance relation in the configured tree");
            }
        }
        var columns = new ArrayList<Column>();
        try (PreparedStatement stmt =
                connection.prepareStatement(
                        "SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),t.typname,NOT a.attnotnull,COALESCE(c.collisdeterministic,true),COALESCE(pg_catalog.to_jsonb(c)::text,'') FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid LEFT JOIN pg_catalog.pg_collation c ON c.oid=a.attcollation WHERE a.attrelid=pg_catalog.to_regclass(?) AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attname")) {
            constrain(stmt, deadline);
            stmt.setString(1, quoted);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (columns.size() >= 1000)
                        throw new Failure(
                                "BLOCKED", "Native schema exceeds the metadata column budget");
                    String physical = rs.getString(2);
                    if (physical.startsWith("character("))
                        physical = "char" + physical.substring("character".length());
                    columns.add(
                            new Column(
                                    rs.getString(1),
                                    physical,
                                    rs.getString(3),
                                    rs.getBoolean(4),
                                    rs.getBoolean(5),
                                    rs.getString(6)));
                }
            }
        }
        if (columns.isEmpty())
            throw new Failure("BLOCKED", "Configured relation has no observable columns");
        var keys = new ArrayList<String>();
        try (PreparedStatement stmt =
                connection.prepareStatement(
                        "SELECT a.attname FROM pg_catalog.pg_index i CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum,ord) JOIN pg_catalog.pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum WHERE i.indrelid=pg_catalog.to_regclass(?) AND i.indisprimary AND i.indisvalid AND k.ord<=i.indnkeyatts ORDER BY k.ord")) {
            constrain(stmt, deadline);
            stmt.setString(1, quoted);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) keys.add(rs.getString(1));
            }
        }
        // 版本仅由真实模式、约束和连接投影决定；行变化、TRUNCATE 和统计更新不会改变模式版本。
        var structural =
                Map.of(
                        "projection",
                        projectionHash(externalRef),
                        "kind",
                        kind,
                        "columns",
                        columns,
                        "primaryKeys",
                        keys);
        String version = json.sha256(json.parse(json.write(structural)));
        return new Catalog(relation, columns, keys, version);
    }

    public JsonNode metadata(Catalog catalog, String externalRef) {
        var columns =
                catalog.columns().stream()
                        .map(
                                c ->
                                        Map.<String, Object>of(
                                                "name",
                                                c.name(),
                                                "type",
                                                c.type(),
                                                "nullable",
                                                c.nullable()))
                        .toList();
        var times =
                catalog.columns().stream()
                        .filter(
                                c ->
                                        Set.of("date", "timestamp", "timestamptz")
                                                .contains(c.nativeType()))
                        .map(Column::name)
                        .sorted()
                        .toList();
        return json.parse(
                json.canonical(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "externalRef",
                                                externalRef,
                                                "sourceVersion",
                                                catalog.sourceVersion(),
                                                "columns",
                                                columns,
                                                "grainKeys",
                                                catalog.primaryKeys().stream().sorted().toList(),
                                                "timeFields",
                                                times)))));
    }

    public String projectionHash(String externalRef) {
        return json.sha256(
                json.parse(
                        json.write(
                                Map.of(
                                        "jdbcURL",
                                        url,
                                        "jdbcUser",
                                        user,
                                        "relation",
                                        relation(externalRef),
                                        "sourceSystem",
                                        "postgres",
                                        "externalRef",
                                        externalRef))));
    }

    public String snapshotRef(Connection connection, String externalRef) throws SQLException {
        return snapshotRef(
                connection,
                externalRef,
                System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos());
    }

    public String snapshotRef(Connection connection, String externalRef, long deadline)
            throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            constrain(stmt, deadline);
            try (ResultSet rs =
                    stmt.executeQuery("SELECT pg_catalog.txid_current_snapshot()::text")) {
                if (!rs.next())
                    throw new Failure(
                            "UNAVAILABLE", "PostgreSQL did not expose a transaction snapshot");
                String snapshot = rs.getString(1);
                if (snapshot == null || snapshot.isBlank() || snapshot.length() > 1048576)
                    throw new Failure(
                            "INCONCLUSIVE", "Transaction snapshot exceeds the evidence budget");
                return "postgresql:binding:"
                        + projectionHash(externalRef)
                        + ":tx_snapshot_sha256:"
                        + json.sha256(json.parse(json.write(snapshot)));
            }
        }
    }

    /** 每条 SQL 只获得整个操作剩余时间，不让多个独立 15 秒查询叠成无界请求。 */
    public static void constrain(Statement statement, long deadline) throws SQLException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
            throw new Failure("INCONCLUSIVE", "Native PostgreSQL operation deadline exceeded");
        statement.setQueryTimeout(
                (int) Math.min(15, Math.max(1, (remaining + 999999999L) / 1000000000L)));
    }

    public static String quoteIdentifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches())
            throw new Failure("BLOCKED", "Unsupported PostgreSQL identifier");
        return '"' + name + '"';
    }

    public static String quoteRelation(String relation) {
        if (relation == null) throw new IllegalArgumentException();
        String[] parts = relation.split("\\.", -1);
        if (parts.length != 2)
            throw new IllegalArgumentException("Explicit schema.table relation required");
        return quoteIdentifier(parts[0]) + "." + quoteIdentifier(parts[1]);
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
        if (uri.getRawQuery() != null)
            for (String part : uri.getRawQuery().split("&")) {
                String[] item = part.split("=", 2);
                String key = URLDecoder.decode(item[0], StandardCharsets.UTF_8),
                        option =
                                item.length == 2
                                        ? URLDecoder.decode(item[1], StandardCharsets.UTF_8)
                                        : "";
                if (!Set.of("sslmode", "sslrootcert", "sslcert", "sslkey").contains(key)
                        || option.isBlank()) throw new IllegalArgumentException();
                if (key.equals("sslmode")
                        && !(option.equals("verify-full") || local && option.equals("disable")))
                    throw new IllegalArgumentException();
            }
        return local;
    }

    public static final class Failure extends RuntimeException {
        private final String status;

        public Failure(String status, String message) {
            super(message);
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}
