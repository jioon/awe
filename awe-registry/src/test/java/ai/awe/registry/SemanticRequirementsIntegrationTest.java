package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.knowledge.FeedbackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 结构化需求必须由服务端重新规划，并在真实数据库上按原始统计身份去重。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=metric-query-author",
            "awe.auth.reviewer-token=metric-query-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class SemanticRequirementsIntegrationTest extends MetricQueryTestSupport {
    private static final String SEMANTIC_DOMAIN = "semantic_requirements";
    private static final List<String> FUNCTIONS =
            List.of("city_app", "ambiguous", "ambiguous_alt", "composite");
    private final Map<String, JsonNode> metrics = new HashMap<>();
    private final Map<String, JsonNode> functions = new HashMap<>();
    @Autowired FeedbackService feedbackService;

    @DynamicPropertySource
    static void configuredNativeQuery(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
        properties.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () ->
                        "{\"postgres:metric.events\":\"public.awe_metric_events\",\"postgres:metric.users\":\"public.awe_metric_users\"}");
        properties.add("AWE_CONSUMER_POLICIES", SemanticRequirementsIntegrationTest::policies);
        properties.add("AWE_HIVE_METADATA_URL", () -> "");
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_BI_QUERY_URL", () -> "");
        properties.add("AWE_DATA_READINESS_URL", () -> "");
        properties.add("AWE_METRIC_VERIFICATION_URL", () -> "");
        properties.add("AWE_WAREHOUSE_MAX_ROWS", () -> "64");
    }

    private static String policies() {
        var policy =
                Map.of(
                        "principal",
                        "semantic-consumer",
                        "token",
                        CONSUMER,
                        "functionIds",
                        FUNCTIONS.stream()
                                .map(name -> "function." + SEMANTIC_DOMAIN + "." + name)
                                .toList(),
                        "purposes",
                        List.of(PURPOSE),
                        "fields",
                        List.of(
                                "event_id",
                                "user_id",
                                "event_time",
                                "channel",
                                "private_value",
                                "amount",
                                "value",
                                "city",
                                "app"),
                        "maxRows",
                        100,
                        "maxBytes",
                        16384,
                        "maxWindowSeconds",
                        8 * 86400);
        var second = new LinkedHashMap<String, Object>(policy);
        second.put("principal", "semantic-second-consumer");
        second.put("token", SECOND_CONSUMER);
        return new ai.awe.registry.support.JsonDocuments().write(List.of(policy, second));
    }

    @BeforeAll
    void publishFormalRequirementsSources() throws Exception {
        createDomain(SEMANTIC_DOMAIN);
        publishEventSource(SEMANTIC_DOMAIN);
        registerGroupedMetric("city_app");
        registerGroupedMetric("ambiguous");
        var alternative = queryFunctionSpec(SEMANTIC_DOMAIN, "ambiguous_alt", false);
        ((ObjectNode) alternative.path("model"))
                .set(
                        "ownerType",
                        json.parse(json.write(ref("metric." + SEMANTIC_DOMAIN + ".ambiguous"))));
        ((ObjectNode) alternative.path("model"))
                .put("resultSchemaRef", "metric." + SEMANTIC_DOMAIN + ".ambiguous@1.0.0");
        functions.put("ambiguous_alt", publish("FunctionDefinition", alternative));
        registerGroupedMetric("hidden");

        Object aggregate =
                aggregate(
                        fixedInput(),
                        List.of("city", "app"),
                        List.of(measure("value", "COUNT_DISTINCT", field("user_id"))));
        Object composite =
                Map.of(
                        "kind",
                        "FILTER",
                        "input",
                        aggregate,
                        "predicate",
                        Map.of(
                                "kind",
                                "COMPARE",
                                "operation",
                                "GT",
                                "left",
                                field("value"),
                                "right",
                                Map.of(
                                        "kind", "LITERAL",
                                        "type", "INTEGER",
                                        "value", "1")));
        metrics.put(
                "composite",
                publish(
                        "Metric",
                        metricSpec(
                                SEMANTIC_DOMAIN,
                                "composite",
                                "COUNT_DISTINCT",
                                "users",
                                composite,
                                List.of(
                                        output("city", "STRING", "city", false),
                                        output("app", "STRING", "app", true),
                                        output("value", "INTEGER", "users", false)),
                                List.of("city", "app"),
                                null)));
        functions.put(
                "composite",
                publish(
                        "FunctionDefinition",
                        queryFunctionSpec(SEMANTIC_DOMAIN, "composite", false)));
    }

    private void registerGroupedMetric(String name) throws Exception {
        metrics.put(
                name,
                publish(
                        "Metric",
                        metricSpec(
                                SEMANTIC_DOMAIN,
                                name,
                                "COUNT_DISTINCT",
                                "users",
                                aggregate(
                                        fixedInput(),
                                        List.of("city", "app"),
                                        List.of(
                                                measure(
                                                        "value",
                                                        "COUNT_DISTINCT",
                                                        field("user_id")))),
                                List.of(
                                        output("city", "STRING", "city", false),
                                        output("app", "STRING", "app", true),
                                        output("value", "INTEGER", "users", false)),
                                List.of("city", "app"),
                                null)));
        functions.put(
                name,
                publish("FunctionDefinition", queryFunctionSpec(SEMANTIC_DOMAIN, name, false)));
    }

    private Object fixedInput() {
        Object businessFiltered =
                Map.of(
                        "kind", "FILTER",
                        "input", excludeUnknownIdentity(scan()),
                        "predicate",
                                Map.of(
                                        "kind",
                                        "COMPARE",
                                        "operation",
                                        "NE",
                                        "left",
                                        field("events.channel"),
                                        "right",
                                        Map.of(
                                                "kind", "LITERAL",
                                                "type", "STRING",
                                                "value", "blocked")));
        return Map.of(
                "kind",
                "PROJECT",
                "input",
                businessFiltered,
                "columns",
                List.of(
                        Map.of("name", "city", "expression", field("events.channel")),
                        Map.of("name", "app", "expression", field("events.private_value")),
                        Map.of("name", "user_id", "expression", field("events.user_id"))));
    }

    @BeforeEach
    void restoreRequirementRows() {
        db.execute("TRUNCATE TABLE " + EVENTS);
        db.execute(
                "INSERT INTO "
                        + EVENTS
                        + " VALUES"
                        + "('a1','u1','2026-08-18T00:00:00Z','A',1,'app1'),"
                        + "('a2','u1','2026-08-18T01:00:00Z','A',1,'app2'),"
                        + "('a3','u2','2026-08-18T02:00:00Z','A',1,'app1'),"
                        + "('b1','u3','2026-08-18T03:00:00Z','B',1,'app1'),"
                        + "('b2','u4','2026-08-18T04:00:00Z','B',1,'app2'),"
                        + "('null',NULL,'2026-08-18T05:00:00Z','A',1,'app1'),"
                        + "('excluded','u5','2026-08-18T06:00:00Z','blocked',1,'app1'),"
                        + "('upper','u9','2026-08-19T00:00:00Z','A',1,'app1')");
    }

    private ObjectNode requirements(String name, List<String> dimensions) {
        var request = new LinkedHashMap<String, Object>();
        request.put("protocolVersion", 3);
        request.put("intent", "QUERY_METRIC");
        request.put("metric", pointer(metrics.get(name)));
        request.put(
                "requirements",
                model(
                        "statisticalSubject",
                        Map.of(
                                "entityId",
                                "entity." + SEMANTIC_DOMAIN + ".user",
                                "modelVersion",
                                "1.0.0"),
                        "unit",
                        "users",
                        "businessZone",
                        "Asia/Shanghai",
                        "timeRole",
                        "EVENT_TIME",
                        "measures",
                        List.of("value"),
                        "dimensions",
                        dimensions,
                        "filters",
                        List.of(),
                        "from",
                        FROM,
                        "to",
                        TO,
                        "view",
                        "OBSERVED_SNAPSHOT",
                        "verification",
                        "OBSERVE"));
        request.put("consumer", Map.of("purpose", PURPOSE));
        request.put("constraints", Map.of("maxRows", 100, "maxBytes", 16384));
        request.put("idempotencyKey", key());
        return json.object(json.write(request));
    }

    private Map<String, Object> pointer(JsonNode release) {
        return Map.of(
                "releaseId", release.path("id").asText(),
                "contentHash", release.path("content_hash").asText());
    }

    private JsonNode assess(ObjectNode request) throws Exception {
        return ok("POST", "/api/requirements/assess", CONSUMER, request);
    }

    private JsonNode execute(ObjectNode request) throws Exception {
        return ok("POST", "/api/data-tasks", CONSUMER, request);
    }

    private JsonNode independentCities() {
        // 独立 SQL 保留业务过滤与半开账期，不使用 AWE 结果拼装参考。
        return json.parse(
                json.write(
                        oracle(
                                "SELECT channel AS city,count(DISTINCT user_id) AS value FROM "
                                        + EVENTS
                                        + " WHERE event_time>=? AND event_time<? AND user_id IS NOT NULL AND channel<>'blocked' GROUP BY channel ORDER BY channel")));
    }

    private SortedMap<String, Long> cityValues(JsonNode rows) {
        var values = new TreeMap<String, Long>();
        for (var row : rows) values.put(row.path("city").asText(), row.path("value").asLong());
        return values;
    }

    @Test
    void regroupingRecomputesDistinctIdentityAndKeepsPublishedBusinessFilters() throws Exception {
        var request = requirements("city_app", List.of("city"));
        var planned = assess(request);
        assertEquals("PLANNED", planned.path("status").asText(), planned.toString());
        assertTrue(planned.path("resolved").path("planHash").asText().matches("[a-f0-9]{64}"));
        assertEquals(
                json.parse("[\"city\"]"), planned.path("resolved").path("contract").path("grain"));
        assertEquals("UNKNOWN", planned.path("readiness").asText("UNKNOWN"));
        var operation = execute(request);
        assertEquals("QUERIED", operation.path("status").asText(), operation.toString());
        var expected = cityValues(independentCities());
        assertEquals(Map.of("A", 2L, "B", 2L), expected);
        assertEquals(expected, cityValues(operation.path("result").path("rows")));
        assertEquals(
                3L,
                oracle(
                                                "SELECT sum(n) AS value FROM (SELECT count(DISTINCT user_id) AS n FROM "
                                                        + EVENTS
                                                        + " WHERE event_time>=? AND event_time<? AND channel='A' GROUP BY private_value) fine_grain")
                                        .getFirst()
                                        .get("value")
                                instanceof Number value
                        ? value.longValue()
                        : -1);
        assertEquals(3, operation.path("request").path("protocolVersion").asInt());
        assertEquals(request.path("metric"), operation.path("request").path("metric"));
        assertEquals(request.path("requirements"), operation.path("request").path("requirements"));
        assertFalse(operation.path("result").path("rows").toString().contains("blocked"));
    }

    @Test
    void dimensionFiltersApplyBeforeAggregationWithoutChangingTheMetricDefinition()
            throws Exception {
        var request = requirements("city_app", List.of("city"));
        ((ObjectNode) request.path("requirements"))
                .set(
                        "filters",
                        json.parse(
                                "[{\"field\":\"app\",\"operation\":\"EQ\",\"type\":\"STRING\",\"value\":\"app2\"}]"));
        var operation = execute(request);
        assertEquals("QUERIED", operation.path("status").asText(), operation.toString());
        assertEquals(Map.of("A", 1L, "B", 1L), cityValues(operation.path("result").path("rows")));
        var expected =
                oracle(
                        "SELECT channel AS city,count(DISTINCT user_id) AS value FROM "
                                + EVENTS
                                + " WHERE event_time>=? AND event_time<? AND user_id IS NOT NULL AND channel<>'blocked' AND private_value='app2' GROUP BY channel");
        assertEquals(
                cityValues(json.parse(json.write(expected))),
                cityValues(operation.path("result").path("rows")));
    }

    @Test
    void semanticMismatchesAndUnsupportedPlanShapesNeverBecomeSuccessfulPlans() throws Exception {
        var baseline = requirements("city_app", List.of("city"));
        for (var change :
                List.of(
                        Map.entry("unit", json.parse("\"accounts\"")),
                        Map.entry("businessZone", json.parse("\"UTC\"")),
                        Map.entry("timeRole", json.parse("\"PROCESSING_TIME\"")),
                        Map.entry("dimensions", json.parse("[\"unregistered_dimension\"]")),
                        Map.entry("measures", json.parse("[\"other_measure\"]")),
                        Map.entry(
                                "statisticalSubject",
                                json.parse(
                                        "{\"entityId\":\"entity.semantic_requirements.account\",\"modelVersion\":\"1.0.0\"}")))) {
            var request = baseline.deepCopy();
            ((ObjectNode) request.path("requirements")).set(change.getKey(), change.getValue());
            var response = assess(request);
            assertEquals("UNSUPPORTED", response.path("status").asText(), response.toString());
            assertFalse(response.has("resolved"), response.toString());
            assertEquals(400, send("POST", "/api/data-tasks", CONSUMER, request).statusCode());
        }
        var composite = assess(requirements("composite", List.of("city")));
        assertEquals("UNSUPPORTED", composite.path("status").asText(), composite.toString());
    }

    @Test
    void ambiguousImplementationsNeedAnExplicitVisibleCandidateAndHiddenAssetsStayHidden()
            throws Exception {
        var request = requirements("ambiguous", List.of("city"));
        var ambiguous = assess(request);
        assertEquals("AMBIGUOUS", ambiguous.path("status").asText(), ambiguous.toString());
        assertEquals(2, ambiguous.path("candidates").size());
        request.set(
                "candidateFunction",
                json.parse(json.write(pointer(functions.get("ambiguous_alt")))));
        var planned = assess(request);
        assertEquals("PLANNED", planned.path("status").asText(), planned.toString());
        assertEquals(
                functions.get("ambiguous_alt").path("id"),
                planned.path("resolved").path("function").path("releaseId"));
        assertEquals("QUERIED", execute(request).path("status").asText());

        var hidden = assess(requirements("hidden", List.of("city")));
        assertEquals("NOT_DISCLOSED", hidden.path("status").asText(), hidden.toString());
        assertFalse(hidden.toString().contains(functions.get("hidden").path("id").asText()));
        assertFalse(hidden.toString().contains("function." + SEMANTIC_DOMAIN + ".hidden"));
        assertFalse(hidden.has("resolved"));
        var wrongHash = requirements("city_app", List.of("city"));
        ((ObjectNode) wrongHash.path("metric")).put("contentHash", "0".repeat(64));
        assertEquals(
                400, send("POST", "/api/requirements/assess", CONSUMER, wrongHash).statusCode());
    }

    @Test
    void callerSqlIrAndPermissionAssertionsAreRejectedBeforeAnyOperationIsAccepted()
            throws Exception {
        long before = db.queryForObject("SELECT count(*) FROM awe_metric_task", Long.class);
        for (String field : List.of("sql", "plan", "resolved", "policyHash")) {
            var request = requirements("city_app", List.of("city"));
            request.put(field, "caller-supplied");
            assertEquals(
                    400, send("POST", "/api/requirements/assess", CONSUMER, request).statusCode());
            assertEquals(400, send("POST", "/api/data-tasks", CONSUMER, request).statusCode());
        }
        var permission = requirements("city_app", List.of("city"));
        ((ObjectNode) permission.path("consumer")).put("administrator", true);
        assertEquals(400, send("POST", "/api/data-tasks", CONSUMER, permission).statusCode());
        var badFilter = requirements("city_app", List.of("city"));
        ((ObjectNode) badFilter.path("requirements"))
                .set(
                        "filters",
                        json.parse(
                                "[{\"field\":\"city\",\"operation\":\"EQ\",\"type\":\"STRING\",\"value\":\"A\",\"sql\":\"1=1\"}]"));
        assertEquals(400, send("POST", "/api/data-tasks", CONSUMER, badFilter).statusCode());
        assertEquals(before, db.queryForObject("SELECT count(*) FROM awe_metric_task", Long.class));
    }

    @Test
    void actualAuthorizationBudgetsAndReadinessGatesStillApplyToDerivedPlans() throws Exception {
        var overBudget = requirements("city_app", List.of("city"));
        ((ObjectNode) overBudget.path("constraints")).put("maxRows", 101);
        assertEquals(403, send("POST", "/api/data-tasks", CONSUMER, overBudget).statusCode());
        var wrongPurpose = requirements("city_app", List.of("city"));
        ((ObjectNode) wrongPurpose.path("consumer")).put("purpose", "unapproved-purpose");
        assertEquals(403, send("POST", "/api/data-tasks", CONSUMER, wrongPurpose).statusCode());

        var complete = requirements("city_app", List.of("city"));
        ((ObjectNode) complete.path("requirements")).put("view", "PERIOD_COMPLETE");
        assertEquals("PLANNED", assess(complete).path("status").asText());
        var notConfigured = execute(complete);
        assertEquals(
                "NOT_CONFIGURED", notConfigured.path("status").asText(), notConfigured.toString());
        assertFalse(notConfigured.path("result").path("payloadAvailable").asBoolean());
        assertTrue(
                notConfigured.path("result").path("rows").isMissingNode()
                        || notConfigured.path("result").path("rows").isEmpty());
        var independent = requirements("city_app", List.of("city"));
        ((ObjectNode) independent.path("requirements")).put("verification", "REQUIRE_INDEPENDENT");
        assertEquals("NOT_CONFIGURED", execute(independent).path("status").asText());
    }

    @Test
    void acceptedSemanticRequestIsImmutableAndItsIdempotencyIncludesTheDerivedRequirements()
            throws Exception {
        var request = requirements("city_app", List.of("city"));
        var operation = execute(request);
        assertEquals(operation.path("id"), execute(request).path("id"));
        var changed = request.deepCopy();
        ((ObjectNode) changed.path("requirements"))
                .set(
                        "filters",
                        json.parse(
                                "[{\"field\":\"city\",\"operation\":\"EQ\",\"type\":\"STRING\",\"value\":\"A\"}]"));
        assertEquals(409, send("POST", "/api/data-tasks", CONSUMER, changed).statusCode());
        var loaded = ok("GET", "/api/data-tasks/" + operation.path("id").asText(), CONSUMER, null);
        assertEquals(operation.path("request"), loaded.path("request"));
        assertEquals(operation.path("resolved"), loaded.path("resolved"));
        assertEquals(
                404,
                send(
                                "GET",
                                "/api/data-tasks/" + operation.path("id").asText(),
                                SECOND_CONSUMER,
                                null)
                        .statusCode());
        String id = operation.path("id").asText();
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_metric_task SET request_doc='{}'::jsonb WHERE id=?::uuid",
                                id));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_metric_task SET resolved='{}'::jsonb WHERE id=?::uuid",
                                id));
    }

    @Test
    void mcpAndHttpUseTheSameRequirementsPlannerAndActualExecution() throws Exception {
        var request = requirements("city_app", List.of("city"));
        var response =
                ok(
                        "POST",
                        "/api/mcp",
                        CONSUMER,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                "semantic-plan",
                                "method",
                                "tools/call",
                                "params",
                                Map.of("name", "awe_plan_requirements", "arguments", request)));
        assertFalse(response.has("error"), response.toString());
        var planned = response.path("result").path("structuredContent");
        assertEquals("PLANNED", planned.path("status").asText(), response.toString());
        assertEquals(assess(request).path("resolved"), planned.path("resolved"));
        var queried =
                ok(
                        "POST",
                        "/api/mcp",
                        CONSUMER,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                "semantic-execute",
                                "method",
                                "tools/call",
                                "params",
                                Map.of("name", "awe_query_metric", "arguments", request)));
        assertFalse(queried.has("error"), queried.toString());
        var operation = queried.path("result").path("structuredContent");
        assertEquals("QUERIED", operation.path("status").asText(), queried.toString());
        assertEquals(Map.of("A", 2L, "B", 2L), cityValues(operation.path("result").path("rows")));
    }

    @Test
    void reviewedDataReferenceReplaysTheOriginalSemanticRequirements() throws Exception {
        var request = requirements("city_app", List.of("city"));
        var operation = execute(request);
        String expectedFingerprint = json.sha256(independentCities());
        var reference =
                ok(
                        "POST",
                        "/api/regressions/cases",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                operation.path("id").asText(),
                                "expectation",
                                Map.of(
                                        "type",
                                        "DATA_RESULT",
                                        "state",
                                        "RECORDS",
                                        "fingerprint",
                                        expectedFingerprint),
                                "referenceBasis",
                                "Independently count distinct identities by city over the formal business filter and exact event window",
                                "referenceEvidenceRef",
                                "reference:semantic-requirements:city-distinct"));
        ok(
                "POST",
                "/api/regressions/cases/" + reference.path("id").asText() + "/approve",
                REVIEWER,
                Map.of());
        var replay =
                ok(
                        "POST",
                        "/api/regressions/cases/" + reference.path("id").asText() + "/replays",
                        CONSUMER,
                        Map.of("idempotencyKey", key()));
        assertTrue(replay.path("passed").asBoolean(), replay.toString());
        var probe =
                ok(
                        "GET",
                        "/api/regressions/observations/"
                                + replay.path("actual_observation_id").asText(),
                        CONSUMER,
                        null);
        var reexecuted =
                ok(
                        "GET",
                        "/api/data-tasks/" + probe.path("actualOperationId").asText(),
                        CONSUMER,
                        null);
        assertEquals(request.path("requirements"), reexecuted.path("request").path("requirements"));
        assertEquals(operation.path("resolved"), reexecuted.path("resolved"));
    }

    @Test
    void assessmentEvidenceIsImmutableScopedAndReusableForReviewedResolutionRegression()
            throws Exception {
        var request = requirements("city_app", List.of("city"));
        var planned = assess(request);
        String id = planned.path("id").asText();
        var loaded = ok("GET", "/api/requirements/assessments/" + id, CONSUMER, null);
        assertEquals(planned, loaded);
        assertEquals(
                404,
                send("GET", "/api/requirements/assessments/" + id, SECOND_CONSUMER, null)
                        .statusCode());
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_resolution_observation SET report='{}'::jsonb WHERE id=?::uuid",
                                id));
        assertThrows(
                DataAccessException.class,
                () -> db.update("DELETE FROM awe_resolution_observation WHERE id=?::uuid", id));
        var feedback =
                ok(
                        "POST",
                        "/api/feedback",
                        CONSUMER,
                        Map.of(
                                "observationId", id,
                                "issueCode", "VALIDATION_REQUEST",
                                "stage", "RESOLUTION",
                                "description",
                                        "Caller requests review of the planned capability; this report does not assert root cause"));
        assertEquals(id, feedback.path("observation_id").asText());
        // 独立期望来自已经发布的具名实现，不从本次候选结果复制候选真值。
        String expectedFingerprint =
                json.sha256(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "resolution", "RESOLVED",
                                                "candidates",
                                                        List.of(pointer(functions.get("city_app"))),
                                                "status", "PLANNED",
                                                "decision",
                                                        Map.of(
                                                                "resolution", "RESOLVED",
                                                                "authorization", "ALLOWED",
                                                                "supply", "DERIVABLE",
                                                                "readiness", "UNKNOWN"),
                                                "catalogComplete", true,
                                                "diagnostics", List.of(),
                                                "planHash",
                                                        planned.path("resolved")
                                                                .path("planHash")
                                                                .asText()))));
        var reference =
                ok(
                        "POST",
                        "/api/regressions/cases",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                id,
                                "expectation",
                                Map.of(
                                        "type", "RESOLUTION",
                                        "state", "RESOLVED",
                                        "fingerprint", expectedFingerprint),
                                "referenceBasis",
                                "The published city/app metric has exactly one independently identified allowed implementation",
                                "referenceEvidenceRef",
                                "reference:semantic-requirements:formal-implementation"));
        String path = "/api/regressions/cases/" + reference.path("id").asText();
        ok("POST", path + "/approve", REVIEWER, Map.of());
        var replay = ok("POST", path + "/replays", CONSUMER, Map.of("idempotencyKey", key()));
        assertTrue(replay.path("passed").asBoolean(), replay.toString());
        var actual =
                ok(
                        "GET",
                        "/api/requirements/assessments/"
                                + replay.path("actual_observation_id").asText(),
                        CONSUMER,
                        null);
        assertEquals(planned.path("request"), actual.path("request"));
        assertEquals(planned.path("resolved"), actual.path("resolved"));
    }

    @Test
    void v3ResolutionFingerprintIncludesCapabilityDecisionAndPlanWhileLegacyStaysCompatible()
            throws Exception {
        var planned = (ObjectNode) assess(requirements("city_app", List.of("city")));
        String reference = feedbackService.resolutionFingerprint(planned);
        // 这里只验证正式参考投影；改写后的 JSON 从不写入真实观测或冒充执行证据。
        var changedStatus = planned.deepCopy();
        changedStatus.put("status", "UNSUPPORTED");
        assertNotEquals(reference, feedbackService.resolutionFingerprint(changedStatus));
        var changedDecision = planned.deepCopy();
        ((ObjectNode) changedDecision.path("decision")).put("supply", "DIRECT");
        assertNotEquals(reference, feedbackService.resolutionFingerprint(changedDecision));
        var changedCoverage = planned.deepCopy();
        changedCoverage.put("catalogComplete", false);
        assertNotEquals(reference, feedbackService.resolutionFingerprint(changedCoverage));
        var changedReason = planned.deepCopy();
        changedReason.set(
                "diagnostics", json.parse("[{\"reason\":\"CANDIDATE_BUDGET_EXCEEDED\"}]"));
        assertNotEquals(reference, feedbackService.resolutionFingerprint(changedReason));
        var changedPlan = planned.deepCopy();
        ((ObjectNode) changedPlan.path("resolved")).put("planHash", "0".repeat(64));
        assertNotEquals(reference, feedbackService.resolutionFingerprint(changedPlan));

        var hidden = (ObjectNode) assess(requirements("hidden", List.of("city")));
        var unsupported = hidden.deepCopy();
        unsupported.put("status", "UNSUPPORTED");
        assertEquals(hidden.path("resolution"), unsupported.path("resolution"));
        assertEquals(hidden.path("candidates"), unsupported.path("candidates"));
        assertNotEquals(
                feedbackService.resolutionFingerprint(hidden),
                feedbackService.resolutionFingerprint(unsupported));

        var legacy = planned.deepCopy();
        ((ObjectNode) legacy.path("request")).put("protocolVersion", 2);
        String legacyReference = feedbackService.resolutionFingerprint(legacy);
        legacy.put("status", "UNSUPPORTED");
        legacy.put("catalogComplete", false);
        assertEquals(legacyReference, feedbackService.resolutionFingerprint(legacy));
        assertEquals(
                json.sha256(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "resolution",
                                                "RESOLVED",
                                                "candidates",
                                                List.of(pointer(functions.get("city_app"))))))),
                legacyReference);
    }
}
