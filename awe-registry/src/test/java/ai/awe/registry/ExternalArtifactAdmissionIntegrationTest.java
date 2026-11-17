package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.production.ProductionRealizations;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
final class ExternalArtifactAdmissionIntegrationTest extends MetricQueryTestSupport {
    private static final String PLATFORM_TOKEN = "production-reference-caller";
    private static final String DOMAIN = "external_artifact";
    private static final List<String> CASES =
            List.of("happy", "bad", "bytes", "scope", "undeclared", "missing", "stale", "column");
    private static SqlBackedProductionPlatform platform;
    @Autowired PostgresWarehouse warehouse;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductionRealizations realizations;
    @Autowired org.springframework.context.ApplicationContext context;
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

    private static String candidateSql(String name) {
        if (name.equals("column"))
            return "SELECT user_id,1::bigint AS score FROM "
                    + USERS
                    + " WHERE hidden_secret='classified'";
        if (name.equals("undeclared")) return "SELECT user_id,1::bigint AS score FROM " + EVENTS;
        return "SELECT user_id,"
                + (name.equals("bad") ? "2" : "1")
                + "::bigint AS score\nFROM "
                + USERS
                + " ORDER BY user_id";
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<Map<String, Object>> profiles() {
        return CASES.stream()
                .filter(n -> !n.equals("missing"))
                .map(
                        n ->
                                Map.<String, Object>of(
                                        "pipelineId",
                                        "pipeline.external_artifact." + n,
                                        "modelVersion",
                                        "1.0.0",
                                        "artifactId",
                                        n + "-1.0.0-artifact",
                                        "artifactVersion",
                                        "external-v1",
                                        "mediaType",
                                        "application/sql",
                                        "contentHash",
                                        digest(candidateSql(n)),
                                        "proofScopeRef",
                                        "scope.external.users.v1"))
                .toList();
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
                        map.put("postgres:external_artifact." + physical(name), table(name));
                    return new ai.awe.registry.support.JsonDocuments().write(map);
                });
        properties.add(
                "AWE_PRODUCTION_EXTERNAL_ARTIFACTS",
                () -> new ai.awe.registry.support.JsonDocuments().write(profiles()));
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
                                "target.external_artifact." + name,
                                Map.of(
                                        "sourceSystem",
                                        "postgres",
                                        "externalRef",
                                        "external_artifact." + physical(name),
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
                                                                                "function.external_artifact."
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
        db.execute(
                "ALTER TABLE "
                        + USERS
                        + " ADD COLUMN IF NOT EXISTS hidden_secret TEXT NOT NULL DEFAULT 'classified'");
        createDomain(DOMAIN);
        JsonNode subject =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("entity.external_artifact.user"),
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
    void restorePhysicalSourceSchema() {
        db.execute("ALTER TABLE " + USERS + " DROP COLUMN IF EXISTS hidden_secret");
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
                                        ref("dataset.external_artifact." + name + "_row"),
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
                                ref("dataset.external_artifact." + name, version),
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
                                ref("entity.external_artifact.user"),
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
                                        "target.external_artifact." + name,
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
                                        ref("contract.external_artifact." + name, version),
                                        "subject",
                                        ref("dataset.external_artifact." + name, version),
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
                                ref("pipeline.external_artifact." + name, version),
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
                                                        ref(
                                                                "function.external_artifact.users_read"))),
                                        "targets",
                                        List.of(
                                                Map.of(
                                                        "dataset",
                                                        ref(
                                                                "dataset.external_artifact." + name,
                                                                version),
                                                        "rowSchema",
                                                        ref(schema.path("entity_id").asText()),
                                                        "plan",
                                                        plan)),
                                        "requiredContracts",
                                        List.of(ref("contract.external_artifact." + name, version)),
                                        "mode",
                                        "REGISTERED_EXTERNAL"))));
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
        assertEquals(
                json.sha256(json.parse(artifact.path("content").asText())),
                artifact.path("contentHash").asText());
        platform()
                .registerExternal(
                        artifactId,
                        artifact.path("artifact"),
                        candidateSql(name),
                        table(name),
                        coreOnly,
                        "scope.external.users.v1");
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

    private JsonNode consumer(Assets asset, String version) throws Exception {
        String name = asset.name();
        String functionId = "function.external_artifact." + name + "_read";
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
                                ref("metric.external_artifact." + name, version),
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
                                ref("entity.external_artifact.user"),
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
                                        ref("entity.external_artifact.user"),
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
                                ref("function.external_artifact." + name + "_query", version),
                                "ownerType",
                                ref("metric.external_artifact." + name, version),
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
                                "metric.external_artifact." + name + "@" + version,
                                "executorBindingRef",
                                "postgres.metric.query.v1",
                                "requiredAssertions",
                                List.of())));
    }

    private static Map<String, Object> ref(String id, String version) {
        return Map.of("id", id, "modelVersion", version);
    }

    @Test
    void registeredRawSqlIsVerifiedDeployedAndConsumedWithoutChangingFormalHash() throws Exception {
        Assets asset = assets("happy");
        int before = platform().actualQueries.get();
        Approved approved = approved(asset);
        assertTrue(platform().actualQueries.get() > before);
        assertTrue(platform().independentQueries.get() > 0);
        assertEquals(
                digest(candidateSql("happy")),
                approved.proposal().path("expected_artifact_hash").asText());
        JsonNode receipt = approved.receipt().path("receipt_doc");
        assertEquals("REGISTERED_EXTERNAL_FINITE_REGRESSION", receipt.path("proofScope").asText());
        assertEquals(
                "scope.external.users.v1",
                receipt.path("externalArtifact").path("proofScopeRef").asText());
        assertFalse(receipt.path("referenceArtifactHash").asText().isBlank());
        assertNotEquals(receipt.path("artifactHash"), receipt.path("referenceArtifactHash"));
        JsonNode consumed = consume(approved);
        assertEquals(
                receipt.path("externalArtifactProfileHash"),
                consumed.path("authorization").path("externalArtifactProfileHash"));
        JsonNode realized = realize(approved, consumed);
        assertEquals("PASSED", realized.path("status").asText(), realized.toString());
        assertEquals(4, db.queryForObject("SELECT count(*) FROM " + asset.table(), Integer.class));
        assertEquals(4L, db.queryForObject("SELECT sum(score) FROM " + asset.table(), Long.class));
        JsonNode fn = consumer(asset, "1.0.0");
        assertCount(fn, 4);
        JsonNode release =
                ok("GET", "/api/releases/" + asset.dataset().path("id").asText(), AUTHOR, null);
        assertEquals(asset.dataset().path("content_hash"), release.path("content_hash"));
        assertTrue(release.path("spec").path("bindings").isEmpty());
    }

    @Test
    void registeredIncorrectSqlFailsActualIndependentComparison() throws Exception {
        Assets asset = assets("bad");
        JsonNode proposal = proposal(asset);
        assertEquals("PREPARED", proposal.path("status").asText(), proposal.toString());
        JsonNode checked =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        JsonNode check = checked.path("latestCheck");
        assertEquals("FAILED", check.path("status").asText(), checked.toString());
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
                                        check.path("id").asText(),
                                        "expiresInSeconds",
                                        300))
                        .statusCode());
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, asset.table()));
    }

    @Test
    void exactRawBytesAndRegisteredProfileAreRequired() throws Exception {
        Assets bytes = assets("bytes");
        platform().corrupt(bytes.artifactId());
        JsonNode changed = proposal(bytes);
        assertEquals("BLOCKED", changed.path("status").asText(), changed.toString());
        Assets missing = assets("missing");
        JsonNode noProfile = proposal(missing);
        assertEquals("NOT_CONFIGURED", noProfile.path("status").asText(), noProfile.toString());
        JsonNode failed =
                ok(
                        "POST",
                        "/api/production/admissions/" + noProfile.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        assertNotEquals("PASSED", failed.path("latestCheck").path("status").asText());
    }

    @Test
    void candidateCannotReadUndeclaredSourceUsingPlatformServicePrivileges() throws Exception {
        Assets asset = assets("undeclared");
        JsonNode proposal = proposal(asset);
        assertEquals("PREPARED", proposal.path("status").asText(), proposal.toString());
        JsonNode checked =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        assertNotEquals(
                "PASSED", checked.path("latestCheck").path("status").asText(), checked.toString());
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, asset.table()));
    }

    @Test
    void undeclaredPhysicalColumnCannotBypassReadFunctionSchema() throws Exception {
        Assets asset = assets("column");
        assertEquals(
                4,
                db.queryForObject(
                        "SELECT count(*) FROM " + USERS + " WHERE hidden_secret='classified'",
                        Integer.class));
        JsonNode proposal = proposal(asset);
        assertEquals("PREPARED", proposal.path("status").asText(), proposal.toString());
        JsonNode checked =
                ok(
                        "POST",
                        "/api/production/admissions/" + proposal.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        assertNotEquals(
                "PASSED", checked.path("latestCheck").path("status").asText(), checked.toString());
        assertNull(db.queryForObject("SELECT to_regclass(?)::text", String.class, asset.table()));
    }

    @Test
    void wrongFiniteScopeOrExecutionEvidenceCannotBorrowApproval() throws Exception {
        Assets asset = assets("scope");
        Approved approved = approved(asset);
        platform()
                .mutate(
                        response -> {
                            if (response.path("regression").isObject())
                                ((ObjectNode) response.path("regression"))
                                        .put("proofScopeRef", "scope.unrelated");
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
        platform()
                .mutate(
                        response ->
                                ((ObjectNode) response.path("externalExecution"))
                                        .put("mode", "UNRESTRICTED"));
        JsonNode checked =
                ok(
                        "POST",
                        "/api/production/admissions/"
                                + approved.proposal().path("id").asText()
                                + "/verify",
                        AUTHOR,
                        Map.of());
        assertNotEquals("PASSED", checked.path("latestCheck").path("status").asText());
    }

    @Test
    void externalConfigurationChangesInvalidateAcceptedProposalAndMalformedProfilesFailClosed()
            throws Exception {
        Assets asset = assets("stale");
        Approved approved = approved(asset);
        var values = new ArrayList<>(profiles());
        var changed = new LinkedHashMap<>(values.getFirst());
        changed.put("proofScopeRef", "scope.changed.v2");
        values.set(0, changed);
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("AWE_PRODUCTION_EXTERNAL_ARTIFACTS", json.write(values));
        var registered = new ai.awe.registry.production.ProductionExternalArtifacts(env, json);
        var original =
                context.getBean(ai.awe.registry.production.ProductionExternalArtifacts.class);
        assertNotEquals(original.configurationHash(), registered.configurationHash());
        var revisedModels =
                new ai.awe.registry.production.ProductionModels(
                        context.getBean(ai.awe.registry.authoring.ModelRegistry.class),
                        json,
                        context.getBean(ai.awe.registry.production.ProductionTargets.class),
                        context.getBeanProvider(ai.awe.registry.model.LogicalDatasetBindings.class),
                        registered);
        var constructor =
                ai.awe.registry.production.ProductionAdmissionService.class.getConstructors()[0];
        Object[] arguments =
                Arrays.stream(constructor.getParameterTypes())
                        .map(
                                t ->
                                        t == ai.awe.registry.production.ProductionModels.class
                                                ? revisedModels
                                                : context.getBean(t))
                        .toArray();
        var revised =
                (ai.awe.registry.production.ProductionAdmissionService)
                        constructor.newInstance(arguments);
        JsonNode checked =
                json.parse(
                        json.write(
                                revised.verify(
                                        UUID.fromString(approved.proposal().path("id").asText()),
                                        json.object("{}"),
                                        "policy-revision-test")));
        assertEquals(
                "STALE", checked.path("latestCheck").path("status").asText(), checked.toString());
        var duplicate = new ArrayList<>(profiles());
        duplicate.add(profiles().getFirst());
        var malformed =
                new ai.awe.registry.production.ProductionExternalArtifacts(
                        new org.springframework.mock.env.MockEnvironment()
                                .withProperty(
                                        "AWE_PRODUCTION_EXTERNAL_ARTIFACTS", json.write(duplicate)),
                        json);
        assertTrue(malformed.find("pipeline.external_artifact.happy", "1.0.0").isEmpty());
    }

    private static String physical(String name) {
        return name;
    }

    private static String table(String name) {
        return "public.awe_external_artifact_" + name;
    }
}
