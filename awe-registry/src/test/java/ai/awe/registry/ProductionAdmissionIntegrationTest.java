package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.production.ProductionRealizations;
import ai.awe.registry.production.ProductionWriteIntents;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real HTTP governance plus real PostgreSQL artifact execution, independent comparisons, platform
 * CAS deployment and native-source realization. No successful platform result is canned.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=metric-query-author",
            "awe.auth.reviewer-token=metric-query-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class ProductionAdmissionIntegrationTest extends MetricQueryTestSupport {
    private static final String PLATFORM_TOKEN = "production-reference-caller";
    private static final String DOMAIN = "production";
    private static final List<String> CASES =
            List.of(
                    "happy",
                    "artifact",
                    "scope",
                    "ttl",
                    "expired",
                    "impact",
                    "concurrent",
                    "fence",
                    "takeover",
                    "shared_x",
                    "shared_y",
                    "late_a",
                    "late_b",
                    "callback_a",
                    "callback_b",
                    "outside",
                    "vector_missing",
                    "vector_wrong",
                    "vector_deploy",
                    "atomic",
                    "atomic_first",
                    "atomic_second",
                    "intent_a",
                    "intent_b",
                    "missing");
    private static SqlBackedProductionPlatform platform;
    @Autowired PostgresWarehouse warehouse;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductionRealizations realizations;
    @Autowired ProductionWriteIntents writeIntents;
    private SourceAssets source;
    private final Map<String, Assets> assets = new HashMap<>();

    private record Assets(
            String name,
            JsonNode dataset,
            JsonNode schema,
            JsonNode contract,
            JsonNode pipeline,
            String artifactId,
            String table) {}

    private record Approved(Assets assets, JsonNode proposal, JsonNode check, JsonNode receipt) {}

    private static synchronized SqlBackedProductionPlatform platform() {
        if (platform == null) platform = new SqlBackedProductionPlatform();
        return platform;
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
        properties.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () -> {
                    var map = new LinkedHashMap<String, String>();
                    map.put("postgres:metric.events", EVENTS);
                    map.put("postgres:metric.users", USERS);
                    for (String name : CASES)
                        map.put("postgres:production." + physical(name), table(name));
                    return new ai.awe.registry.support.JsonDocuments().write(map);
                });
        properties.add("AWE_PRODUCTION_PLATFORM_URL", () -> platform().baseUrl());
        properties.add("AWE_PRODUCTION_PLATFORM_TOKEN", () -> SqlBackedProductionPlatform.TOKEN);
        properties.add("AWE_PRODUCTION_ENVIRONMENT", () -> "test");
        properties.add(
                "AWE_PRODUCTION_CALLERS",
                () ->
                        new ai.awe.registry.support.JsonDocuments()
                                .write(
                                        List.of(
                                                Map.of(
                                                        "principal",
                                                        "reference-platform",
                                                        "token",
                                                        PLATFORM_TOKEN,
                                                        "pipelineIds",
                                                        CASES.stream()
                                                                .map(
                                                                        n ->
                                                                                "pipeline."
                                                                                        + DOMAIN
                                                                                        + "."
                                                                                        + n)
                                                                .toList(),
                                                        "environments",
                                                        List.of("test")))));
        properties.add(
                "AWE_PRODUCTION_TARGET_BINDINGS",
                () -> {
                    var map = new LinkedHashMap<String, Object>();
                    for (String name : CASES)
                        map.put(
                                "target.production." + name,
                                Map.of(
                                        "sourceSystem",
                                        "postgres",
                                        "externalRef",
                                        "production." + physical(name),
                                        "timeFields",
                                        Map.of()));
                    return new ai.awe.registry.support.JsonDocuments().write(map);
                });
        properties.add(
                "AWE_PRODUCTION_REGRESSION_SUITES",
                () ->
                        new ai.awe.registry.support.JsonDocuments()
                                .write(
                                        CASES.stream()
                                                .map(
                                                        n ->
                                                                Map.of(
                                                                        "pipelineId",
                                                                        "pipeline."
                                                                                + DOMAIN
                                                                                + "."
                                                                                + n,
                                                                        "suiteId",
                                                                        "suite.production.users",
                                                                        "version",
                                                                        "1.0.0",
                                                                        "contentHash",
                                                                        SqlBackedProductionPlatform
                                                                                .SUITE_HASH,
                                                                        "independentCheckers",
                                                                        List.of(
                                                                                SqlBackedProductionPlatform
                                                                                        .CHECKER)))
                                                .toList()));
        properties.add(
                "AWE_CONSUMER_POLICIES",
                () ->
                        new ai.awe.registry.support.JsonDocuments()
                                .write(
                                        List.of(
                                                Map.of(
                                                        "principal",
                                                        "production-consumer",
                                                        "token",
                                                        CONSUMER,
                                                        "functionIds",
                                                        CASES.stream()
                                                                .map(
                                                                        n ->
                                                                                "function.production."
                                                                                        + n
                                                                                        + "_query")
                                                                .toList(),
                                                        "purposes",
                                                        List.of(PURPOSE),
                                                        "fields",
                                                        List.of("user_id", "score", "value"),
                                                        "maxRows",
                                                        100,
                                                        "maxBytes",
                                                        16384,
                                                        "maxWindowSeconds",
                                                        86400))));
        properties.add("AWE_HIVE_METADATA_URL", () -> "");
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_DATA_READINESS_URL", () -> "");
        properties.add("AWE_METRIC_VERIFICATION_URL", () -> "");
    }

    @BeforeAll
    void initializePlatformAndFormalInput() throws Exception {
        platform().attach(db, warehouse, new TransactionTemplate(transactions));
        createDomain(DOMAIN);
        JsonNode subject =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("entity.production.user"),
                                        "family",
                                        "BUSINESS_ENTITY",
                                        "description",
                                        "Formal registered user",
                                        "properties",
                                        List.of(property("user_id", "STRING", true, false)),
                                        "identityKeys",
                                        List.of("user_id"))));
        source = publishStaticUserSource(DOMAIN, subject);
    }

    @AfterAll
    static synchronized void closePlatform() {
        if (platform != null) {
            platform.close();
            platform = null;
        }
    }

    @Override
    void beforeWarehouseReset() {
        platform().reset();
    }

    private Assets assets(String name) throws Exception {
        if (assets.containsKey(name)) return assets.get(name);
        JsonNode schema =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("dataset.production." + name + "_row"),
                                        "family",
                                        "DATASET",
                                        "description",
                                        "Declared target row before a table exists",
                                        "properties",
                                        List.of(
                                                property("user_id", "STRING", true, false),
                                                property("score", "INTEGER", true, false)),
                                        "identityKeys",
                                        List.of("user_id"))));
        var value = publishVersion(name, "1.0.0", schema, false);
        assets.put(name, value);
        return value;
    }

    private Assets publishVersion(String name, String version, JsonNode schema, boolean coreOnly)
            throws Exception {
        var datasetSpec =
                spec(
                        model(
                                "ref",
                                ref("dataset.production." + name, version),
                                "grain",
                                Map.of("kind", "ENTITY_SNAPSHOT", "keys", List.of("user_id")),
                                "time",
                                Map.of(
                                        "roles",
                                        List.of("OBSERVED_AT"),
                                        "businessZone",
                                        "Asia/Shanghai",
                                        "backfillPossible",
                                        false),
                                "subjectType",
                                ref("entity.production.user"),
                                "dimensions",
                                List.of()));
        datasetSpec.put("schemaVersion", 3);
        datasetSpec.set(
                "datasetContract",
                json.parse(
                        json.write(
                                model(
                                        "rowSchema",
                                        ref(schema.path("entity_id").asText()),
                                        "mode",
                                        "LOGICAL_TARGET",
                                        "targetBindingRef",
                                        "target.production." + name,
                                        "readMode",
                                        "STATIC_SNAPSHOT",
                                        "rangeRole",
                                        "OBSERVED_AT"))));
        JsonNode dataset = publish("Dataset", datasetSpec);
        JsonNode contract =
                publish(
                        "ContractDefinition",
                        spec(
                                model(
                                        "ref",
                                        ref("contract.production." + name, version),
                                        "subject",
                                        ref("dataset.production." + name, version),
                                        "kind",
                                        "DATA",
                                        "ownerTeam",
                                        "metric_team",
                                        "obligations",
                                        List.of(
                                                Map.of(
                                                        "ruleId",
                                                        "check.pipeline.output.schema",
                                                        "at",
                                                        "EXECUTE",
                                                        "blocking",
                                                        true),
                                                Map.of(
                                                        "ruleId",
                                                        "check.pipeline.output.grain",
                                                        "at",
                                                        "EXECUTE",
                                                        "blocking",
                                                        true),
                                                Map.of(
                                                        "ruleId",
                                                        "check.pipeline.result.equivalence",
                                                        "at",
                                                        "EXECUTE",
                                                        "blocking",
                                                        true)))));
        Object input =
                Map.of("kind", "SCAN", "source", "users", "fromDayOffset", 0, "toDayOffset", 0);
        if (coreOnly)
            input =
                    Map.of(
                            "kind",
                            "FILTER",
                            "input",
                            input,
                            "predicate",
                            Map.of(
                                    "kind",
                                    "COMPARE",
                                    "operation",
                                    "EQ",
                                    "left",
                                    field("users.segment"),
                                    "right",
                                    Map.of("kind", "LITERAL", "type", "STRING", "value", "core")));
        Object plan =
                Map.of(
                        "kind",
                        "PROJECT",
                        "input",
                        input,
                        "columns",
                        List.of(
                                Map.of("name", "user_id", "expression", field("users.user_id")),
                                Map.of(
                                        "name",
                                        "score",
                                        "expression",
                                        Map.of(
                                                "kind", "LITERAL", "type", "INTEGER", "value",
                                                "1"))));
        var pipelineSpec =
                spec(
                        model(
                                "ref",
                                ref("pipeline.production." + name, version),
                                "family",
                                "PIPELINE",
                                "description",
                                "Formal data-processing intent; deployment is external",
                                "properties",
                                List.of(property("pipeline_id", "STRING", true, false)),
                                "identityKeys",
                                List.of("pipeline_id")));
        pipelineSpec.put("schemaVersion", 3);
        pipelineSpec.set(
                "pipelineContract",
                json.parse(
                        json.write(
                                Map.of(
                                        "sources",
                                        List.of(
                                                Map.of(
                                                        "alias",
                                                        "users",
                                                        "readFunction",
                                                        ref("function.production.users_read"))),
                                        "targets",
                                        List.of(
                                                Map.of(
                                                        "dataset",
                                                        ref("dataset.production." + name, version),
                                                        "rowSchema",
                                                        ref(schema.path("entity_id").asText()),
                                                        "plan",
                                                        plan)),
                                        "requiredContracts",
                                        List.of(ref("contract.production." + name, version)),
                                        "mode",
                                        "MODEL_COMPILED"))));
        JsonNode pipeline = publish("ObjectType", pipelineSpec);
        String artifactId = name + "-" + version + "-artifact";
        JsonNode artifact =
                ok(
                        "GET",
                        "/api/production/plans/"
                                + pipeline.path("id").asText()
                                + "?contentHash="
                                + pipeline.path("content_hash").asText(),
                        AUTHOR,
                        null);
        platform().register(artifactId, artifact.path("artifact"), table(name), coreOnly);
        return new Assets(name, dataset, schema, contract, pipeline, artifactId, table(name));
    }

    private JsonNode proposal(Assets asset) throws Exception {
        return ok(
                "POST",
                "/api/production/admissions",
                AUTHOR,
                Map.of(
                        "pipelineReleaseId",
                        asset.pipeline().path("id").asText(),
                        "pipelineContentHash",
                        asset.pipeline().path("content_hash").asText(),
                        "artifactId",
                        asset.artifactId(),
                        "environment",
                        "test",
                        "idempotencyKey",
                        key()));
    }

    private Approved approved(Assets asset) throws Exception {
        JsonNode proposal = proposal(asset);
        assertEquals("PREPARED", proposal.path("status").asText(), proposal.toString());
        JsonNode checked =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        JsonNode check = checked.path("latestCheck");
        assertEquals("PASSED", check.path("status").asText(), checked.toString());
        JsonNode result =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/approve",
                        REVIEWER,
                        Map.of("checkId", check.path("id").asText(), "expiresInSeconds", 300));
        assertFalse(result.path("receipt").isEmpty(), result.toString());
        return new Approved(asset, proposal, check, result.path("receipt"));
    }

    private ObjectNode consumeBody(Approved approved, String key) {
        JsonNode receipt = approved.receipt().path("receipt_doc");
        return json.object(
                json.write(
                        Map.of(
                                "receiptId",
                                approved.receipt().path("id").asText(),
                                "receiptHash",
                                approved.receipt().path("receipt_hash").asText(),
                                "artifactHash",
                                approved.assets().pipeline().path("id").isMissingNode()
                                        ? ""
                                        : approved.proposal()
                                                .path("expected_artifact_hash")
                                                .asText(),
                                "pipelineReleaseId",
                                approved.assets().pipeline().path("id").asText(),
                                "pipelineContentHash",
                                approved.assets().pipeline().path("content_hash").asText(),
                                "environment",
                                "test",
                                "baselineRevision",
                                receipt.path("expectedBaselineRevision").asText(),
                                "idempotencyKey",
                                key)));
    }

    private JsonNode consume(Approved approved) throws Exception {
        return ok(
                "POST",
                "/api/production/admissions/"
                        + approved.proposal().path("id").asText()
                        + "/consume",
                PLATFORM_TOKEN,
                consumeBody(approved, key()));
    }

    private JsonNode realize(Approved approved, JsonNode consumed) throws Exception {
        String deployment = platform().deploy(consumed.path("authorization"));
        return observeRealization(approved, consumed, deployment);
    }

    private JsonNode observeRealization(Approved approved, JsonNode consumed, String deployment)
            throws Exception {
        return ok(
                "POST",
                "/api/production/admissions/"
                        + approved.proposal().path("id").asText()
                        + "/realizations",
                PLATFORM_TOKEN,
                Map.of(
                        "consumptionId",
                        consumed.path("id").asText(),
                        "deploymentRef",
                        deployment,
                        "idempotencyKey",
                        key()));
    }

    private JsonNode query(JsonNode function) throws Exception {
        return ok("POST", "/api/data-tasks", CONSUMER, metricRequest(function, key()));
    }

    private void assertCount(JsonNode function, int expected) throws Exception {
        JsonNode result = query(function);
        assertEquals("QUERIED", result.path("status").asText(), result.toString());
        assertEquals(expected, result.path("result").path("rows").get(0).path("value").asInt());
    }

    @Test
    void modelFirstArtifactAndRealRegressionThenPlatformCasRealizeAndRead() throws Exception {
        Assets asset = assets("happy");
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, asset.table()));
        assertTrue(
                realizations
                        .effectiveBinding(UUID.fromString(asset.dataset().path("id").asText()))
                        .isEmpty());
        int before = platform().actualQueries.get();
        Approved approved = approved(asset);
        assertTrue(platform().actualQueries.get() > before);
        assertTrue(platform().independentQueries.get() > 0);
        JsonNode consumed = consume(approved);
        assertEquals("CONSUMED", consumed.path("status").asText());
        JsonNode realized = realize(approved, consumed);
        assertEquals("PASSED", realized.path("status").asText(), realized.toString());
        assertEquals(4, db.queryForObject("SELECT count(*) FROM " + asset.table(), Integer.class));
        var proof =
                realizations.realization(
                        new ai.awe.kernel.identity.EntityRef(
                                asset.dataset().path("entity_id").asText(), "1.0.0"));
        assertTrue(proof.isPresent());
        assertEquals(
                asset.dataset().path("content_hash").asText(), proof.get().datasetContentHash());
        assertEquals(consumed.path("id").asText(), proof.get().consumptionId().toString());
        JsonNode unchanged =
                ok("GET", "/api/releases/" + asset.dataset().path("id").asText(), AUTHOR, null);
        assertEquals(asset.dataset().path("content_hash"), unchanged.path("content_hash"));
        assertTrue(unchanged.path("spec").path("bindings").isEmpty());
        JsonNode queryFunction = consumer(asset, "1.0.0");
        JsonNode result =
                ok("POST", "/api/data-tasks", CONSUMER, metricRequest(queryFunction, key()));
        assertEquals("QUERIED", result.path("status").asText(), result.toString());
        assertEquals(4, result.path("result").path("rows").get(0).path("value").asInt());
    }

    @Test
    void artifactBytesAndUnknownClientPassedCannotCreatePermit() throws Exception {
        Assets asset = assets("artifact");
        platform().corrupt(asset.artifactId());
        JsonNode proposal = proposal(asset);
        assertEquals("BLOCKED", proposal.path("status").asText(), proposal.toString());
        var injected =
                send(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of("status", "PASSED"));
        assertEquals(400, injected.statusCode());
        assertEquals(
                403,
                send(
                                "POST",
                                "/api/production/admissions/"
                                        + proposal.path("id").asText()
                                        + "/approve",
                                AUTHOR,
                                Map.of(
                                        "checkId",
                                        UUID.randomUUID().toString(),
                                        "expiresInSeconds",
                                        300))
                        .statusCode());
    }

    @Test
    void realCandidateWrongResultAndDeletedObligationRejectApproval() throws Exception {
        Assets asset = assets("scope");
        platform().wrongExecution(true);
        JsonNode proposal = proposal(asset);
        JsonNode failed =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        assertEquals(
                "FAILED", failed.path("latestCheck").path("status").asText(), failed.toString());
        platform().wrongExecution(false);
        platform()
                .mutate(
                        response -> {
                            JsonNode regression = response.path("regression");
                            if (regression.path("checks").isArray()
                                    && !regression.path("checks").isEmpty())
                                ((com.fasterxml.jackson.databind.node.ArrayNode)
                                                regression.path("checks"))
                                        .remove(0);
                        });
        JsonNode incomplete =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        assertEquals(
                "INCONCLUSIVE",
                incomplete.path("latestCheck").path("status").asText(),
                incomplete.toString());
        assertEquals(
                409,
                send(
                                "POST",
                                "/api/production/admissions/"
                                        + proposal.path("id").asText()
                                        + "/approve",
                                REVIEWER,
                                Map.of(
                                        "checkId",
                                        incomplete.path("latestCheck").path("id").asText(),
                                        "expiresInSeconds",
                                        300))
                        .statusCode());
    }

    @Test
    void receiptRejectsDifferentArtifactAndStaleProofEvenWithValidPlatformCredential()
            throws Exception {
        Approved approved = approved(assets("ttl"));
        var wrong = consumeBody(approved, key());
        wrong.put("artifactHash", "0".repeat(64));
        assertEquals(
                409,
                send(
                                "POST",
                                "/api/production/admissions/"
                                        + approved.proposal().path("id").asText()
                                        + "/consume",
                                PLATFORM_TOKEN,
                                wrong)
                        .statusCode());
        // Immutable receipt timestamps cannot be edited; a deliberately stale genuine proof is
        // rejected
        // before any receipt can be issued, while zero TTL is rejected at the approval boundary.
        assertEquals(
                400,
                send(
                                "POST",
                                "/api/production/admissions/"
                                        + approved.proposal().path("id").asText()
                                        + "/approve",
                                REVIEWER,
                                Map.of(
                                        "checkId",
                                        approved.check().path("id").asText(),
                                        "expiresInSeconds",
                                        0))
                        .statusCode());
        platform()
                .mutate(
                        response -> {
                            if (response.path("regression").isObject())
                                ((ObjectNode) response.path("regression"))
                                        .put("observedAt", "2020-01-01T00:00:00Z");
                        });
        assertEquals(
                409,
                send(
                                "POST",
                                "/api/production/admissions/"
                                        + approved.proposal().path("id").asText()
                                        + "/consume",
                                PLATFORM_TOKEN,
                                consumeBody(approved, key()))
                        .statusCode());
    }

    @Test
    @org.junit.jupiter.api.Timeout(45)
    void genuineImmutableReceiptExpiresBeforeItsFirstConsume() throws Exception {
        Assets asset = assets("expired");
        JsonNode proposal = proposal(asset);
        JsonNode checked =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        JsonNode result =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/approve",
                        REVIEWER,
                        Map.of(
                                "checkId",
                                checked.path("latestCheck").path("id").asText(),
                                "expiresInSeconds",
                                30));
        Approved approved =
                new Approved(asset, proposal, checked.path("latestCheck"), result.path("receipt"));
        // 保留不可变批准记录，用真实时钟检验短期许可，而不是直接改写数据库证据。
        Thread.sleep(31_000);
        var denied =
                send(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/consume",
                        PLATFORM_TOKEN,
                        consumeBody(approved, key()));
        assertEquals(409, denied.statusCode(), denied.body());
        assertTrue(denied.body().toLowerCase(java.util.Locale.ROOT).contains("expired"));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM awe_production_consumption WHERE proposal_id=?",
                        Integer.class,
                        UUID.fromString(proposal.path("id").asText())));
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, asset.table()));
    }

    @Test
    void newlyPublishedDependentInvalidatesCompleteImpactAndApprovedReceipt() throws Exception {
        Assets asset = assets("impact");
        Approved approved = approved(asset);
        consumer(asset, "1.0.0");
        assertEquals(
                409,
                send(
                                "POST",
                                "/api/production/admissions/"
                                        + approved.proposal().path("id").asText()
                                        + "/consume",
                                PLATFORM_TOKEN,
                                consumeBody(approved, key()))
                        .statusCode());
    }

    @Test
    void onlyOneConcurrentConsumeAndSamePayloadRetriesAreIdempotent() throws Exception {
        Approved approved = approved(assets("concurrent"));
        String path =
                "/api/production/admissions/"
                        + approved.proposal().path("id").asText()
                        + "/consume";
        ObjectNode body = consumeBody(approved, key());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> send("POST", path, PLATFORM_TOKEN, body));
            var second = executor.submit(() -> send("POST", path, PLATFORM_TOKEN, body));
            HttpResponse<String> a = first.get(40, TimeUnit.SECONDS),
                    b = second.get(40, TimeUnit.SECONDS);
            assertEquals(200, a.statusCode(), a.body());
            assertEquals(200, b.statusCode(), b.body());
            assertEquals(json.parse(a.body()).path("id"), json.parse(b.body()).path("id"));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM awe_production_consumption WHERE proposal_id=?",
                            Integer.class,
                            UUID.fromString(approved.proposal().path("id").asText())));
            assertEquals(
                    409,
                    send("POST", path, PLATFORM_TOKEN, consumeBody(approved, key())).statusCode());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void newConsumedWriteIntentFencesOldProofBeforeDeploymentCallback() throws Exception {
        Assets original = assets("fence");
        Approved first = approved(original);
        JsonNode initialConsume = consume(first);
        assertEquals("PASSED", realize(first, initialConsume).path("status").asText());
        assertTrue(
                realizations
                        .effectiveBinding(UUID.fromString(original.dataset().path("id").asText()))
                        .isPresent());
        Assets next = publishVersion("fence", "2.0.0", original.schema(), true);
        Approved second = approved(next);
        consume(second);
        assertTrue(
                realizations
                        .effectiveBinding(UUID.fromString(original.dataset().path("id").asText()))
                        .isEmpty(),
                "old proof must be fenced as soon as another artifact has target write authority");
    }

    @Test
    void sameSchemaDifferentSemanticVersionCannotKeepOldLogicalBinding() throws Exception {
        Assets original = assets("takeover");
        Approved first = approved(original);
        JsonNode consumed = consume(first);
        assertEquals("PASSED", realize(first, consumed).path("status").asText());
        JsonNode oldConsumer = consumer(original, "1.0.0");
        assertEquals(
                4,
                ok("POST", "/api/data-tasks", CONSUMER, metricRequest(oldConsumer, key()))
                        .path("result")
                        .path("rows")
                        .get(0)
                        .path("value")
                        .asInt());
        Assets next = publishVersion("takeover", "2.0.0", original.schema(), true);
        Approved replacement = approved(next);
        JsonNode nextConsume = consume(replacement);
        assertEquals("PASSED", realize(replacement, nextConsume).path("status").asText());
        assertEquals(
                2, db.queryForObject("SELECT count(*) FROM " + original.table(), Integer.class));
        assertTrue(
                realizations
                        .effectiveBinding(UUID.fromString(original.dataset().path("id").asText()))
                        .isEmpty());
        assertTrue(
                realizations
                        .effectiveBinding(UUID.fromString(next.dataset().path("id").asText()))
                        .isPresent());
        JsonNode blocked =
                ok("POST", "/api/data-tasks", CONSUMER, metricRequest(oldConsumer, key()));
        assertNotEquals("QUERIED", blocked.path("status").asText(), blocked.toString());
    }

    @Test
    void runtimeLogicalRealizationsSeedSharedPhysicalImpactAcrossDifferentEntities()
            throws Exception {
        Assets first = assets("shared_x");
        consumer(first, "1.0.0");
        Approved initial = approved(first);
        assertEquals("PASSED", realize(initial, consume(initial)).path("status").asText());
        Assets other = assets("shared_y");
        JsonNode proposal = proposal(other);
        assertEquals("PREPARED", proposal.path("status").asText(), proposal.toString());
        String impact = proposal.path("impact_doc").toString();
        assertTrue(impact.contains(first.dataset().path("id").asText()));
        assertTrue(impact.contains("function.production.shared_x_query"));
        assertTrue(impact.contains("realization_id"));
    }

    @Test
    void laterSharedTargetDeploymentRejectsOlderPipelineEvenWhenItsOwnBaselineIsUnchanged()
            throws Exception {
        Assets older = assets("late_a");
        Approved first = approved(older);
        JsonNode oldConsume = consume(first);
        Assets newer = publishVersion("late_b", "1.0.0", older.schema(), true);
        Approved second = approved(newer);
        JsonNode newConsume = consume(second);
        assertEquals(2, newConsume.path("authorization").path("protocolVersion").asInt());
        assertEquals(1, newConsume.path("authorization").path("targetBaselines").size());
        assertTrue(
                new java.math.BigInteger(
                                        newConsume
                                                .path("authorization")
                                                .path("targetWriteIntents")
                                                .get(0)
                                                .path("intentId")
                                                .asText())
                                .compareTo(
                                        new java.math.BigInteger(
                                                oldConsume
                                                        .path("authorization")
                                                        .path("targetWriteIntents")
                                                        .get(0)
                                                        .path("intentId")
                                                        .asText()))
                        > 0);
        String deployment = platform().deploy(newConsume.path("authorization"));
        JsonNode realizationReport = observeRealization(second, newConsume, deployment);
        assertEquals(
                "PASSED", realizationReport.path("status").asText(), realizationReport.toString());
        JsonNode newerConsumer = consumer(newer, "1.0.0");
        assertCount(newerConsumer, 2);
        // 旧发布即使最后才抵达，也不能借自己仍为 0 的 Pipeline baseline 覆盖共享物理表。
        assertThrows(
                IllegalStateException.class,
                () -> platform().deploy(oldConsume.path("authorization")));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM " + older.table(), Integer.class));
        assertEquals(
                deployment,
                platform().deploy(newConsume.path("authorization")),
                "the exact accepted consumption is platform-idempotent");
        ObjectNode reused = newConsume.path("authorization").deepCopy();
        reused.put("receiptHash", "0".repeat(64));
        assertThrows(IllegalStateException.class, () -> platform().deploy(reused));
        assertCount(newerConsumer, 2);
    }

    @Test
    void olderCallbackCannotPoisonTheNewSharedTargetOwner() throws Exception {
        Assets older = assets("callback_a");
        Approved first = approved(older);
        JsonNode oldConsume = consume(first);
        String oldDeployment = platform().deploy(oldConsume.path("authorization"));
        assertEquals(
                "PASSED",
                observeRealization(first, oldConsume, oldDeployment).path("status").asText());
        Assets newer = publishVersion("callback_b", "1.0.0", older.schema(), true);
        Approved second = approved(newer);
        JsonNode newConsume = consume(second);
        assertEquals("PASSED", realize(second, newConsume).path("status").asText());
        JsonNode newConsumer = consumer(newer, "1.0.0");
        assertCount(newConsumer, 2);
        JsonNode late = observeRealization(first, oldConsume, oldDeployment);
        assertNotEquals("PASSED", late.path("status").asText(), late.toString());
        assertTrue(
                realizations
                        .effectiveBinding(UUID.fromString(newer.dataset().path("id").asText()))
                        .isPresent(),
                "a stale observation is audit history and cannot become the target's active proof");
        assertCount(newConsumer, 2);
    }

    @Test
    void sameSchemaExternalPlatformReplacementWithoutCallbackRevokesCachedProof() throws Exception {
        Assets original = assets("outside");
        Approved first = approved(original);
        JsonNode consumed = consume(first);
        assertEquals("PASSED", realize(first, consumed).path("status").asText());
        JsonNode function = consumer(original, "1.0.0");
        assertCount(function, 4);
        platform().replaceOutsideAdmission(consumed.path("authorization"));
        assertEquals(
                2, db.queryForObject("SELECT count(*) FROM " + original.table(), Integer.class));
        // Registry 没收到新 callback、Schema 完全不变；查询仍须向可信平台核对当前目标所有者。
        assertTrue(
                realizations
                        .effectiveBinding(UUID.fromString(original.dataset().path("id").asText()))
                        .isEmpty());
        JsonNode result = query(function);
        assertNotEquals("QUERIED", result.path("status").asText(), result.toString());
        assertTrue(
                result.path("result").path("rows").isMissingNode()
                        || result.path("result").path("rows").isEmpty(),
                result.toString());
    }

    @Test
    void incompleteOrWrongPhysicalTargetVectorsCannotPrepareAnAdmission() throws Exception {
        Assets missing = assets("vector_missing");
        platform().mutate(response -> response.remove("targetBaselines"));
        JsonNode absent = proposal(missing);
        assertNotEquals("PREPARED", absent.path("status").asText(), absent.toString());
        platform().reset();
        Assets wrong = assets("vector_wrong");
        platform()
                .mutate(
                        response -> {
                            JsonNode vector = response.path("targetBaselines");
                            if (vector.isArray() && !vector.isEmpty())
                                ((ObjectNode) vector.get(0))
                                        .put("targetKey", "postgres:production.another_target");
                        });
        JsonNode crossed = proposal(wrong);
        assertNotEquals("PREPARED", crossed.path("status").asText(), crossed.toString());
    }

    @Test
    void platformRejectsMissingOrSupersededTargetVectorsBeforeWritingRows() throws Exception {
        Assets original = assets("vector_deploy");
        Approved first = approved(original);
        JsonNode consumed = consume(first);
        ObjectNode missing = consumed.path("authorization").deepCopy();
        missing.remove("targetBaselines");
        assertThrows(IllegalStateException.class, () -> platform().deploy(missing));
        ObjectNode wrongIntent = consumed.path("authorization").deepCopy();
        ((ObjectNode) wrongIntent.path("targetWriteIntents").get(0)).put("intentId", "0");
        assertThrows(IllegalStateException.class, () -> platform().deploy(wrongIntent));
        assertNull(
                db.queryForObject("SELECT to_regclass(?)::text", String.class, original.table()));
        assertEquals("PASSED", realize(first, consumed).path("status").asText());
    }

    @Test
    void allTargetCasChecksPrecedeWritesAndSecondTargetFailureRollsBackTheWholeDeployment()
            throws Exception {
        Assets first = assets("atomic_first");
        Assets second = assets("atomic_second");
        ObjectNode pipelineSpec = first.pipeline().path("spec").deepCopy();
        ((ObjectNode) pipelineSpec.path("model"))
                .set("ref", json.parse(json.write(ref("pipeline.production.atomic"))));
        var contract = (ObjectNode) pipelineSpec.path("pipelineContract");
        ((com.fasterxml.jackson.databind.node.ArrayNode) contract.path("targets"))
                .add(
                        second.pipeline()
                                .path("spec")
                                .path("pipelineContract")
                                .path("targets")
                                .get(0));
        ((com.fasterxml.jackson.databind.node.ArrayNode) contract.path("requiredContracts"))
                .add(json.parse(json.write(ref("contract.production.atomic_second"))));
        JsonNode pipeline = publish("ObjectType", pipelineSpec);
        JsonNode artifact =
                ok(
                        "GET",
                        "/api/production/plans/"
                                + pipeline.path("id").asText()
                                + "?contentHash="
                                + pipeline.path("content_hash").asText(),
                        AUTHOR,
                        null);
        platform().register("atomic-artifact", artifact.path("artifact"), first.table(), false);
        Assets combined =
                new Assets(
                        "atomic",
                        first.dataset(),
                        first.schema(),
                        first.contract(),
                        pipeline,
                        "atomic-artifact",
                        first.table());
        Approved approved = approved(combined);
        JsonNode consumed = consume(approved);
        assertEquals(2, consumed.path("authorization").path("targetBaselines").size());
        ObjectNode wrongSecondBaseline = consumed.path("authorization").deepCopy();
        ((ObjectNode) wrongSecondBaseline.path("targetBaselines").get(1))
                .put("revision", "unrelated-revision");
        assertThrows(IllegalStateException.class, () -> platform().deploy(wrongSecondBaseline));
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, first.table()));
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, second.table()));
        // 第二目标真实 INSERT 失败，第一目标写入及全体目标版本必须随事务一起回滚。
        db.execute(
                "CREATE TABLE "
                        + second.table()
                        + "(user_id TEXT PRIMARY KEY,score BIGINT NOT NULL CONSTRAINT reject_positive CHECK(score<0))");
        assertThrows(
                RuntimeException.class, () -> platform().deploy(consumed.path("authorization")));
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, first.table()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM " + second.table(), Integer.class));
        db.execute("ALTER TABLE " + second.table() + " DROP CONSTRAINT reject_positive");
        JsonNode realization = realize(approved, consumed);
        assertEquals("PASSED", realization.path("status").asText(), realization.toString());
        assertEquals(2, realization.path("realizations").size());
        assertEquals(4, db.queryForObject("SELECT count(*) FROM " + first.table(), Integer.class));
        assertEquals(4, db.queryForObject("SELECT count(*) FROM " + second.table(), Integer.class));
    }

    @Test
    void concurrentDifferentPipelinesHaveOneOrderedImmutableIntentOwnerPerEnvironment()
            throws Exception {
        Assets a = assets("intent_a");
        Assets b = publishVersion("intent_b", "1.0.0", a.schema(), true);
        Approved first = approved(a), second = approved(b);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        JsonNode left, right;
        try {
            var f =
                    executor.submit(
                            () -> {
                                start.await();
                                return consume(first);
                            });
            var g =
                    executor.submit(
                            () -> {
                                start.await();
                                return consume(second);
                            });
            start.countDown();
            left = f.get(40, TimeUnit.SECONDS);
            right = g.get(40, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        long leftIntent =
                Long.parseLong(
                        left.path("authorization")
                                .path("targetWriteIntents")
                                .get(0)
                                .path("intentId")
                                .asText());
        long rightIntent =
                Long.parseLong(
                        right.path("authorization")
                                .path("targetWriteIntents")
                                .get(0)
                                .path("intentId")
                                .asText());
        assertNotEquals(leftIntent, rightIntent);
        JsonNode newer = leftIntent > rightIntent ? left : right;
        JsonNode older = leftIntent > rightIntent ? right : left;
        UUID newerId = UUID.fromString(newer.path("id").asText()),
                olderId = UUID.fromString(older.path("id").asText());
        List<String> keys = new ArrayList<>();
        newer.path("authorization").path("physicalTargets").forEach(k -> keys.add(k.asText()));
        assertTrue(
                writeIntents.current(
                        "test",
                        newerId,
                        newer.path("authorization").path("targetWriteIntents"),
                        keys));
        assertFalse(
                writeIntents.current(
                        "test",
                        olderId,
                        older.path("authorization").path("targetWriteIntents"),
                        keys));
        // 复用真实 consumption 外键，直接验证底层意图存储的环境隔离；不宣称第二企业环境已接入。
        JsonNode otherEnvironment =
                new TransactionTemplate(transactions)
                        .execute(
                                tx ->
                                        writeIntents.append(
                                                "isolated-test-environment", keys, olderId));
        assertTrue(
                writeIntents.current("isolated-test-environment", olderId, otherEnvironment, keys));
        assertTrue(
                writeIntents.current(
                        "test",
                        newerId,
                        newer.path("authorization").path("targetWriteIntents"),
                        keys));
        assertFalse(writeIntents.current("test", olderId, otherEnvironment, keys));
        // 用真实追加自然跨过十进制位数边界，防止把 id::text 的字典序当作授权先后。
        // 每个填充环境仍使用已提交 consumption 外键，不改历史证据，也不设置数据库 sequence。
        String boundaryEnvironment = "isolated-test-environment";
        JsonNode boundaryOlder = otherEnvironment;
        long allocated = Long.parseLong(boundaryOlder.get(0).path("intentId").asText());
        long boundary = 10;
        while (boundary <= allocated) boundary *= 10;
        while (true) {
            if (allocated == boundary - 1) {
                String environment = boundaryEnvironment;
                JsonNode boundaryNewer =
                        new TransactionTemplate(transactions)
                                .execute(tx -> writeIntents.append(environment, keys, newerId));
                long latest = Long.parseLong(boundaryNewer.get(0).path("intentId").asText());
                assertEquals(
                        boundary,
                        latest,
                        "the fixture must cross a real decimal generation boundary");
                assertFalse(
                        writeIntents.current(environment, olderId, boundaryOlder, keys),
                        "generation "
                                + allocated
                                + " must be superseded by numeric generation "
                                + latest);
                assertTrue(
                        writeIntents.current(environment, newerId, boundaryNewer, keys),
                        "numeric generation "
                                + latest
                                + " must win over textually larger "
                                + allocated);
                if (boundary >= 100) break;
                boundary *= 10;
            }
            boundaryEnvironment = "intent-boundary-" + UUID.randomUUID();
            String environment = boundaryEnvironment;
            boundaryOlder =
                    new TransactionTemplate(transactions)
                            .execute(tx -> writeIntents.append(environment, keys, olderId));
            allocated = Long.parseLong(boundaryOlder.get(0).path("intentId").asText());
        }
        long immutableId = Math.max(leftIntent, rightIntent);
        assertThrows(
                RuntimeException.class,
                () ->
                        db.update(
                                "UPDATE awe_production_target_intent SET environment='tampered' WHERE id=?",
                                immutableId));
        assertThrows(
                RuntimeException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_production_target_intent WHERE id=?",
                                immutableId));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM awe_production_target_intent WHERE id=?",
                        Integer.class,
                        immutableId));
        assertTrue(
                writeIntents.current(
                        "test",
                        newerId,
                        newer.path("authorization").path("targetWriteIntents"),
                        keys));
    }

    private JsonNode consumer(Assets asset, String version) throws Exception {
        String name = asset.name();
        String functionId = "function.production." + name + "_read";
        publish(
                "FunctionDefinition",
                spec(
                        model(
                                "ref",
                                ref(functionId, version),
                                "ownerType",
                                ref(
                                        asset.dataset().path("entity_id").asText(),
                                        asset.dataset().path("version").asText()),
                                "operation",
                                "dataset.snapshot.v1",
                                "parameters",
                                List.of(),
                                "resultSchemaRef",
                                asset.schema().path("entity_id").asText() + "@1.0.0",
                                "executorBindingRef",
                                "postgres.dataset.snapshot.v1",
                                "requiredAssertions",
                                List.of())));
        Object plan =
                aggregate(
                        Map.of(
                                "kind",
                                "SCAN",
                                "source",
                                "out",
                                "fromDayOffset",
                                0,
                                "toDayOffset",
                                0),
                        List.of(),
                        List.of(measure("value", "COUNT_DISTINCT", field("out.user_id"))));
        var metric =
                spec(
                        model(
                                "ref",
                                ref("metric.production." + name, version),
                                "grain",
                                Map.of("kind", "AGGREGATE", "keys", List.of()),
                                "time",
                                Map.of(
                                        "roles",
                                        List.of("OBSERVED_AT"),
                                        "businessZone",
                                        "Asia/Shanghai",
                                        "backfillPossible",
                                        false),
                                "subjectType",
                                ref("entity.production.user"),
                                "sourceDataset",
                                ref(
                                        asset.dataset().path("entity_id").asText(),
                                        asset.dataset().path("version").asText()),
                                "aggregation",
                                Map.of(
                                        "kind",
                                        "COUNT_DISTINCT",
                                        "permittedRollupAxes",
                                        List.of(),
                                        "forbiddenRollupAxes",
                                        List.of("day")),
                                "allowedDimensions",
                                List.of()));
        metric.put("schemaVersion", 3);
        metric.set(
                "metricContract",
                json.parse(
                        json.write(
                                model(
                                        "statisticalSubject",
                                        ref("entity.production.user"),
                                        "identityField",
                                        "out.user_id",
                                        "identityNullPolicy",
                                        "REJECT",
                                        "unit",
                                        "users",
                                        "timeRole",
                                        "OBSERVED_AT",
                                        "businessZone",
                                        "Asia/Shanghai",
                                        "sources",
                                        List.of(
                                                Map.of(
                                                        "alias",
                                                        "out",
                                                        "readFunction",
                                                        ref(functionId, version))),
                                        "plan",
                                        plan,
                                        "outputs",
                                        List.of(output("value", "INTEGER", "users", false)),
                                        "grain",
                                        List.of(),
                                        "emptyPolicy",
                                        "ZERO",
                                        "zeroDivisionPolicy",
                                        "NULL",
                                        "direct",
                                        null))));
        publish("Metric", metric);
        return publish(
                "FunctionDefinition",
                spec(
                        model(
                                "ref",
                                ref("function.production." + name + "_query", version),
                                "ownerType",
                                ref("metric.production." + name, version),
                                "operation",
                                "metric.query.v1",
                                "parameters",
                                List.of(
                                        Map.of(
                                                "name",
                                                "from",
                                                "type",
                                                "TIMESTAMP",
                                                "required",
                                                true),
                                        Map.of(
                                                "name",
                                                "to",
                                                "type",
                                                "TIMESTAMP",
                                                "required",
                                                true)),
                                "resultSchemaRef",
                                "metric.production." + name + "@" + version,
                                "executorBindingRef",
                                "postgres.metric.query.v1",
                                "requiredAssertions",
                                List.of())));
    }

    private static Map<String, Object> ref(String id, String version) {
        return Map.of("id", id, "modelVersion", version);
    }

    private static String physical(String name) {
        if (name.startsWith("shared_")) return "shared";
        if (name.startsWith("late_")) return "late";
        if (name.startsWith("callback_")) return "callback";
        if (name.startsWith("intent_")) return "intent";
        return name;
    }

    private static String table(String name) {
        return "public.awe_production_" + physical(name);
    }
}
