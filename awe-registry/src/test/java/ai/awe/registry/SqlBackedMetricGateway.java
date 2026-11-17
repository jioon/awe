package ai.awe.registry;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A trusted-reference BI protocol server: every successful value is computed by PostgreSQL using a
 * server-registered query, and readiness is read from an actual source status relation. This is a
 * protocol fixture, not a claim that an enterprise BI product has been connected.
 */
final class SqlBackedMetricGateway implements AutoCloseable {
    static final String TOKEN = "metric-reference-gateway-token";
    static final String READINESS = "public.awe_metric_readiness";
    private final JsonDocuments json = new JsonDocuments();
    private final HttpServer server;

    private record Registered(
            String sql, String referenceSql, String version, String hash, JsonNode metric) {}

    private record Execution(
            Connection connection,
            Registered implementation,
            ObjectNode request,
            Instant from,
            Instant to,
            String snapshotRef,
            String runRef) {}

    private final Map<String, Registered> queries = new ConcurrentHashMap<>();
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    private final AtomicInteger actualQueries = new AtomicInteger();
    private final AtomicInteger referenceQueries = new AtomicInteger();
    private volatile Consumer<ObjectNode> responseMutation = ignored -> {};
    private volatile Consumer<ObjectNode> verificationMutation = ignored -> {};
    private volatile int responseDelayMillis;
    private volatile JsonNode lastRequest;
    private volatile JsonNode lastVerificationRequest;

    SqlBackedMetricGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/query", this::handle);
            server.createContext("/verify", this::verify);
            server.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start the local metric protocol fixture", ex);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void register(String capability, String trustedQuery) {
        register(capability, trustedQuery, trustedQuery);
    }

    void register(String capability, String trustedQuery, String independentReferenceQuery) {
        queries.put(
                capability,
                new Registered(
                        trustedQuery,
                        independentReferenceQuery,
                        "reference-v1",
                        implementationHash(trustedQuery),
                        null));
    }

    void bindMetric(String capability, JsonNode publishedMetric) {
        queries.compute(
                capability,
                (key, registered) -> {
                    if (registered == null)
                        throw new IllegalArgumentException(
                                "Register the reference implementation first");
                    return new Registered(
                            registered.sql(),
                            registered.referenceSql(),
                            registered.version(),
                            registered.hash(),
                            json.parse(
                                    json.write(
                                            Map.of(
                                                    "releaseId",
                                                            publishedMetric.path("id").asText(),
                                                    "contentHash",
                                                            publishedMetric
                                                                    .path("content_hash")
                                                                    .asText(),
                                                    "entityId",
                                                            publishedMetric
                                                                    .path("entity_id")
                                                                    .asText(),
                                                    "modelVersion",
                                                            publishedMetric
                                                                    .path("version")
                                                                    .asText()))));
                });
    }

    static String implementationHash(String query) {
        return sha256(query);
    }

    void mutateResponse(Consumer<ObjectNode> mutation) {
        responseMutation = mutation;
    }

    void mutateVerification(Consumer<ObjectNode> mutation) {
        verificationMutation = mutation;
    }

    void expireSnapshots() {
        for (Execution execution : executions.values()) closeConnection(execution.connection());
    }

    void delay(int millis) {
        responseDelayMillis = millis;
    }

    int actualQueries() {
        return actualQueries.get();
    }

    int referenceQueries() {
        return referenceQueries.get();
    }

    void clearExecutions() {
        for (Execution execution : executions.values()) closeConnection(execution.connection());
        executions.clear();
    }

    JsonNode lastRequest() {
        return lastRequest;
    }

    JsonNode lastVerificationRequest() {
        return lastVerificationRequest;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/query".equals(exchange.getRequestURI().getPath())) {
                reply(exchange, 404, Map.of("error", "NOT_FOUND"));
                return;
            }
            if (!("Bearer " + TOKEN)
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 403, Map.of("error", "UNAUTHORIZED_SOURCE"));
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
            if (bytes.length > 65_536) {
                reply(exchange, 413, Map.of("error", "REQUEST_BUDGET"));
                return;
            }
            ObjectNode request = json.object(new String(bytes, StandardCharsets.UTF_8));
            lastRequest = request.deepCopy();
            String capability = request.path("capability").path("ref").asText();
            Registered registered = queries.get(capability);
            if (registered == null) {
                reply(exchange, 404, Map.of("error", "CAPABILITY_NOT_REGISTERED"));
                return;
            }
            if (registered.metric() == null
                    || !metricMatches(registered.metric(), request.path("metric"))
                    || !registered
                            .version()
                            .equals(
                                    request.path("capability")
                                            .path("implementationVersion")
                                            .asText())
                    || !registered
                            .hash()
                            .equals(
                                    request.path("capability")
                                            .path("implementationHash")
                                            .asText())) {
                reply(exchange, 409, Map.of("error", "BOUND_IMPLEMENTATION_MISMATCH"));
                return;
            }
            Connection connection = null;
            try {
                connection =
                        DriverManager.getConnection(
                                Objects.requireNonNull(System.getenv("AWE_DB_URL")),
                                Objects.requireNonNull(System.getenv("AWE_DB_USER")),
                                Objects.requireNonNull(System.getenv("AWE_DB_PASSWORD")));
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                ObjectNode response = execute(connection, request, capability, registered);
                responseMutation.accept(response);
                if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis);
                reply(exchange, 200, response);
            } catch (SQLException | RuntimeException ex) {
                reply(exchange, 503, Map.of("error", "REFERENCE_SOURCE_UNAVAILABLE"));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                reply(exchange, 503, Map.of("error", "REFERENCE_SOURCE_INTERRUPTED"));
            } finally {
                if (!executions.containsKey(request.path("operationId").asText()))
                    closeConnection(connection);
            }
        }
    }

    private ObjectNode execute(
            Connection connection, ObjectNode request, String capability, Registered registered)
            throws SQLException {
        Instant from = Instant.parse(request.path("scope").path("from").asText());
        Instant to = Instant.parse(request.path("scope").path("to").asText());
        String readiness = "UNKNOWN", completeFrom = "", completeThrough = "";
        Instant observedAt = Instant.now();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT state,complete_from,complete_through,updated_at FROM "
                                + READINESS
                                + " WHERE capability_ref=?")) {
            statement.setString(1, capability);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    readiness = rs.getString(1);
                    completeFrom = instant(rs, 2);
                    completeThrough = instant(rs, 3);
                    observedAt = rs.getObject(4, OffsetDateTime.class).toInstant();
                }
            }
        }
        String snapshot = snapshot(connection);
        String run = "reference-query-run:" + UUID.randomUUID();
        boolean completeRequired =
                "PERIOD_COMPLETE".equals(request.path("scope").path("view").asText());
        var rows = new ArrayList<Map<String, Object>>();
        String resultState;
        if (completeRequired && !"READY".equals(readiness)) {
            resultState = "NOT_READY";
            run = "reference-readiness-observation:" + UUID.randomUUID();
        } else {
            rows.addAll(rows(connection, registered.sql(), from, to));
            actualQueries.incrementAndGet();
            resultState = rows.isEmpty() ? "NO_RECORD" : "PRESENT";
            if (executions.size() >= 32)
                throw new SQLException("Reference fixture snapshot budget exceeded");
            Execution previous =
                    executions.put(
                            request.path("operationId").asText(),
                            new Execution(
                                    connection,
                                    registered,
                                    request.deepCopy(),
                                    from,
                                    to,
                                    snapshot,
                                    run));
            if (previous != null) closeConnection(previous.connection());
        }
        var response =
                json.object(
                        json.write(
                                Map.of(
                                        "protocolVersion", 1,
                                        "operationId", request.path("operationId"),
                                        "requestHash", json.sha256(request),
                                        "metric",
                                                Map.of(
                                                        "releaseId",
                                                        registered.metric().path("releaseId"),
                                                        "contentHash",
                                                        registered.metric().path("contentHash")),
                                        "planHash", request.path("planHash"),
                                        "capability",
                                                Map.of(
                                                        "ref",
                                                        capability,
                                                        "implementationVersion",
                                                        registered.version(),
                                                        "implementationHash",
                                                        registered.hash()),
                                        "scope", request.path("scope"),
                                        "readiness",
                                                Map.of(
                                                        "state",
                                                        readiness,
                                                        "observedAt",
                                                        observedAt.toString(),
                                                        "completeFrom",
                                                        completeFrom,
                                                        "completeThrough",
                                                        completeThrough,
                                                        "snapshotRef",
                                                        snapshot,
                                                        "runRef",
                                                        run),
                                        "result", Map.of("state", resultState, "rows", rows))));
        if (request.has("sourceProjectionRef"))
            ((ObjectNode) response.path("readiness"))
                    .set("sourceProjectionRef", request.path("sourceProjectionRef"));
        return response;
    }

    private static String instant(ResultSet rs, int index) throws SQLException {
        OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
        return value == null ? "" : value.toInstant().toString();
    }

    private void verify(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !("Bearer " + TOKEN)
                            .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 403, Map.of("error", "UNAUTHORIZED_CHECKER"));
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(1_048_577);
            if (bytes.length > 1_048_576) {
                reply(exchange, 413, Map.of("error", "CHECK_BUDGET"));
                return;
            }
            ObjectNode request = json.object(new String(bytes, StandardCharsets.UTF_8));
            String operation = request.path("operationId").asText();
            lastVerificationRequest = request.deepCopy();
            Execution executed = executions.get(operation);
            ObjectNode unhashed = request.deepCopy();
            unhashed.remove("requestHash");
            if (executed == null
                    || !json.sha256(unhashed).equals(request.path("requestHash").asText())
                    || !metricMatches(executed.implementation().metric(), request.path("metric"))
                    || !executed.request().path("planHash").equals(request.path("planHash"))
                    || !executed.from().equals(Instant.parse(request.path("from").asText()))
                    || !executed.to().equals(Instant.parse(request.path("to").asText()))
                    || !executed.request().path("scope").path("view").equals(request.path("view"))
                    || !executed.request()
                            .path("consumer")
                            .path("principal")
                            .equals(request.path("principal"))
                    || !executed.request()
                            .path("consumer")
                            .path("purpose")
                            .equals(request.path("purpose"))
                    || !executed.request()
                            .path("consumer")
                            .path("policyHash")
                            .equals(request.path("policyHash"))
                    || !json.sha256(executed.request().path("rowScope"))
                            .equals(request.path("scopeHash").asText())
                    || !request.path("resultHash").asText().matches("[a-f0-9]{64}")
                    || !containsText(request.path("executionEvidence"), executed.runRef())) {
                reply(exchange, 409, Map.of("error", "REFERENCE_SCOPE_MISMATCH"));
                return;
            }
            JsonNode snapshots = request.path("snapshots");
            boolean snapshotBound = snapshots.isObject() && snapshots.size() == 1;
            var values = snapshots.elements();
            while (values.hasNext())
                snapshotBound &= values.next().asText().equals(executed.snapshotRef());
            try {
                boolean sameSnapshot =
                        snapshotBound
                                && !executed.connection().isClosed()
                                && !executed.connection().getAutoCommit()
                                && snapshot(executed.connection()).equals(executed.snapshotRef());
                List<Map<String, Object>> reference =
                        sameSnapshot
                                ? rows(
                                        executed.connection(),
                                        executed.implementation().referenceSql(),
                                        executed.from(),
                                        executed.to())
                                : List.of();
                if (sameSnapshot) referenceQueries.incrementAndGet();
                ObjectNode response =
                        json.object(
                                json.write(
                                        Map.ofEntries(
                                                Map.entry("operationId", operation),
                                                Map.entry(
                                                        "requestHash", request.path("requestHash")),
                                                Map.entry(
                                                        "checkerRef",
                                                        "checker.reference.independent_sql"),
                                                Map.entry("observedAt", Instant.now().toString()),
                                                Map.entry("implementationVersion", "reference-v1"),
                                                Map.entry(
                                                        "implementationHash",
                                                        implementationHash(
                                                                executed.implementation()
                                                                        .referenceSql())),
                                                Map.entry("snapshots", snapshots),
                                                Map.entry("sameSnapshotVerified", sameSnapshot),
                                                Map.entry("complete", sameSnapshot),
                                                Map.entry("referenceRows", reference),
                                                Map.entry(
                                                        "referenceBasis",
                                                        "Independent SQL over the retained actual PostgreSQL repeatable-read transaction"),
                                                Map.entry(
                                                        "runRef", executed.runRef() + ":reference"),
                                                Map.entry(
                                                        "attemptRef",
                                                        UUID.randomUUID().toString()))));
                verificationMutation.accept(response);
                reply(exchange, 200, response);
            } catch (SQLException ex) {
                reply(exchange, 503, Map.of("error", "REFERENCE_SNAPSHOT_UNAVAILABLE"));
            } finally {
                closeConnection(executed.connection());
            }
        }
    }

    private static List<Map<String, Object>> rows(
            Connection connection, String query, Instant from, Instant to) throws SQLException {
        var rows = new ArrayList<Map<String, Object>>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(10);
            statement.setObject(1, from.atOffset(java.time.ZoneOffset.UTC));
            statement.setObject(2, to.atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        Object value =
                                switch (metadata.getColumnType(i)) {
                                    case Types.BIGINT, Types.INTEGER, Types.SMALLINT -> {
                                        long integer = rs.getLong(i);
                                        yield rs.wasNull() ? null : BigInteger.valueOf(integer);
                                    }
                                    case Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(i);
                                    case Types.BOOLEAN, Types.BIT -> {
                                        boolean bool = rs.getBoolean(i);
                                        yield rs.wasNull() ? null : bool;
                                    }
                                    case Types.DATE -> {
                                        var date = rs.getObject(i, java.time.LocalDate.class);
                                        yield date == null ? null : date.toString();
                                    }
                                    case Types.TIMESTAMP_WITH_TIMEZONE -> {
                                        var timestamp = rs.getObject(i, OffsetDateTime.class);
                                        yield timestamp == null
                                                ? null
                                                : timestamp.toInstant().toString();
                                    }
                                    default -> rs.getString(i);
                                };
                        row.put(metadata.getColumnLabel(i), value);
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static String snapshot(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT pg_catalog.txid_current_snapshot()::text")) {
            if (!rs.next()) throw new SQLException("Reference source did not expose a snapshot");
            return "postgresql:reference-metric:tx_snapshot_sha256:" + sha256(rs.getString(1));
        }
    }

    private static boolean containsText(JsonNode node, String expected) {
        if (node.isTextual()) return expected.equals(node.asText());
        for (JsonNode child : node) if (containsText(child, expected)) return true;
        return false;
    }

    private static boolean metricMatches(JsonNode registered, JsonNode provided) {
        if (!registered.path("releaseId").equals(provided.path("releaseId"))
                || !registered.path("contentHash").equals(provided.path("contentHash")))
            return false;
        for (String field : List.of("entityId", "modelVersion"))
            if (provided.has(field) && !registered.path(field).equals(provided.path(field)))
                return false;
        return true;
    }

    private static void closeConnection(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The fixture only closes its own read-only snapshot transaction.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Already closed snapshots remain unavailable, never silently recreated.
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        clearExecutions();
        server.stop(0);
    }
}
