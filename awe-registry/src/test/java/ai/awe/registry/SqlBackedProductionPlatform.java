package ai.awe.registry;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.model.*;
import ai.awe.registry.consumption.query.*;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reference engineering platform: uploaded typed artifacts execute real PG SQL; independent SQL
 * checks, atomic CAS deployment and actual output tables replace any fixed successful fixture.
 */
final class SqlBackedProductionPlatform implements AutoCloseable {
    static final String TOKEN = "production-platform-reference-token";
    static final String CHECKER = "checker.production.independent_sql.v1";
    static final String SUITE_HASH =
            new JsonDocuments()
                    .sha256(
                            new JsonDocuments()
                                    .parse(
                                            "{\"allUsers\":\"SELECT user_id,1::bigint AS score FROM public.awe_metric_users ORDER BY user_id\",\"coreUsers\":\"SELECT user_id,1::bigint AS score FROM public.awe_metric_users WHERE segment='core' ORDER BY user_id\"}"));
    private static final String STATE = "public.awe_production_reference_state",
            DEPLOYMENTS = "public.awe_production_reference_deployment";

    private record Artifact(JsonNode document, String content, String table, boolean coreOnly) {}

    private record Execution(
            List<Map<String, Object>> rows,
            long mismatches,
            long grainViolations,
            JsonNode inputs) {}

    private final JsonDocuments json = new JsonDocuments();
    private final TypedModels typed = new TypedModels();
    private final HttpServer server;
    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> regressions = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> snapshots = new ConcurrentHashMap<>();
    private JdbcTemplate db;
    private PostgresWarehouse warehouse;
    private TransactionTemplate transactions;
    private volatile Consumer<ObjectNode> mutation = ignored -> {};
    private volatile boolean wrongExecution;
    final AtomicInteger actualQueries = new AtomicInteger(),
            independentQueries = new AtomicInteger();

    SqlBackedProductionPlatform() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/admissions/observe", this::observe);
            server.createContext("/admissions/realizations", this::realization);
            server.start();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void attach(JdbcTemplate db, PostgresWarehouse warehouse, TransactionTemplate tx) {
        this.db = db;
        this.warehouse = warehouse;
        this.transactions = tx;
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + STATE
                        + "(pipeline_id TEXT PRIMARY KEY,revision BIGINT NOT NULL,pipeline_release_id TEXT NOT NULL,pipeline_hash TEXT NOT NULL,artifact_hash TEXT NOT NULL)");
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + DEPLOYMENTS
                        + "(consumption_id UUID PRIMARY KEY,deployment_ref TEXT UNIQUE NOT NULL,manifest JSONB NOT NULL)");
    }

    void reset() {
        mutation = ignored -> {};
        wrongExecution = false;
    }

    void mutate(Consumer<ObjectNode> change) {
        mutation = change;
    }

    void wrongExecution(boolean value) {
        wrongExecution = value;
    }

    void register(String artifactId, JsonNode document, String targetTable, boolean coreOnly) {
        artifacts.put(
                artifactId,
                new Artifact(document.deepCopy(), json.canonical(document), targetTable, coreOnly));
    }

    void corrupt(String artifactId) {
        var current = artifacts.get(artifactId);
        artifacts.put(
                artifactId,
                new Artifact(
                        current.document(),
                        " " + current.content(),
                        current.table(),
                        current.coreOnly()));
    }

    void advanceBaseline(String pipelineId) {
        db.update("UPDATE " + STATE + " SET revision=revision+1 WHERE pipeline_id=?", pipelineId);
    }

    private void observe(HttpExchange exchange) {
        try {
            ObjectNode request = request(exchange);
            if (request == null) return;
            Artifact artifact = artifacts.get(request.path("artifactId").asText());
            if (artifact == null) {
                reply(exchange, 404, json.object("{}"));
                return;
            }
            ObjectNode response = json.object("{}");
            response.put("protocolVersion", 1);
            response.set("requestId", request.get("requestId"));
            response.put("bindingRevision", "production-reference-v1");
            response.set("environment", request.get("environment"));
            response.set("pipelineReleaseId", request.path("pipeline").get("releaseId"));
            response.set("pipelineContentHash", request.path("pipeline").get("contentHash"));
            response.set(
                    "artifact",
                    json.parse(
                            json.write(
                                    Map.of(
                                            "artifactId",
                                            request.path("artifactId").asText(),
                                            "version",
                                            "uploaded-v1",
                                            "contentHash",
                                            sha(artifact.content()),
                                            "mediaType",
                                            "application/vnd.awe.pipeline-plan+json",
                                            "content",
                                            artifact.content()))));
            response.set("baseline", baseline(request.path("pipeline").path("entityId").asText()));
            response.set("suite", request.get("suite"));
            if (request.path("phase").asText().equals("VERIFY")) {
                Execution execution = execute(artifact);
                String artifactId = request.path("artifactId").asText();
                snapshots.put(artifactId, execution.inputs());
                var proof = json.object("{}");
                proof.put("state", "COMPLETE");
                proof.put("runId", "regression-" + UUID.randomUUID());
                proof.put("attemptId", "1");
                proof.put("observedAt", Instant.now().toString());
                proof.set("pipelineReleaseId", request.path("pipeline").get("releaseId"));
                proof.set("pipelineContentHash", request.path("pipeline").get("contentHash"));
                proof.set("artifactHash", request.get("expectedArtifactHash"));
                proof.set("environment", request.get("environment"));
                proof.put("baselineRevision", response.path("baseline").path("revision").asText());
                proof.set("impactHash", request.get("impactHash"));
                proof.put("inputRevisionsHash", json.sha256(execution.inputs()));
                var checks = json.parse("[]");
                for (JsonNode obligation : request.path("obligations")) {
                    String rule = obligation.path("ruleId").asText();
                    long violations =
                            rule.equals("check.pipeline.output.grain")
                                    ? execution.grainViolations()
                                    : 0;
                    long mismatches =
                            Set.of(
                                                    "check.pipeline.result.equivalence",
                                                    "check.pipeline.consumer.regression")
                                            .contains(rule)
                                    ? execution.mismatches()
                                    : 0;
                    ((com.fasterxml.jackson.databind.node.ArrayNode) checks)
                            .add(
                                    json.parse(
                                            json.write(
                                                    Map.of(
                                                            "obligationId",
                                                            obligation.path("id").asText(),
                                                            "scopeHash",
                                                            obligation.path("scopeHash").asText(),
                                                            "checkerRef",
                                                            CHECKER,
                                                            "checkedItems",
                                                            Math.max(1, execution.rows().size()),
                                                            "violationCount",
                                                            violations,
                                                            "mismatchCount",
                                                            mismatches,
                                                            "complete",
                                                            true))));
                }
                proof.set("checks", checks);
                regressions.put(artifactId, proof);
            }
            response.set("inputs", currentInputs(request.path("artifactId").asText(), artifact));
            response.set(
                    "regression",
                    regressions.getOrDefault(
                            request.path("artifactId").asText(),
                            json.object("{\"state\":\"PENDING\"}")));
            mutation.accept(response);
            reply(exchange, 200, response);
        } catch (Throwable ex) {
            reply(
                    exchange,
                    500,
                    json.object(
                            "{\"error\":\"reference platform could not execute its real database operation\"}"));
        }
    }

    private void realization(HttpExchange exchange) {
        try {
            ObjectNode request = request(exchange);
            if (request == null) return;
            var rows =
                    db.queryForList(
                            "SELECT manifest::text FROM "
                                    + DEPLOYMENTS
                                    + " WHERE consumption_id=? AND deployment_ref=?",
                            UUID.fromString(request.path("consumptionId").asText()),
                            request.path("deploymentRef").asText());
            if (rows.isEmpty()) {
                reply(exchange, 404, json.object("{}"));
                return;
            }
            var result = json.object((String) rows.getFirst().get("manifest"));
            result.set("requestId", request.get("requestId"));
            result.put(
                    "activeRevision",
                    baseline(result.path("pipelineEntityId").asText()).path("revision").asText());
            result.remove("pipelineEntityId");
            mutation.accept(result);
            reply(exchange, 200, result);
        } catch (Throwable ex) {
            reply(exchange, 500, json.object("{}"));
        }
    }

    String deploy(JsonNode authorization) {
        UUID consumption = UUID.fromString(authorization.path("consumptionId").asText());
        String pipelineId = authorization.path("pipeline").path("entityId").asText();
        Artifact artifact = artifacts.get(authorization.path("artifactId").asText());
        if (artifact == null
                || !sha(artifact.content()).equals(authorization.path("artifactHash").asText()))
            throw new IllegalStateException("Platform rejects wrong uploaded artifact bytes");
        return transactions.execute(
                tx -> {
                    db.queryForList(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                            "reference-platform:" + pipelineId);
                    var replay =
                            db.queryForList(
                                    "SELECT deployment_ref FROM "
                                            + DEPLOYMENTS
                                            + " WHERE consumption_id=?",
                                    consumption);
                    if (!replay.isEmpty()) return (String) replay.getFirst().get("deployment_ref");
                    JsonNode previous = baseline(pipelineId);
                    if (!previous.path("revision")
                            .asText()
                            .equals(authorization.path("expectedBaselineRevision").asText()))
                        throw new IllegalStateException(
                                "Platform deployment CAS rejected a changed baseline");
                    Execution execution = execute(artifact);
                    db.execute(
                            "CREATE TABLE IF NOT EXISTS "
                                    + artifact.table()
                                    + "(user_id TEXT PRIMARY KEY,score BIGINT NOT NULL)");
                    db.execute("TRUNCATE TABLE " + artifact.table());
                    for (var row : execution.rows())
                        db.update(
                                "INSERT INTO " + artifact.table() + " VALUES(?,?)",
                                row.get("user_id"),
                                new java.math.BigDecimal(row.get("score").toString()));
                    long revision = Long.parseLong(previous.path("revision").asText()) + 1;
                    db.update(
                            "INSERT INTO "
                                    + STATE
                                    + "(pipeline_id,revision,pipeline_release_id,pipeline_hash,artifact_hash) VALUES(?,?,?,?,?) ON CONFLICT(pipeline_id) DO UPDATE SET revision=excluded.revision,pipeline_release_id=excluded.pipeline_release_id,pipeline_hash=excluded.pipeline_hash,artifact_hash=excluded.artifact_hash",
                            pipelineId,
                            revision,
                            authorization.path("pipeline").path("releaseId").asText(),
                            authorization.path("pipeline").path("contentHash").asText(),
                            authorization.path("artifactHash").asText());
                    String deployment = "deployment-" + UUID.randomUUID();
                    JsonNode contract = artifact.document().path("pipelineContract");
                    var outputs = new ArrayList<Map<String, Object>>();
                    for (JsonNode target : contract.path("targets")) {
                        JsonNode release = pointer(artifact.document(), target.path("dataset"));
                        JsonNode spec = releaseSpec(target.path("dataset"));
                        outputs.add(
                                Map.of(
                                        "datasetReleaseId",
                                        release.path("releaseId").asText(),
                                        "datasetContentHash",
                                        release.path("contentHash").asText(),
                                        "targetBindingRef",
                                        spec.path("datasetContract")
                                                .path("targetBindingRef")
                                                .asText()));
                    }
                    var manifest =
                            json.parse(
                                    json.write(
                                            Map.ofEntries(
                                                    Map.entry("protocolVersion", 1),
                                                    Map.entry(
                                                            "bindingRevision",
                                                            "production-reference-v1"),
                                                    Map.entry(
                                                            "consumptionId",
                                                            consumption.toString()),
                                                    Map.entry("deploymentRef", deployment),
                                                    Map.entry("state", "DEPLOYED"),
                                                    Map.entry(
                                                            "receiptId",
                                                            authorization
                                                                    .path("receiptId")
                                                                    .asText()),
                                                    Map.entry(
                                                            "receiptHash",
                                                            authorization
                                                                    .path("receiptHash")
                                                                    .asText()),
                                                    Map.entry(
                                                            "pipelineReleaseId",
                                                            authorization
                                                                    .path("pipeline")
                                                                    .path("releaseId")
                                                                    .asText()),
                                                    Map.entry(
                                                            "pipelineContentHash",
                                                            authorization
                                                                    .path("pipeline")
                                                                    .path("contentHash")
                                                                    .asText()),
                                                    Map.entry("pipelineEntityId", pipelineId),
                                                    Map.entry(
                                                            "artifactHash",
                                                            authorization
                                                                    .path("artifactHash")
                                                                    .asText()),
                                                    Map.entry(
                                                            "environment",
                                                            authorization
                                                                    .path("environment")
                                                                    .asText()),
                                                    Map.entry(
                                                            "previousRevision",
                                                            previous.path("revision").asText()),
                                                    Map.entry("revision", Long.toString(revision)),
                                                    Map.entry(
                                                            "runId",
                                                            "deploy-run-" + UUID.randomUUID()),
                                                    Map.entry("attemptId", "1"),
                                                    Map.entry(
                                                            "completedAt",
                                                            Instant.now().toString()),
                                                    Map.entry("outputs", outputs))));
                    db.update(
                            "INSERT INTO "
                                    + DEPLOYMENTS
                                    + "(consumption_id,deployment_ref,manifest) VALUES(?,?,?::jsonb)",
                            consumption,
                            deployment,
                            json.write(manifest));
                    return deployment;
                });
    }

    private Execution execute(Artifact artifact) {
        var contract =
                typed.pipelineContract(
                        json.object(
                                json.write(
                                        Map.of(
                                                "schemaVersion",
                                                3,
                                                "pipelineContract",
                                                artifact.document().path("pipelineContract")))));
        var target = contract.targets().getFirst();
        var schema =
                (ObjectType)
                        typed.decode(
                                "ObjectType",
                                releaseSpec(json.parse(json.write(target.rowSchema()))));
        var resolved = new LinkedHashMap<String, BoundQuerySource>();
        var actualInputs = new ArrayList<JsonNode>();
        for (var source : contract.sources()) {
            JsonNode functionSpec = releaseSpec(json.parse(json.write(source.readFunction())));
            var function = (FunctionDefinition) typed.decode("FunctionDefinition", functionSpec);
            JsonNode datasetSpec = releaseSpec(json.parse(json.write(function.ownerType())));
            var dataset = (SemanticModel.Dataset) typed.decode("Dataset", datasetSpec);
            var datasetContract = typed.datasetContract(datasetSpec);
            JsonNode rowSpec = releaseSpec(json.parse(json.write(datasetContract.rowSchema())));
            var row = (ObjectType) typed.decode("ObjectType", rowSpec);
            JsonNode pointer =
                    pointer(artifact.document(), json.parse(json.write(function.ownerType())));
            resolved.put(
                    source.alias(),
                    new BoundQuerySource(
                            source.alias(),
                            dataset,
                            row,
                            datasetSpec.path("bindings").get(0),
                            UUID.fromString(pointer.path("releaseId").asText()),
                            pointer.path("contentHash").asText(),
                            Optional.of(datasetContract)));
        }
        var first = resolved.values().iterator().next();
        var wrapper =
                new MetricContract(
                        first.dataset().subjectType(),
                        first.alias() + ".user_id",
                        MetricContract.IdentityNullPolicy.REJECT,
                        "selected-users",
                        TemporalContract.TimeRole.OBSERVED_AT,
                        ZoneId.of("Asia/Shanghai"),
                        contract.sources(),
                        target.plan(),
                        schema.properties().stream()
                                .map(
                                        p ->
                                                new MetricContract.Output(
                                                        p.name(), p.type(), "value", !p.required()))
                                .toList(),
                        schema.identityKeys(),
                        MetricContract.EmptyPolicy.NO_RECORD,
                        MetricContract.ZeroDivisionPolicy.NULL,
                        Optional.empty());
        List<Map<String, Object>> actual;
        try (Connection connection = warehouse.openReadOnly()) {
            try {
                var catalogs = new TreeMap<String, PostgresWarehouse.Catalog>();
                for (var source : resolved.values()) {
                    String external = source.binding().path("externalRef").asText();
                    catalogs.put(source.alias(), warehouse.inspect(connection, external));
                    String revision = sourceRevision();
                    String snapshot = warehouse.snapshotRef(connection, external);
                    actualInputs.add(
                            json.parse(
                                    json.write(
                                            Map.of(
                                                    "datasetReleaseId",
                                                    source.releaseId().toString(),
                                                    "contentHash",
                                                    source.contentHash(),
                                                    "revision",
                                                    revision,
                                                    "snapshotRef",
                                                    snapshot))));
                }
                var compiled =
                        new RelationalSqlCompiler(
                                        wrapper,
                                        resolved,
                                        catalogs,
                                        Map.of(),
                                        Map.of(),
                                        Instant.parse(MetricQueryTestSupport.FROM),
                                        Instant.parse(MetricQueryTestSupport.TO))
                                .compile();
                String sql =
                        wrongExecution
                                ? "SELECT user_id,score+1 AS score FROM ("
                                        + compiled.sql()
                                        + ") bad"
                                : compiled.sql();
                actual = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < compiled.parameters().size(); i++)
                        statement.setObject(i + 1, compiled.parameters().get(i));
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next())
                            actual.add(
                                    Map.of(
                                            "user_id",
                                            rs.getString("user_id"),
                                            "score",
                                            new java.math.BigInteger(rs.getString("score"))));
                    }
                }
                actualQueries.incrementAndGet();
            } finally {
                connection.rollback();
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Reference platform could not compile/execute actual artifact", ex);
        }
        String golden =
                "SELECT user_id,1::bigint AS score FROM "
                        + MetricQueryTestSupport.USERS
                        + (artifact.coreOnly() ? " WHERE segment='core'" : "")
                        + " ORDER BY user_id";
        List<Map<String, Object>> expected = db.queryForList(golden);
        independentQueries.incrementAndGet();
        var a =
                actual.stream()
                        .map(r -> json.canonical(json.parse(json.write(r))))
                        .sorted()
                        .toList();
        var b =
                expected.stream()
                        .map(r -> json.canonical(json.parse(json.write(r))))
                        .sorted()
                        .toList();
        long mismatches = a.equals(b) ? 0 : Math.max(a.size(), b.size());
        long grain = actual.size() - actual.stream().map(r -> r.get("user_id")).distinct().count();
        actualInputs.sort(Comparator.comparing(n -> n.path("datasetReleaseId").asText()));
        return new Execution(actual, mismatches, grain, json.parse(json.write(actualInputs)));
    }

    private JsonNode currentInputs(String artifactId, Artifact artifact) {
        var accepted = snapshots.get(artifactId);
        if (accepted != null && accepted.get(0).path("revision").asText().equals(sourceRevision()))
            return accepted.deepCopy();
        var inputs = new ArrayList<JsonNode>();
        var source = artifact.document().path("pipelineContract").path("sources").get(0);
        JsonNode functionSpec = releaseSpec(source.path("readFunction"));
        var function = (FunctionDefinition) typed.decode("FunctionDefinition", functionSpec);
        JsonNode dataset =
                pointer(artifact.document(), json.parse(json.write(function.ownerType())));
        JsonNode spec = releaseSpec(json.parse(json.write(function.ownerType())));
        try (Connection connection = warehouse.openReadOnly()) {
            try {
                inputs.add(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "datasetReleaseId",
                                                dataset.path("releaseId").asText(),
                                                "contentHash",
                                                dataset.path("contentHash").asText(),
                                                "revision",
                                                sourceRevision(),
                                                "snapshotRef",
                                                warehouse.snapshotRef(
                                                        connection,
                                                        spec.path("bindings")
                                                                .get(0)
                                                                .path("externalRef")
                                                                .asText())))));
            } finally {
                connection.rollback();
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return json.parse(json.write(inputs));
    }

    private String sourceRevision() {
        return json.sha256(
                json.parse(
                        json.write(
                                db.queryForList(
                                        "SELECT user_id,city,segment FROM "
                                                + MetricQueryTestSupport.USERS
                                                + " ORDER BY user_id"))));
    }

    private JsonNode baseline(String pipelineId) {
        var rows =
                db.queryForList(
                        "SELECT revision,pipeline_release_id,pipeline_hash,artifact_hash FROM "
                                + STATE
                                + " WHERE pipeline_id=?",
                        pipelineId);
        if (rows.isEmpty())
            return json.parse(
                    json.write(
                            Map.of(
                                    "revision",
                                    "0",
                                    "state",
                                    "ABSENT",
                                    "pipelineReleaseId",
                                    "",
                                    "pipelineContentHash",
                                    "",
                                    "artifactHash",
                                    "")));
        var row = rows.getFirst();
        return json.parse(
                json.write(
                        Map.of(
                                "revision",
                                row.get("revision").toString(),
                                "state",
                                "PRESENT",
                                "pipelineReleaseId",
                                row.get("pipeline_release_id"),
                                "pipelineContentHash",
                                row.get("pipeline_hash"),
                                "artifactHash",
                                row.get("artifact_hash"))));
    }

    private JsonNode releaseSpec(JsonNode reference) {
        return json.parse(
                db.queryForObject(
                        "SELECT spec::text FROM awe_release WHERE entity_id=? AND version=?",
                        String.class,
                        reference.path("id").asText(),
                        reference.path("modelVersion").asText()));
    }

    private JsonNode pointer(JsonNode artifact, JsonNode ref) {
        for (JsonNode dependency : artifact.path("dependencies"))
            if (dependency.path("entityId").asText().equals(ref.path("id").asText())
                    && dependency
                            .path("modelVersion")
                            .asText()
                            .equals(ref.path("modelVersion").asText())) return dependency;
        throw new IllegalStateException("Artifact lacks its exact model dependency");
    }

    private ObjectNode request(HttpExchange exchange) throws Exception {
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            reply(exchange, 403, json.object("{}"));
            return null;
        }
        return json.object(
                new String(exchange.getRequestBody().readNBytes(262145), StandardCharsets.UTF_8));
    }

    private void reply(HttpExchange exchange, int status, JsonNode response) {
        try {
            byte[] bytes = json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception ignored) {
        } finally {
            exchange.close();
        }
    }

    private String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
