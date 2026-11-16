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
    private static final String STATE = "public.awe_production_reference_state_v2",
            DEPLOYMENTS = "public.awe_production_reference_deployment_v2",
            TARGET_STATE = "public.awe_production_reference_target_state";

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
    private final Map<String, String> registeredTargets = new ConcurrentHashMap<>();
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
                        + "(environment TEXT NOT NULL,pipeline_id TEXT NOT NULL,revision BIGINT NOT NULL,pipeline_release_id TEXT NOT NULL,pipeline_hash TEXT NOT NULL,artifact_hash TEXT NOT NULL,PRIMARY KEY(environment,pipeline_id))");
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + TARGET_STATE
                        + "(environment TEXT NOT NULL,target_key TEXT NOT NULL,revision BIGINT NOT NULL,consumption_id UUID NOT NULL,intent_id NUMERIC NOT NULL,PRIMARY KEY(environment,target_key))");
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + DEPLOYMENTS
                        + "(consumption_id UUID PRIMARY KEY,deployment_ref TEXT UNIQUE NOT NULL,authorization_hash TEXT NOT NULL,manifest JSONB NOT NULL)");
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
        if (document.path("pipelineContract").path("targets").size() == 1) {
            JsonNode target = document.path("pipelineContract").path("targets").get(0);
            registeredTargets.put(
                    releaseSpec(target.path("dataset"))
                            .path("datasetContract")
                            .path("targetBindingRef")
                            .asText(),
                    targetTable);
        }
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
            if (request.path("protocolVersion").asInt() != 2)
                throw new IllegalArgumentException("Production protocol v2 is required");
            Artifact artifact = artifacts.get(request.path("artifactId").asText());
            if (artifact == null) {
                reply(exchange, 404, json.object("{}"));
                return;
            }
            ObjectNode response = json.object("{}");
            response.put("protocolVersion", 2);
            response.set("requestId", request.get("requestId"));
            response.put("bindingRevision", "production-reference-v2");
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
            response.set(
                    "baseline",
                    baseline(
                            request.path("environment").asText(),
                            request.path("pipeline").path("entityId").asText()));
            response.set(
                    "targetBaselines",
                    currentTargets(
                            request.path("environment").asText(), request.path("physicalTargets")));
            response.set("suite", request.get("suite"));
            if (request.path("phase").asText().equals("VERIFY")) {
                var executions = new ArrayList<Execution>();
                for (int i = 0;
                        i < artifact.document().path("pipelineContract").path("targets").size();
                        i++) executions.add(execute(artifact, i));
                Execution execution =
                        new Execution(
                                executions.stream().flatMap(e -> e.rows().stream()).toList(),
                                executions.stream().mapToLong(Execution::mismatches).sum(),
                                executions.stream().mapToLong(Execution::grainViolations).sum(),
                                executions.getFirst().inputs());
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
                proof.put("targetBaselinesHash", json.sha256(response.path("targetBaselines")));
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
                    baseline(
                                    result.path("environment").asText(),
                                    result.path("pipelineEntityId").asText())
                            .path("revision")
                            .asText());
            result.remove("pipelineEntityId");
            result.set(
                    "currentTargets",
                    currentTargets(
                            result.path("environment").asText(),
                            request.path("authorization").path("physicalTargets")));
            mutation.accept(result);
            reply(exchange, 200, result);
        } catch (Throwable ex) {
            reply(exchange, 500, json.object("{}"));
        }
    }

    String deploy(JsonNode authorization) {
        if (authorization.path("protocolVersion").asInt() != 2)
            throw new IllegalStateException("Platform requires production authorization v2");
        UUID consumption = UUID.fromString(authorization.path("consumptionId").asText());
        String pipelineId = authorization.path("pipeline").path("entityId").asText();
        String environment = authorization.path("environment").asText();
        Artifact artifact = artifacts.get(authorization.path("artifactId").asText());
        if (artifact == null
                || !sha(artifact.content()).equals(authorization.path("artifactHash").asText()))
            throw new IllegalStateException("Platform rejects wrong uploaded artifact bytes");
        List<String> targetKeys = targetKeys(authorization.path("physicalTargets"));
        JsonNode expected = authorization.path("targetBaselines");
        JsonNode intents = authorization.path("targetWriteIntents");
        if (!expected.isArray()
                || expected.size() != targetKeys.size()
                || !intents.isArray()
                || intents.size() != targetKeys.size())
            throw new IllegalStateException("Platform requires complete physical target vectors");
        return transactions.execute(
                tx -> {
                    // 先锁住整个物理目标集合；不同 Pipeline 写同一目标也必须共享同一把锁。
                    for (String target : targetKeys)
                        db.queryForList(
                                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                                "reference-target:" + environment + ":" + physicalRelation(target));
                    db.queryForList(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                            "reference-platform:" + environment + ":" + pipelineId);
                    var replay =
                            db.queryForList(
                                    "SELECT deployment_ref,authorization_hash FROM "
                                            + DEPLOYMENTS
                                            + " WHERE consumption_id=?",
                                    consumption);
                    if (!replay.isEmpty()) {
                        if (!json.sha256(authorization)
                                .equals(replay.getFirst().get("authorization_hash")))
                            throw new IllegalStateException(
                                    "An existing consumption cannot authorize a different deployment payload");
                        return (String) replay.getFirst().get("deployment_ref");
                    }
                    JsonNode current =
                            currentTargets(environment, authorization.path("physicalTargets"));
                    if (!expected.equals(current))
                        throw new IllegalStateException(
                                "Platform physical target CAS rejected a changed baseline");
                    for (int i = 0; i < targetKeys.size(); i++) {
                        JsonNode intent = intents.get(i);
                        if (!targetKeys.get(i).equals(intent.path("targetKey").asText())
                                || !intent.path("intentId").asText().matches("[1-9][0-9]*")
                                || new java.math.BigInteger(intent.path("intentId").asText())
                                                .compareTo(
                                                        new java.math.BigInteger(
                                                                current.get(i)
                                                                        .path("intentId")
                                                                        .asText()))
                                        <= 0)
                            throw new IllegalStateException(
                                    "Platform rejects superseded physical target write intent");
                    }
                    JsonNode previous = baseline(environment, pipelineId);
                    if (!previous.path("revision")
                            .asText()
                            .equals(authorization.path("expectedBaselineRevision").asText()))
                        throw new IllegalStateException(
                                "Platform deployment CAS rejected a changed baseline");
                    // 全目标 CAS 成功之后才开始修改表；数据、目标所有者和部署证明在同一事务提交。
                    var executions = new HashMap<String, Execution>();
                    for (int i = 0;
                            i < artifact.document().path("pipelineContract").path("targets").size();
                            i++) {
                        JsonNode target =
                                artifact.document().path("pipelineContract").path("targets").get(i);
                        String binding =
                                releaseSpec(target.path("dataset"))
                                        .path("datasetContract")
                                        .path("targetBindingRef")
                                        .asText();
                        String relation = registeredTargets.get(binding);
                        if (relation == null
                                || executions.put(relation, execute(artifact, i)) != null)
                            throw new IllegalStateException(
                                    "Platform artifact target binding is missing or ambiguous");
                    }
                    if (!executions
                            .keySet()
                            .equals(
                                    targetKeys.stream()
                                            .map(this::physicalRelation)
                                            .collect(java.util.stream.Collectors.toSet())))
                        throw new IllegalStateException(
                                "Authorization targets differ from uploaded artifact targets");
                    var targetRevisions = json.parse("[]");
                    for (int i = 0; i < targetKeys.size(); i++) {
                        String target = targetKeys.get(i);
                        if (!target.startsWith("postgres:"))
                            throw new IllegalStateException(
                                    "Reference platform only executes PostgreSQL targets");
                        String table =
                                PostgresWarehouse.quoteRelation(
                                        warehouse.relation(target.substring(9)));
                        db.execute(
                                "CREATE TABLE IF NOT EXISTS "
                                        + table
                                        + "(user_id TEXT PRIMARY KEY,score BIGINT NOT NULL)");
                        db.execute("TRUNCATE TABLE " + table);
                        for (var row : executions.get(physicalRelation(target)).rows())
                            db.update(
                                    "INSERT INTO " + table + " VALUES(?,?)",
                                    row.get("user_id"),
                                    new java.math.BigDecimal(row.get("score").toString()));
                        String next =
                                new java.math.BigInteger(current.get(i).path("revision").asText())
                                        .add(java.math.BigInteger.ONE)
                                        .toString();
                        String intent = intents.get(i).path("intentId").asText();
                        db.update(
                                "INSERT INTO "
                                        + TARGET_STATE
                                        + "(environment,target_key,revision,consumption_id,intent_id) VALUES(?,?,?,?,?) ON CONFLICT(environment,target_key) DO UPDATE SET revision=excluded.revision,consumption_id=excluded.consumption_id,intent_id=excluded.intent_id",
                                environment,
                                target,
                                Long.parseLong(next),
                                consumption,
                                new java.math.BigDecimal(intent));
                        ((com.fasterxml.jackson.databind.node.ArrayNode) targetRevisions)
                                .add(
                                        json.parse(
                                                json.write(
                                                        Map.of(
                                                                "targetKey",
                                                                target,
                                                                "previousRevision",
                                                                current.get(i)
                                                                        .path("revision")
                                                                        .asText(),
                                                                "revision",
                                                                next,
                                                                "consumptionId",
                                                                consumption.toString(),
                                                                "intentId",
                                                                intent))));
                    }
                    long revision = Long.parseLong(previous.path("revision").asText()) + 1;
                    db.update(
                            "INSERT INTO "
                                    + STATE
                                    + "(environment,pipeline_id,revision,pipeline_release_id,pipeline_hash,artifact_hash) VALUES(?,?,?,?,?,?) ON CONFLICT(environment,pipeline_id) DO UPDATE SET revision=excluded.revision,pipeline_release_id=excluded.pipeline_release_id,pipeline_hash=excluded.pipeline_hash,artifact_hash=excluded.artifact_hash",
                            environment,
                            pipelineId,
                            revision,
                            authorization.path("pipeline").path("releaseId").asText(),
                            authorization.path("pipeline").path("contentHash").asText(),
                            authorization.path("artifactHash").asText());
                    String deployment = "deployment-" + UUID.randomUUID();
                    var outputs = new ArrayList<Map<String, Object>>();
                    for (JsonNode target :
                            artifact.document().path("pipelineContract").path("targets")) {
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
                    var manifest = json.object("{}");
                    manifest.put("protocolVersion", 2);
                    manifest.put("bindingRevision", "production-reference-v2");
                    manifest.put("consumptionId", consumption.toString());
                    manifest.put("deploymentRef", deployment);
                    manifest.put("state", "DEPLOYED");
                    for (String field :
                            List.of("receiptId", "receiptHash", "artifactHash", "environment"))
                        manifest.set(field, authorization.get(field));
                    manifest.set(
                            "pipelineReleaseId", authorization.path("pipeline").get("releaseId"));
                    manifest.set(
                            "pipelineContentHash",
                            authorization.path("pipeline").get("contentHash"));
                    manifest.put("pipelineEntityId", pipelineId);
                    manifest.put("previousRevision", previous.path("revision").asText());
                    manifest.put("revision", Long.toString(revision));
                    manifest.put("runId", "deploy-run-" + UUID.randomUUID());
                    manifest.put("attemptId", "1");
                    manifest.put("completedAt", Instant.now().toString());
                    manifest.set("outputs", json.parse(json.write(outputs)));
                    manifest.set("targetRevisions", targetRevisions);
                    db.update(
                            "INSERT INTO "
                                    + DEPLOYMENTS
                                    + "(consumption_id,deployment_ref,authorization_hash,manifest) VALUES(?,?,?,?::jsonb)",
                            consumption,
                            deployment,
                            json.sha256(authorization),
                            json.write(manifest));
                    return deployment;
                });
    }

    /** 模拟现有平台绕开 AWE 的真实替换；只有动态平台证明才能发现这个同 Schema 变化。 */
    void replaceOutsideAdmission(JsonNode authorization) {
        String environment = authorization.path("environment").asText();
        transactions.executeWithoutResult(
                tx -> {
                    for (String target : targetKeys(authorization.path("physicalTargets"))) {
                        db.queryForList(
                                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                                "reference-target:" + environment + ":" + physicalRelation(target));
                        String table = PostgresWarehouse.quoteRelation(physicalRelation(target));
                        db.execute(
                                "DELETE FROM "
                                        + table
                                        + " WHERE user_id NOT IN (SELECT user_id FROM "
                                        + MetricQueryTestSupport.USERS
                                        + " WHERE segment='core')");
                        db.update(
                                "UPDATE "
                                        + TARGET_STATE
                                        + " SET revision=revision+1,consumption_id=?,intent_id=intent_id+1 WHERE environment=? AND target_key=?",
                                UUID.randomUUID(),
                                environment,
                                target);
                    }
                });
    }

    private String physicalRelation(String targetKey) {
        if (!targetKey.startsWith("postgres:"))
            throw new IllegalStateException("Reference platform only executes PostgreSQL targets");
        return warehouse.relation(targetKey.substring(9));
    }

    private List<String> targetKeys(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty())
            throw new IllegalStateException("Platform requires explicit physical targets");
        var keys = new ArrayList<String>();
        for (JsonNode node : nodes) {
            if (!node.isTextual() || node.asText().isBlank())
                throw new IllegalStateException("Invalid physical target key");
            keys.add(node.asText());
        }
        if (!keys.equals(keys.stream().distinct().sorted().toList()))
            throw new IllegalStateException("Physical target vector must be unique and sorted");
        return keys;
    }

    private JsonNode currentTargets(String environment, JsonNode requested) {
        var values = json.parse("[]");
        for (String target : targetKeys(requested)) {
            var rows =
                    db.queryForList(
                            "SELECT revision::text,consumption_id::text,intent_id::text FROM "
                                    + TARGET_STATE
                                    + " WHERE environment=? AND target_key=?",
                            environment,
                            target);
            Map<String, Object> value =
                    rows.isEmpty()
                            ? Map.of(
                                    "targetKey",
                                    target,
                                    "revision",
                                    "0",
                                    "consumptionId",
                                    "",
                                    "intentId",
                                    "0")
                            : Map.of(
                                    "targetKey",
                                    target,
                                    "revision",
                                    rows.getFirst().get("revision"),
                                    "consumptionId",
                                    rows.getFirst().get("consumption_id"),
                                    "intentId",
                                    rows.getFirst().get("intent_id"));
            ((com.fasterxml.jackson.databind.node.ArrayNode) values)
                    .add(json.parse(json.write(value)));
        }
        return values;
    }

    private Execution execute(Artifact artifact, int targetIndex) {
        var contract =
                typed.pipelineContract(
                        json.object(
                                json.write(
                                        Map.of(
                                                "schemaVersion",
                                                3,
                                                "pipelineContract",
                                                artifact.document().path("pipelineContract")))));
        var target = contract.targets().get(targetIndex);
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

    private JsonNode baseline(String environment, String pipelineId) {
        var rows =
                db.queryForList(
                        "SELECT revision,pipeline_release_id,pipeline_hash,artifact_hash FROM "
                                + STATE
                                + " WHERE environment=? AND pipeline_id=?",
                        environment,
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
