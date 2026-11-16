package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Formal coverage, actual consumer observations and SQL-backed repair replays share one kernel. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=knowledge-author",
            "awe.auth.reviewer-token=knowledge-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeLoopIntegrationTest {
    private static final String AUTHOR = "knowledge-author",
            REVIEWER = "knowledge-reviewer",
            CONSUMER = "knowledge-consumer-token",
            OTHER = "knowledge-other-token";
    private static final String FIRST = "function.knowledge.extract",
            SECOND = "function.knowledge.extract_other",
            ABSENT = "function.knowledge.absent";
    private static final String METRIC = "function.knowledge.population";
    private static final String TABLE = "public.awe_knowledge_events";
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Autowired JsonDocuments json;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;
    private JsonNode first, second, source;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry p) {
        p.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        p.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        p.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
        p.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () -> "{\"postgres:knowledge.events\":\"public.awe_knowledge_events\"}");
        p.add("AWE_HIVE_METADATA_URL", () -> "");
        p.add("AWE_BI_METADATA_URL", () -> "");
        p.add(
                "AWE_CONSUMER_POLICIES",
                () ->
                        new JsonDocuments()
                                .write(
                                        List.of(
                                                policy(
                                                        "knowledge-consumer",
                                                        CONSUMER,
                                                        List.of(FIRST, SECOND, ABSENT, METRIC)),
                                                policy(
                                                        "knowledge-other",
                                                        OTHER,
                                                        List.of("function.hidden.extract")))));
    }

    private static Map<String, Object> policy(
            String principal, String token, List<String> functions) {
        return Map.of(
                "principal",
                principal,
                "token",
                token,
                "functionIds",
                functions,
                "purposes",
                List.of("analysis"),
                "fields",
                List.of("event_id", "user_id", "event_time", "city", "value"),
                "maxRows",
                20,
                "maxBytes",
                4096,
                "maxWindowSeconds",
                86400);
    }

    @BeforeAll
    void publishActualSources() throws Exception {
        db.execute(
                "CREATE TABLE "
                        + TABLE
                        + "(event_id TEXT PRIMARY KEY,user_id TEXT NOT NULL,event_time TIMESTAMPTZ NOT NULL,city TEXT NOT NULL)");
        ok(
                "POST",
                "/api/domains",
                AUTHOR,
                Map.of(
                        "code",
                        "knowledge",
                        "displayName",
                        "Knowledge loop acceptance",
                        "ownerTeam",
                        "knowledge_team"));
        publish(
                "ObjectType",
                model(
                        "ref",
                        ref("entity.knowledge.user"),
                        "family",
                        "BUSINESS_ENTITY",
                        "description",
                        "Device identifier",
                        "properties",
                        List.of(property("user_id", "STRING")),
                        "identityKeys",
                        List.of("user_id")));
        publish(
                "ObjectType",
                model(
                        "ref",
                        ref("dataset.knowledge.row"),
                        "family",
                        "DATASET",
                        "description",
                        "Explicit event row",
                        "properties",
                        List.of(
                                property("event_id", "STRING"),
                                property("user_id", "STRING"),
                                property("event_time", "TIMESTAMP"),
                                property("city", "STRING")),
                        "identityKeys",
                        List.of("event_id")));
        source =
                ok(
                        "POST",
                        "/api/sources/postgres/imports",
                        AUTHOR,
                        Map.of("externalRef", "knowledge.events"));
        assertEquals("IMPORTED", source.path("status").asText());
        var spec =
                spec(
                        model(
                                "ref",
                                ref("dataset.knowledge.events"),
                                "grain",
                                Map.of("keys", List.of("event_id"), "kind", "EVENT"),
                                "time",
                                Map.of(
                                        "roles",
                                        List.of("EVENT_TIME"),
                                        "businessZone",
                                        "Asia/Shanghai",
                                        "backfillPossible",
                                        false),
                                "subjectType",
                                ref("entity.knowledge.user"),
                                "dimensions",
                                List.of()));
        spec.put(
                "bindings",
                List.of(
                        Map.of(
                                "sourceSystem",
                                "postgres",
                                "externalRef",
                                "knowledge.events",
                                "fingerprint",
                                source.path("fingerprint").asText(),
                                "timeFields",
                                Map.of("EVENT_TIME", "event_time"))));
        publishSpec("Dataset", spec);
        first = publish("FunctionDefinition", function(FIRST));
        second = publish("FunctionDefinition", function(SECOND));
    }

    @BeforeEach
    void resetRows() {
        db.execute("TRUNCATE TABLE " + TABLE);
        db.execute(
                "INSERT INTO "
                        + TABLE
                        + " VALUES('event_1','user_1','2026-11-12T00:00:00Z','A'),('event_2','user_2','2026-11-12T00:30:00Z','B')");
    }

    @Test
    @Order(1)
    void ambiguousNamesUnknownMissesAndCurrentPrincipalScopeStaySeparate() throws Exception {
        var ambiguous =
                ok(
                        "POST",
                        "/api/knowledge/resolve",
                        CONSUMER,
                        Map.of("text", "设备活动数据", "domain", "knowledge", "intent", "EXTRACT"));
        assertEquals("AMBIGUOUS", ambiguous.path("resolution").asText());
        assertEquals(2, ambiguous.path("candidates").size());
        var sentence =
                ok(
                        "POST",
                        "/api/knowledge/resolve",
                        CONSUMER,
                        Map.of(
                                "text",
                                "请提取设备活动数据并提供结果",
                                "domain",
                                "knowledge",
                                "intent",
                                "EXTRACT"));
        assertEquals("AMBIGUOUS", sentence.path("resolution").asText());
        assertEquals(2, sentence.path("candidates").size());
        var absent =
                ok(
                        "POST",
                        "/api/knowledge/assess",
                        CONSUMER,
                        Map.of("functionId", ABSENT, "intent", "EXTRACT"));
        assertEquals("UNKNOWN", absent.path("supply").asText());
        assertEquals(
                404,
                send(
                                "GET",
                                "/api/knowledge/observations/" + ambiguous.path("id").asText(),
                                OTHER,
                                null)
                        .statusCode());
        assertEquals(
                404,
                send(
                                "POST",
                                "/api/knowledge/resolve",
                                OTHER,
                                Map.of("functionId", FIRST, "intent", "EXTRACT"))
                        .statusCode());
        var exact =
                ok(
                        "POST",
                        "/api/knowledge/resolve",
                        CONSUMER,
                        Map.of(
                                "releaseId",
                                first.path("id").asText(),
                                "contentHash",
                                first.path("content_hash").asText(),
                                "intent",
                                "EXTRACT"));
        assertEquals("RESOLVED", exact.path("resolution").asText());
        var stored =
                ok(
                        "GET",
                        "/api/knowledge/observations/" + exact.path("id").asText(),
                        CONSUMER,
                        null);
        assertEquals(exact.path("indexHash"), stored.path("indexHash"));
        assertEquals(exact.path("candidates"), stored.path("candidates"));
    }

    @Test
    @Order(2)
    void completeCoverageAndAliasesRequireAnIndependentReviewAndPinActualInventory()
            throws Exception {
        var proposed = coverage("1.0.0", "设备活动明细");
        assertEquals(401, send("POST", "/api/knowledge/coverage", CONSUMER, proposed).statusCode());
        var declaration = ok("POST", "/api/knowledge/coverage", AUTHOR, proposed);
        var before =
                ok(
                        "POST",
                        "/api/knowledge/resolve",
                        CONSUMER,
                        Map.of("text", "设备活动明细", "domain", "knowledge", "intent", "EXTRACT"));
        assertNotEquals("RESOLVED", before.path("resolution").asText());
        assertEquals(
                403,
                send(
                                "POST",
                                "/api/knowledge/coverage/"
                                        + declaration.path("id").asText()
                                        + "/approve",
                                AUTHOR,
                                Map.of())
                        .statusCode());
        ok(
                "POST",
                "/api/knowledge/coverage/" + declaration.path("id").asText() + "/approve",
                REVIEWER,
                Map.of());
        var resolved =
                ok(
                        "POST",
                        "/api/knowledge/resolve",
                        CONSUMER,
                        Map.of("text", "设备活动明细", "domain", "knowledge", "intent", "EXTRACT"));
        assertEquals("RESOLVED", resolved.path("resolution").asText());
        assertEquals(
                "REVIEWED_ALIAS", resolved.path("candidates").get(0).path("matchBasis").asText());
        var exactAbsence =
                ok(
                        "POST",
                        "/api/knowledge/assess",
                        CONSUMER,
                        Map.of("functionId", ABSENT, "intent", "EXTRACT"));
        assertEquals("MISSING", exactAbsence.path("supply").asText());
        assertEquals(
                "REVIEWED_REGISTERED_FUNCTION_NAMESPACE",
                exactAbsence.path("assessmentScope").asText());
        var lexicalMiss =
                ok(
                        "POST",
                        "/api/knowledge/assess",
                        CONSUMER,
                        Map.of(
                                "text",
                                "unmodeled arbitrary business question",
                                "domain",
                                "knowledge",
                                "intent",
                                "EXTRACT"));
        assertEquals("UNKNOWN", lexicalMiss.path("supply").asText());
        assertTrue(ok("GET", "/api/knowledge/coverage", OTHER, null).isEmpty());
    }

    @Test
    @Order(3)
    void mcpUsesTheSameActualExecutionAndDoesNotDispatchArbitraryTools() throws Exception {
        var tools =
                ok(
                        "POST",
                        "/api/mcp",
                        CONSUMER,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                1,
                                "method",
                                "tools/list",
                                "params",
                                Map.of()));
        assertFalse(tools.has("error"));
        assertTrue(tools.path("result").path("tools").size() >= 8);
        var unsupported =
                ok(
                        "POST",
                        "/api/mcp",
                        CONSUMER,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                2,
                                "method",
                                "tools/call",
                                "params",
                                Map.of("name", "run_shell", "arguments", Map.of("command", "id"))));
        assertEquals(-32602, unsupported.path("error").path("code").asInt());
        var operation =
                ok(
                        "POST",
                        "/api/mcp",
                        CONSUMER,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                3,
                                "method",
                                "tools/call",
                                "params",
                                Map.of(
                                        "name",
                                        "awe_extract_dataset",
                                        "arguments",
                                        request("mcp-" + UUID.randomUUID()))));
        assertEquals(
                "EXTRACTED",
                operation.path("result").path("structuredContent").path("status").asText());
        assertEquals(
                2,
                operation
                        .path("result")
                        .path("structuredContent")
                        .path("result")
                        .path("rows")
                        .size());
        assertEquals(
                202,
                send(
                                "POST",
                                "/api/mcp",
                                CONSUMER,
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "method",
                                        "notifications/initialized",
                                        "params",
                                        Map.of()))
                        .statusCode());
    }

    @Test
    @Order(4)
    void reviewedReferenceAndActualExternalRepairAreRequiredForTechnicalClosure() throws Exception {
        db.update("UPDATE " + TABLE + " SET user_id='wrong_user' WHERE event_id='event_1'");
        var operation =
                ok("POST", "/api/data-tasks", CONSUMER, request("original-" + UUID.randomUUID()));
        var feedbackRequest =
                Map.of(
                        "observationId",
                        operation.path("id").asText(),
                        "issueCode",
                        "WRONG_RESULT",
                        "stage",
                        "VERIFICATION",
                        "description",
                        "Independent domain reference disagrees with event_1 user");
        var feedback = ok("POST", "/api/feedback", CONSUMER, feedbackRequest);
        assertEquals(
                feedback.path("id"),
                ok("POST", "/api/feedback", CONSUMER, feedbackRequest).path("id"));
        assertEquals(
                404,
                send("GET", "/api/feedback/" + feedback.path("id").asText(), OTHER, null)
                        .statusCode());
        var expected =
                List.of(
                        Map.of(
                                "event_id",
                                "event_1",
                                "user_id",
                                "user_1",
                                "event_time",
                                "2026-11-12T00:00:00Z"),
                        Map.of(
                                "event_id",
                                "event_2",
                                "user_id",
                                "user_2",
                                "event_time",
                                "2026-11-12T00:30:00Z"));
        var reference =
                proposeCase(operation, "RECORDS", json.sha256(json.parse(json.write(expected))));
        assertEquals(
                400,
                send(
                                "POST",
                                "/api/regressions/cases/"
                                        + reference.path("id").asText()
                                        + "/replays",
                                CONSUMER,
                                Map.of("idempotencyKey", "before-review"))
                        .statusCode());
        ok(
                "POST",
                "/api/regressions/cases/" + reference.path("id").asText() + "/approve",
                REVIEWER,
                Map.of());
        var failed =
                ok(
                        "POST",
                        "/api/regressions/cases/" + reference.path("id").asText() + "/replays",
                        CONSUMER,
                        Map.of("idempotencyKey", "before-repair"));
        assertFalse(failed.path("passed").asBoolean());
        ok(
                "POST",
                "/api/feedback/" + feedback.path("id").asText() + "/reviews",
                AUTHOR,
                Map.of(
                        "classification",
                        "DATA_QUALITY",
                        "ownerTeam",
                        "knowledge_team",
                        "externalIssueRef",
                        "warehouse:repair-42",
                        "note",
                        "Domain-owned row repair required; root cause remains scoped"));
        assertEquals(
                400,
                send(
                                "POST",
                                "/api/feedback/" + feedback.path("id").asText() + "/closures",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        failed.path("id").asText(),
                                        "note",
                                        "cannot close failed evidence"))
                        .statusCode());
        db.update("UPDATE " + TABLE + " SET user_id='user_1' WHERE event_id='event_1'");
        var passed =
                ok(
                        "POST",
                        "/api/regressions/cases/" + reference.path("id").asText() + "/replays",
                        CONSUMER,
                        Map.of("idempotencyKey", "after-repair"));
        assertTrue(passed.path("passed").asBoolean(), passed.toString());
        assertEquals(
                passed,
                ok(
                        "POST",
                        "/api/regressions/cases/" + reference.path("id").asText() + "/replays",
                        CONSUMER,
                        Map.of("idempotencyKey", "after-repair")));
        var closed =
                ok(
                        "POST",
                        "/api/feedback/" + feedback.path("id").asText() + "/closures",
                        REVIEWER,
                        Map.of(
                                "replayId",
                                passed.path("id").asText(),
                                "note",
                                "Original exact request now matches the reviewed reference"));
        assertEquals("TECHNICALLY_CLOSED", closed.path("status").asText());
        assertEquals(
                operation.path("resultHash"),
                ok("GET", "/api/data-tasks/" + operation.path("id").asText(), CONSUMER, null)
                        .path("resultHash"));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_regression_replay SET passed=true WHERE id=?::uuid",
                                failed.path("id").asText()));
        db.update("UPDATE " + TABLE + " SET user_id='new_wrong_user' WHERE event_id='event_1'");
        var recurrence =
                ok("POST", "/api/data-tasks", CONSUMER, request("recurrence-" + UUID.randomUUID()));
        var reopened =
                ok(
                        "POST",
                        "/api/feedback",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                recurrence.path("id").asText(),
                                "issueCode",
                                "WRONG_RESULT",
                                "stage",
                                "VERIFICATION",
                                "description",
                                "The same data obligation failed after an earlier repair"));
        assertEquals(feedback.path("id"), reopened.path("id"));
        assertEquals("OPEN", reopened.path("status").asText());
        assertEquals(
                400,
                send(
                                "POST",
                                "/api/feedback/" + feedback.path("id").asText() + "/closures",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        passed.path("id").asText(),
                                        "note",
                                        "Old proof must not close a newer failure"))
                        .statusCode());
    }

    @Test
    @Order(5)
    void resolutionGapCanBeReplayedAfterReviewedKnowledgeProduction() throws Exception {
        String term = "device activity approved synonym";
        var original =
                ok(
                        "POST",
                        "/api/knowledge/resolve",
                        CONSUMER,
                        Map.of("text", term, "domain", "knowledge", "intent", "EXTRACT"));
        assertEquals("UNRESOLVED", original.path("resolution").asText());
        var feedback =
                ok(
                        "POST",
                        "/api/feedback",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                original.path("id").asText(),
                                "issueCode",
                                "KNOWLEDGE_GAP",
                                "stage",
                                "RESOLUTION",
                                "description",
                                "Approved domain alias is missing from published knowledge"));
        String expected =
                json.sha256(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "resolution",
                                                "RESOLVED",
                                                "candidates",
                                                List.of(
                                                        Map.of(
                                                                "releaseId",
                                                                first.path("id").asText(),
                                                                "contentHash",
                                                                first.path("content_hash")
                                                                        .asText()))))));
        var reference = proposeCase(original, "RESOLVED", expected);
        ok(
                "POST",
                "/api/regressions/cases/" + reference.path("id").asText() + "/approve",
                REVIEWER,
                Map.of());
        var declaration = ok("POST", "/api/knowledge/coverage", AUTHOR, coverage("2.0.0", term));
        ok(
                "POST",
                "/api/knowledge/coverage/" + declaration.path("id").asText() + "/approve",
                REVIEWER,
                Map.of());
        var passed =
                ok(
                        "POST",
                        "/api/regressions/cases/" + reference.path("id").asText() + "/replays",
                        CONSUMER,
                        Map.of("idempotencyKey", "alias-fixed"));
        assertTrue(passed.path("passed").asBoolean(), passed.toString());
        ok(
                "POST",
                "/api/feedback/" + feedback.path("id").asText() + "/reviews",
                AUTHOR,
                Map.of(
                        "classification",
                        "RETRIEVAL_GAP",
                        "ownerTeam",
                        "knowledge_team",
                        "note",
                        "A separately reviewed alias was published, not inferred from search score"));
        assertEquals(
                "TECHNICALLY_CLOSED",
                ok(
                                "POST",
                                "/api/feedback/" + feedback.path("id").asText() + "/closures",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        passed.path("id").asText(),
                                        "note",
                                        "Original term resolves to the exact reviewed release"))
                        .path("status")
                        .asText());
    }

    @Test
    @Order(6)
    void aCityScopedReplayCannotCloseNationalFeedbackOrReadRevokedDiagnostics() throws Exception {
        JsonNode metricFunction = publishPopulationMetric();
        var query = metricRequest(metricFunction, "national-" + UUID.randomUUID());
        JsonNode national = ok("POST", "/api/data-tasks", CONSUMER, query);
        assertEquals("QUERIED", national.path("status").asText(), national.toString());
        assertEquals(2, national.path("result").path("rows").get(0).path("value").asInt());
        JsonNode diagnostic =
                ok(
                        "POST",
                        "/api/knowledge/resolve",
                        CONSUMER,
                        Map.of(
                                "releaseId",
                                first.path("id").asText(),
                                "contentHash",
                                first.path("content_hash").asText(),
                                "intent",
                                "EXTRACT"));
        JsonNode globalFeedback =
                ok(
                        "POST",
                        "/api/feedback",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                national.path("id").asText(),
                                "issueCode",
                                "WRONG_RESULT",
                                "stage",
                                "VERIFICATION",
                                "description",
                                "The national population is missing business records from city B"));
        String feedbackId = globalFeedback.path("id").asText();
        ok(
                "POST",
                "/api/feedback/" + feedbackId + "/reviews",
                AUTHOR,
                Map.of(
                        "classification",
                        "DATA_QUALITY",
                        "ownerTeam",
                        "knowledge_team",
                        "note",
                        "A national data issue cannot be closed by checking only city A"));

        var restricted =
                new LinkedHashMap<String, Object>(
                        policy(
                                "knowledge-consumer",
                                CONSUMER,
                                List.of(FIRST, SECOND, ABSENT, METRIC)));
        restricted.put("fields", List.of("value"));
        restricted.put(
                "rowScopes",
                List.of(
                        Map.of(
                                "datasetId",
                                "dataset.knowledge.events",
                                "fields",
                                List.of("event_id", "user_id", "event_time", "city"),
                                "filters",
                                List.of(
                                        Map.of(
                                                "field",
                                                "city",
                                                "operation",
                                                "EQ",
                                                "type",
                                                "STRING",
                                                "values",
                                                List.of("A"))))));
        var settings = new LinkedHashMap<String, Object>();
        settings.put("server.port", "0");
        settings.put("server.address", "127.0.0.1");
        settings.put("awe.auth.author-token", AUTHOR);
        settings.put("awe.auth.reviewer-token", REVIEWER);
        settings.put("AWE_CONSUMER_POLICIES", json.write(List.of(restricted)));
        settings.put("AWE_WAREHOUSE_JDBC_URL", Objects.requireNonNull(System.getenv("AWE_DB_URL")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_USER", Objects.requireNonNull(System.getenv("AWE_DB_USER")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_PASSWORD",
                Objects.requireNonNull(System.getenv("AWE_DB_PASSWORD")));
        settings.put(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                "{\"postgres:knowledge.events\":\"public.awe_knowledge_events\"}");
        settings.put("AWE_HIVE_METADATA_URL", "");
        settings.put("AWE_BI_METADATA_URL", "");
        settings.put("AWE_BI_QUERY_URL", "");
        settings.put("AWE_DATA_READINESS_URL", "");
        settings.put("AWE_METRIC_VERIFICATION_URL", "");
        int originalPort = port;
        try (var scoped =
                new SpringApplicationBuilder(RegistryApplication.class)
                        .registerShutdownHook(false)
                        .initializers(
                                context ->
                                        context.getEnvironment()
                                                .getPropertySources()
                                                .addFirst(
                                                        new MapPropertySource(
                                                                "restricted-city-policy",
                                                                settings)))
                        .run()) {
            port = ((WebServerApplicationContext) scoped).getWebServer().getPort();
            assertEquals(
                    404,
                    send("GET", "/api/data-tasks/" + national.path("id").asText(), CONSUMER, null)
                            .statusCode());
            assertEquals(
                    404,
                    send(
                                    "GET",
                                    "/api/knowledge/observations/" + diagnostic.path("id").asText(),
                                    CONSUMER,
                                    null)
                            .statusCode());
            JsonNode city =
                    ok(
                            "POST",
                            "/api/data-tasks",
                            CONSUMER,
                            metricRequest(metricFunction, "city-" + UUID.randomUUID()));
            assertEquals("QUERIED", city.path("status").asText(), city.toString());
            assertEquals(1, city.path("result").path("rows").get(0).path("value").asInt());
            JsonNode reference =
                    proposeCase(city, "RECORDS", json.sha256(json.parse("[{\"value\":1}]")));
            ok(
                    "POST",
                    "/api/regressions/cases/" + reference.path("id").asText() + "/approve",
                    REVIEWER,
                    Map.of());
            JsonNode replay =
                    ok(
                            "POST",
                            "/api/regressions/cases/" + reference.path("id").asText() + "/replays",
                            CONSUMER,
                            Map.of("idempotencyKey", "city-regression"));
            assertTrue(replay.path("passed").asBoolean(), replay.toString());
            assertEquals(
                    400,
                    send(
                                    "POST",
                                    "/api/feedback/" + feedbackId + "/closures",
                                    REVIEWER,
                                    Map.of(
                                            "replayId",
                                            replay.path("id").asText(),
                                            "note",
                                            "City A passing proof must not close the national population obligation"))
                            .statusCode());
        } finally {
            port = originalPort;
        }
        assertEquals(
                "OPEN",
                ok("GET", "/api/feedback/" + feedbackId, CONSUMER, null).path("status").asText());
    }

    private JsonNode publishPopulationMetric() throws Exception {
        var document =
                spec(
                        model(
                                "ref",
                                ref("metric.knowledge.population"),
                                "grain",
                                Map.of("kind", "AGGREGATE", "keys", List.of()),
                                "time",
                                Map.of(
                                        "roles",
                                        List.of("EVENT_TIME"),
                                        "businessZone",
                                        "Asia/Shanghai",
                                        "backfillPossible",
                                        false),
                                "subjectType",
                                ref("entity.knowledge.user"),
                                "sourceDataset",
                                ref("dataset.knowledge.events"),
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
        document.put("schemaVersion", 3);
        document.put(
                "metricContract",
                model(
                        "statisticalSubject",
                        ref("entity.knowledge.user"),
                        "identityField",
                        "events.user_id",
                        "identityNullPolicy",
                        "IGNORE",
                        "unit",
                        "users",
                        "timeRole",
                        "EVENT_TIME",
                        "businessZone",
                        "Asia/Shanghai",
                        "sources",
                        List.of(Map.of("alias", "events", "readFunction", ref(FIRST))),
                        "plan",
                        Map.of(
                                "kind",
                                "AGGREGATE",
                                "input",
                                Map.of(
                                        "kind",
                                        "SCAN",
                                        "source",
                                        "events",
                                        "fromDayOffset",
                                        0,
                                        "toDayOffset",
                                        0),
                                "groupBy",
                                List.of(),
                                "measures",
                                List.of(
                                        Map.of(
                                                "name",
                                                "value",
                                                "kind",
                                                "COUNT_DISTINCT",
                                                "expression",
                                                Map.of(
                                                        "kind",
                                                        "FIELD",
                                                        "name",
                                                        "events.user_id")))),
                        "outputs",
                        List.of(
                                Map.of(
                                        "name",
                                        "value",
                                        "type",
                                        "INTEGER",
                                        "unit",
                                        "users",
                                        "nullable",
                                        false)),
                        "grain",
                        List.of(),
                        "emptyPolicy",
                        "ZERO",
                        "zeroDivisionPolicy",
                        "NULL",
                        "direct",
                        null));
        publishSpec("Metric", document);
        return publish(
                "FunctionDefinition",
                model(
                        "ref",
                        ref(METRIC),
                        "ownerType",
                        ref("metric.knowledge.population"),
                        "operation",
                        "metric.query.v1",
                        "parameters",
                        List.of(
                                Map.of("name", "from", "type", "TIMESTAMP", "required", true),
                                Map.of("name", "to", "type", "TIMESTAMP", "required", true)),
                        "resultSchemaRef",
                        "metric.knowledge.population@1.0.0",
                        "executorBindingRef",
                        "postgres.metric.query.v1",
                        "requiredAssertions",
                        List.of()));
    }

    private Map<String, Object> metricRequest(JsonNode function, String key) {
        return Map.of(
                "protocolVersion",
                2,
                "intent",
                "QUERY_METRIC",
                "proposedFunction",
                Map.of(
                        "releaseId",
                        function.path("id").asText(),
                        "contentHash",
                        function.path("content_hash").asText()),
                "requirements",
                Map.of(
                        "from",
                        "2026-11-12T00:00:00Z",
                        "to",
                        "2026-11-12T01:00:00Z",
                        "view",
                        "OBSERVED_SNAPSHOT"),
                "consumer",
                Map.of("purpose", "analysis"),
                "constraints",
                Map.of("maxRows", 20, "maxBytes", 4096),
                "idempotencyKey",
                key);
    }

    private JsonNode proposeCase(JsonNode anchor, String state, String expected) throws Exception {
        return ok(
                "POST",
                "/api/regressions/cases",
                CONSUMER,
                Map.of(
                        "observationId",
                        anchor.path("id").asText(),
                        "expectedState",
                        state,
                        "expectedFingerprint",
                        expected,
                        "referenceBasis",
                        "Domain reference independently reviewed before replay; not an automatic numeric-truth claim",
                        "referenceEvidenceRef",
                        "reference:knowledge-domain-case"));
    }

    private Map<String, Object> coverage(String version, String alias) {
        return Map.of(
                "domain",
                "knowledge",
                "version",
                version,
                "ownerTeam",
                "knowledge_team",
                "validUntil",
                Instant.now().plusSeconds(3600).toString(),
                "complete",
                true,
                "operations",
                List.of("EXTRACT"),
                "assetReleases",
                List.of(
                        Map.of(
                                "releaseId",
                                first.path("id").asText(),
                                "contentHash",
                                first.path("content_hash").asText()),
                        Map.of(
                                "releaseId",
                                second.path("id").asText(),
                                "contentHash",
                                second.path("content_hash").asText())),
                "aliases",
                List.of(Map.of("term", alias, "functionReleaseId", first.path("id").asText())),
                "sourceAssets",
                List.of(
                        Map.of(
                                "sourceSystem",
                                "postgres",
                                "externalRef",
                                "knowledge.events",
                                "snapshotId",
                                source.path("snapshotId").asText())),
                "limitations",
                List.of(
                        "Only the reviewed published Function inventory of this domain; enterprise data and unregistered derivations are outside this claim"));
    }

    private Map<String, Object> request(String key) {
        return Map.of(
                "protocolVersion",
                1,
                "intent",
                "EXTRACT",
                "proposedFunction",
                Map.of(
                        "releaseId",
                        first.path("id").asText(),
                        "contentHash",
                        first.path("content_hash").asText()),
                "requirements",
                Map.of(
                        "fields",
                        List.of("event_id", "user_id", "event_time"),
                        "from",
                        "2026-11-12T00:00:00Z",
                        "to",
                        "2026-11-12T01:00:00Z",
                        "view",
                        "OBSERVED_SNAPSHOT"),
                "consumer",
                Map.of("purpose", "analysis"),
                "constraints",
                Map.of("maxRows", 20, "maxBytes", 4096),
                "idempotencyKey",
                key);
    }

    private Map<String, Object> function(String id) {
        return model(
                "ref",
                ref(id),
                "ownerType",
                ref("dataset.knowledge.events"),
                "operation",
                "dataset.extract.v1",
                "parameters",
                List.of(
                        Map.of("name", "from", "type", "TIMESTAMP", "required", true),
                        Map.of("name", "to", "type", "TIMESTAMP", "required", true)),
                "resultSchemaRef",
                "dataset.knowledge.row@1.0.0",
                "executorBindingRef",
                "postgres.dataset.extract.v1",
                "requiredAssertions",
                List.of());
    }

    private Map<String, Object> property(String name, String type) {
        return Map.of("name", name, "type", type, "required", true, "sensitive", false);
    }

    private Map<String, Object> ref(String id) {
        return Map.of("id", id, "modelVersion", "1.0.0");
    }

    private Map<String, Object> model(Object... entries) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i + 1]);
        return m;
    }

    private Map<String, Object> spec(Map<String, Object> model) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        2,
                        "name",
                        "设备活动数据",
                        "definition",
                        "Published extraction for knowledge-loop acceptance",
                        "ownerTeam",
                        "knowledge_team",
                        "model",
                        model));
    }

    private JsonNode publish(String type, Map<String, Object> model) throws Exception {
        return publishSpec(type, spec(model));
    }

    private JsonNode publishSpec(String type, Map<String, Object> spec) throws Exception {
        return publishDocument(type, spec);
    }

    private JsonNode publishDocument(String type, Map<String, Object> spec) throws Exception {
        String entity = (String) ((Map<?, ?>) ((Map<?, ?>) spec.get("model")).get("ref")).get("id");
        var draft =
                ok(
                        "POST",
                        "/api/drafts",
                        AUTHOR,
                        Map.of(
                                "domain",
                                "knowledge",
                                "entityId",
                                entity,
                                "entityType",
                                type,
                                "spec",
                                spec));
        ok("POST", "/api/drafts/" + draft.path("id").asText() + "/submit", AUTHOR, Map.of());
        ok("POST", "/api/drafts/" + draft.path("id").asText() + "/approve", REVIEWER, Map.of());
        return ok(
                "POST",
                "/api/drafts/" + draft.path("id").asText() + "/publish",
                REVIEWER,
                Map.of("version", "1.0.0"));
    }

    private HttpResponse<String> send(String method, String path, String token, Object body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(json.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = send(method, path, token, body);
        assertEquals(200, response.statusCode(), method + " " + path + " " + response.body());
        return json.parse(response.body());
    }
}
