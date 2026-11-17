package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实模型修订、拒绝与就绪观测经过相同消费内核；人工参考不冒充独立业务真值。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=metric-query-author",
            "awe.auth.reviewer-token=metric-query-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class RepairRegressionIntegrationTest extends MetricQueryTestSupport {
    private static final String REPAIR_DOMAIN = "repair";
    private static final String PRINCIPAL = "repair-consumer";
    private static final String READINESS_CAPABILITY = "repair-readiness";
    private static final String READINESS_BINDING = "bi.repair.readiness";
    private static final String BI_SOURCE_PROJECTION = "repair.reference.events.v1";
    private static final String READINESS_SQL =
            "SELECT COUNT(DISTINCT user_id) AS value FROM public.awe_metric_events WHERE event_time>=? AND event_time<? AND user_id IS NOT NULL";
    private static final List<String> FUNCTION_IDS =
            List.of(
                    "function.repair.population",
                    "function.repair.other",
                    "function.repair.readiness");
    private static SqlBackedMetricGateway gateway;
    private JsonNode original,
            corrected,
            changedUnit,
            other,
            readinessFunction,
            crossMetricFunction,
            downgradedMetricFunction;

    private static synchronized SqlBackedMetricGateway gateway() {
        if (gateway == null) {
            gateway = new SqlBackedMetricGateway();
            gateway.register(READINESS_CAPABILITY, READINESS_SQL);
        }
        return gateway;
    }

    @Override
    void beforeWarehouseReset() {
        gateway().clearExecutions();
        gateway().mutateResponse(ignored -> {});
    }

    @AfterAll
    static synchronized void stopGateway() {
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }
    }

    @DynamicPropertySource
    static void configuredRepairDomain(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
        properties.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () -> "{\"postgres:metric.events\":\"public.awe_metric_events\"}");
        properties.add("AWE_HIVE_METADATA_URL", () -> "");
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_BI_QUERY_URL", () -> gateway().baseUrl());
        properties.add("AWE_BI_QUERY_TOKEN", () -> SqlBackedMetricGateway.TOKEN);
        properties.add(
                "AWE_BI_QUERY_BINDINGS",
                () ->
                        new JsonDocuments()
                                .write(
                                        Map.of(
                                                READINESS_BINDING,
                                                Map.of(
                                                        "capabilityRef",
                                                        READINESS_CAPABILITY,
                                                        "implementationVersion",
                                                        "reference-v1",
                                                        "implementationHash",
                                                        SqlBackedMetricGateway.implementationHash(
                                                                READINESS_SQL),
                                                        "sourceProjectionRef",
                                                        BI_SOURCE_PROJECTION))));
        properties.add("AWE_DATA_READINESS_URL", () -> "");
        properties.add("AWE_METRIC_VERIFICATION_URL", () -> "");
        properties.add("AWE_CONSUMER_POLICIES", () -> policies(100));
    }

    private static String policies(int maxRows) {
        var first = new LinkedHashMap<String, Object>();
        first.put("principal", PRINCIPAL);
        first.put("token", CONSUMER);
        first.put("functionIds", FUNCTION_IDS);
        first.put("purposes", List.of(PURPOSE));
        first.put("fields", List.of("event_id", "user_id", "event_time", "channel", "value"));
        first.put("maxRows", maxRows);
        first.put("maxBytes", 16384);
        first.put("maxWindowSeconds", 8 * 86400);
        var second = new LinkedHashMap<>(first);
        second.put("principal", "repair-other-consumer");
        second.put("token", SECOND_CONSUMER);
        return new JsonDocuments().write(List.of(first, second));
    }

    @BeforeAll
    void publishRealOldAndCorrectedModels() throws Exception {
        createDomain(REPAIR_DOMAIN);
        publishEventSource(REPAIR_DOMAIN);
        original = populationVersion("population", "1.0.0", true, "users");
        corrected = populationVersion("population", "2.0.0", false, "users");
        changedUnit = populationVersion("population", "3.0.0", false, "devices");
        populationVersion("other", "1.0.0", true, "users");
        other = populationVersion("other", "2.0.0", false, "users");
        crossMetricFunction = populationImplementation("4.0.0", "other", "2.0.0");
        downgradedMetricFunction = populationImplementation("5.0.0", "population", "1.0.0");
        var readinessMetric =
                publish(
                        "Metric",
                        metricSpec(
                                REPAIR_DOMAIN,
                                "readiness",
                                "COUNT_DISTINCT",
                                "users",
                                aggregate(
                                        excludeUnknownIdentity(scan()),
                                        List.of(),
                                        List.of(
                                                measure(
                                                        "value",
                                                        "COUNT_DISTINCT",
                                                        field("events.user_id")))),
                                List.of(output("value", "INTEGER", "users", false)),
                                List.of(),
                                Map.of(
                                        "bindingRef",
                                        READINESS_BINDING,
                                        "implementationVersion",
                                        "reference-v1",
                                        "implementationHash",
                                        SqlBackedMetricGateway.implementationHash(READINESS_SQL))));
        readinessFunction =
                publish("FunctionDefinition", queryFunctionSpec(REPAIR_DOMAIN, "readiness", true));
        gateway().bindMetric(READINESS_CAPABILITY, readinessMetric);
    }

    private JsonNode populationImplementation(
            String version, String metricName, String metricVersion) throws Exception {
        var function = queryFunctionSpec(REPAIR_DOMAIN, "population", false);
        ((ObjectNode) function.path("model").path("ref")).put("modelVersion", version);
        ((ObjectNode) function.path("model").path("ownerType"))
                .put("id", "metric." + REPAIR_DOMAIN + "." + metricName)
                .put("modelVersion", metricVersion);
        ((ObjectNode) function.path("model"))
                .put(
                        "resultSchemaRef",
                        "metric." + REPAIR_DOMAIN + "." + metricName + "@" + metricVersion);
        return publish("FunctionDefinition", function);
    }

    private JsonNode populationVersion(String name, String version, boolean nativeOnly, String unit)
            throws Exception {
        Object input = excludeUnknownIdentity(scan());
        if (nativeOnly)
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
                                    field("events.channel"),
                                    "right",
                                    Map.of(
                                            "kind", "LITERAL", "type", "STRING", "value",
                                            "native")));
        var metric =
                metricSpec(
                        REPAIR_DOMAIN,
                        name,
                        "COUNT_DISTINCT",
                        unit,
                        aggregate(
                                input,
                                List.of(),
                                List.of(
                                        measure(
                                                "value",
                                                "COUNT_DISTINCT",
                                                field("events.user_id")))),
                        List.of(output("value", "INTEGER", unit, false)),
                        List.of(),
                        null);
        ((ObjectNode) metric.path("model").path("ref")).put("modelVersion", version);
        publish("Metric", metric);
        var function = queryFunctionSpec(REPAIR_DOMAIN, name, false);
        ((ObjectNode) function.path("model").path("ref")).put("modelVersion", version);
        ((ObjectNode) function.path("model").path("ownerType")).put("modelVersion", version);
        ((ObjectNode) function.path("model"))
                .put("resultSchemaRef", "metric." + REPAIR_DOMAIN + "." + name + "@" + version);
        return publish("FunctionDefinition", function);
    }

    private JsonNode execute(JsonNode function) throws Exception {
        return ok("POST", "/api/data-tasks", CONSUMER, metricRequest(function, key()));
    }

    private ObjectNode semanticRequest(JsonNode function) throws Exception {
        JsonNode capability = null;
        for (var candidate : ok("GET", "/api/capabilities", CONSUMER, null))
            if (candidate.path("releaseId").asText().equals(function.path("id").asText()))
                capability = candidate;
        assertNotNull(
                capability, "The current consumer must discover the exact metric implementation");
        var request = metricRequest(function, key());
        var proposed = request.remove("proposedFunction");
        request.put("protocolVersion", 3);
        request.set("candidateFunction", proposed);
        request.set(
                "metric",
                json.parse(
                        json.write(
                                Map.of(
                                        "releaseId",
                                                capability
                                                        .path("metric")
                                                        .path("releaseId")
                                                        .asText(),
                                        "contentHash",
                                                capability
                                                        .path("metric")
                                                        .path("contentHash")
                                                        .asText()))));
        var requirements = (ObjectNode) request.path("requirements");
        requirements.set(
                "statisticalSubject",
                json.parse(
                        json.write(
                                Map.of(
                                        "entityId",
                                                capability
                                                        .path("statisticalSubject")
                                                        .path("entityId")
                                                        .asText(),
                                        "modelVersion",
                                                capability
                                                        .path("statisticalSubject")
                                                        .path("modelVersion")
                                                        .asText()))));
        requirements.put("unit", capability.path("contract").path("unit").asText());
        requirements.put("businessZone", capability.path("contract").path("businessZone").asText());
        requirements.put("timeRole", capability.path("contract").path("timeRole").asText());
        requirements.putArray("measures").add("value");
        requirements.putArray("dimensions");
        requirements.putArray("filters");
        requirements.put("verification", "OBSERVE");
        return request;
    }

    private String independentPopulationFingerprint() {
        // 独立 SQL 从真实源按用户分组；不读取 AWE 被验行来拼参考答案。
        var rows =
                oracle(
                        "SELECT count(*) AS value FROM (SELECT user_id FROM "
                                + EVENTS
                                + " WHERE event_time>=? AND event_time<? AND user_id IS NOT NULL GROUP BY user_id) independent_population");
        assertEquals(3, ((Number) rows.getFirst().get("value")).intValue());
        return json.sha256(json.parse(json.write(rows)));
    }

    private JsonNode reference(JsonNode observation, String fingerprint, String token)
            throws Exception {
        return ok(
                "POST",
                "/api/regressions/cases",
                token,
                Map.of(
                        "observationId",
                        observation.path("id").asText(),
                        "expectedState",
                        "RECORDS",
                        "expectedFingerprint",
                        fingerprint,
                        "referenceBasis",
                        "Independently grouped all authorized users over the exact half-open event window",
                        "referenceEvidenceRef",
                        "reference:repair-domain:population"));
    }

    private JsonNode approveCase(JsonNode reference) throws Exception {
        return ok(
                "POST",
                "/api/regressions/cases/" + reference.path("id").asText() + "/approve",
                REVIEWER,
                Map.of());
    }

    private JsonNode replay(JsonNode reference) throws Exception {
        return ok(
                "POST",
                "/api/regressions/cases/" + reference.path("id").asText() + "/replays",
                CONSUMER,
                Map.of("idempotencyKey", key()));
    }

    private JsonNode reportModelProblem(JsonNode observation) throws Exception {
        var feedback =
                ok(
                        "POST",
                        "/api/feedback",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                observation.path("id").asText(),
                                "issueCode",
                                "WRONG_RESULT",
                                "stage",
                                "VERIFICATION",
                                "description",
                                "The published native-only filter omits authorized external users from the domain population"));
        ok(
                "POST",
                "/api/feedback/" + feedback.path("id").asText() + "/reviews",
                AUTHOR,
                Map.of(
                        "classification",
                        "MODEL_GAP",
                        "ownerTeam",
                        "metric_team",
                        "externalIssueRef",
                        "domain:population-model-revision",
                        "note",
                        "Revise the formal model; do not rewrite the old published release or its failed result"));
        return feedback;
    }

    private Map<String, Object> migrationRequest(JsonNode sourceCase, JsonNode targetCase) {
        return Map.of(
                "fromCaseId",
                sourceCase.path("id").asText(),
                "toCaseId",
                targetCase.path("id").asText(),
                "reason",
                "The higher formal version removes the erroneous native-channel filter for the same business question",
                "referenceEvidenceRef",
                "domain:population-model-revision",
                "idempotencyKey",
                key());
    }

    private String migrationsPath(JsonNode feedback) {
        return "/api/feedback/" + feedback.path("id").asText() + "/migrations";
    }

    @Test
    void semanticRevisionFlowsThroughReviewedReplacementAndFreshActualReplay() throws Exception {
        String expected = independentPopulationFingerprint();
        var oldOperation = ok("POST", "/api/data-tasks", CONSUMER, semanticRequest(original));
        var newOperation = ok("POST", "/api/data-tasks", CONSUMER, semanticRequest(corrected));
        assertEquals("QUERIED", oldOperation.path("status").asText(), oldOperation.toString());
        assertEquals("QUERIED", newOperation.path("status").asText(), newOperation.toString());
        assertEquals(2, oldOperation.path("result").path("rows").get(0).path("value").asInt());
        assertEquals(3, newOperation.path("result").path("rows").get(0).path("value").asInt());
        var issue = reportModelProblem(oldOperation);
        var oldCase = reference(oldOperation, expected, CONSUMER);
        var newCase = reference(newOperation, expected, CONSUMER);
        approveCase(oldCase);
        approveCase(newCase);
        var failed = replay(oldCase);
        assertFalse(failed.path("passed").asBoolean(), failed.toString());
        var early = replay(newCase);
        assertTrue(early.path("passed").asBoolean(), early.toString());
        var migration =
                ok("POST", migrationsPath(issue), CONSUMER, migrationRequest(oldCase, newCase));
        var comparison = migration.path("proposal").path("comparison");
        assertFalse(comparison.path("request").has("candidateFunction"));
        assertFalse(comparison.path("request").has("metric"));
        assertEquals(
                oldOperation.path("request").path("requirements"),
                comparison.path("request").path("requirements"));
        assertEquals(
                "metric.repair.population",
                comparison.path("oldModelsAndBindings").path("metric").path("entityId").asText());
        assertEquals(
                "metric.repair.population",
                comparison.path("newModelsAndBindings").path("metric").path("entityId").asText());
        String path = migrationsPath(issue) + "/" + migration.path("id").asText();
        ok(
                "POST",
                path + "/approve",
                REVIEWER,
                Map.of("migrationHash", migration.path("migrationHash").asText()));
        assertEquals(
                400,
                send(
                                "POST",
                                path + "/resolve",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        early.path("id").asText(),
                                        "note",
                                        "Pre-approval replay is not a replacement acceptance"))
                        .statusCode());
        var fresh = replay(newCase);
        assertTrue(fresh.path("passed").asBoolean(), fresh.toString());
        var resolved =
                ok(
                        "POST",
                        path + "/resolve",
                        REVIEWER,
                        Map.of(
                                "replayId",
                                fresh.path("id").asText(),
                                "note",
                                "The exact v3 business requirements pass the independent reference after the approved Metric revision"));
        assertEquals(
                "RESOLVED_BY_REPLACEMENT", resolved.path("status").asText(), resolved.toString());
        var preserved =
                ok("GET", "/api/data-tasks/" + oldOperation.path("id").asText(), CONSUMER, null);
        assertEquals(oldOperation.path("request"), preserved.path("request"));
        assertEquals(2, preserved.path("result").path("rows").get(0).path("value").asInt());
        assertFalse(
                ok("GET", "/api/regressions/replays/" + failed.path("id").asText(), CONSUMER, null)
                        .path("passed")
                        .asBoolean());
    }

    @Test
    void semanticMigrationRejectsMetricSwapDowngradeRequestChangesAndProtocolChanges()
            throws Exception {
        String expected = independentPopulationFingerprint();
        var oldOperation = ok("POST", "/api/data-tasks", CONSUMER, semanticRequest(original));
        var issue = reportModelProblem(oldOperation);
        var oldCase = reference(oldOperation, expected, CONSUMER);
        approveCase(oldCase);
        var changedTime = semanticRequest(corrected);
        ((ObjectNode) changedTime.path("requirements")).put("from", "2026-08-18T01:00:00Z");
        var normalNew = ok("POST", "/api/data-tasks", CONSUMER, semanticRequest(corrected));
        var swapped = ok("POST", "/api/data-tasks", CONSUMER, semanticRequest(crossMetricFunction));
        assertEquals(
                oldOperation.path("resolved").path("function").path("entityId"),
                swapped.path("resolved").path("function").path("entityId"));
        assertNotEquals(
                oldOperation.path("resolved").path("metric").path("entityId"),
                swapped.path("resolved").path("metric").path("entityId"));
        var variants =
                List.of(
                        reference(swapped, expected, CONSUMER),
                        reference(
                                ok("POST", "/api/data-tasks", CONSUMER, changedTime),
                                expected,
                                CONSUMER),
                        reference(
                                ok(
                                        "POST",
                                        "/api/data-tasks",
                                        CONSUMER,
                                        semanticRequest(changedUnit)),
                                expected,
                                CONSUMER),
                        reference(
                                normalNew, json.sha256(json.parse("[{\"value\":999}]")), CONSUMER),
                        reference(execute(corrected), expected, CONSUMER));
        for (var candidate : variants) {
            approveCase(candidate);
            assertEquals(
                    400,
                    send(
                                    "POST",
                                    migrationsPath(issue),
                                    CONSUMER,
                                    migrationRequest(oldCase, candidate))
                            .statusCode(),
                    "A newer Function cannot swap the formal Metric, business request, answer, unit or protocol");
        }
        var currentIssue = reportModelProblem(normalNew);
        var currentCase = reference(normalNew, expected, CONSUMER);
        var downgrade =
                ok("POST", "/api/data-tasks", CONSUMER, semanticRequest(downgradedMetricFunction));
        assertEquals(
                "5.0.0", downgrade.path("resolved").path("function").path("modelVersion").asText());
        assertEquals(
                "1.0.0", downgrade.path("resolved").path("metric").path("modelVersion").asText());
        var downgradedCase = reference(downgrade, expected, CONSUMER);
        approveCase(currentCase);
        approveCase(downgradedCase);
        assertEquals(
                400,
                send(
                                "POST",
                                migrationsPath(currentIssue),
                                CONSUMER,
                                migrationRequest(currentCase, downgradedCase))
                        .statusCode(),
                "A higher Function version cannot hide a Metric downgrade");
    }

    @Test
    void reviewedRevisionAndFreshReplayValidateReplacementWithoutRewritingOldFailure()
            throws Exception {
        String expected = independentPopulationFingerprint();
        var oldOperation = execute(original);
        assertEquals("QUERIED", oldOperation.path("status").asText(), oldOperation.toString());
        assertEquals(2, oldOperation.path("result").path("rows").get(0).path("value").asInt());
        var feedback = reportModelProblem(oldOperation);
        var oldCase = reference(oldOperation, expected, CONSUMER);
        approveCase(oldCase);
        var failed = replay(oldCase);
        assertFalse(failed.path("passed").asBoolean(), failed.toString());

        var newOperation = execute(corrected);
        assertEquals(3, newOperation.path("result").path("rows").get(0).path("value").asInt());
        var newCase = reference(newOperation, expected, CONSUMER);
        approveCase(newCase);
        var premature = replay(newCase);
        assertTrue(premature.path("passed").asBoolean(), premature.toString());
        assertEquals(
                400,
                send(
                                "POST",
                                "/api/feedback/" + feedback.path("id").asText() + "/closures",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        premature.path("id").asText(),
                                        "note",
                                        "A different model must not bypass the old exact-scope closure"))
                        .statusCode());

        var request = migrationRequest(oldCase, newCase);
        var migration = ok("POST", migrationsPath(feedback), CONSUMER, request);
        assertEquals(
                migration.path("id"),
                ok("POST", migrationsPath(feedback), CONSUMER, request).path("id"));
        String path = migrationsPath(feedback) + "/" + migration.path("id").asText();
        String migrationId = migration.path("id").asText();
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_obligation_migration SET proposal_doc='{}'::jsonb WHERE id=?::uuid",
                                migrationId));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_obligation_migration WHERE id=?::uuid",
                                migrationId));

        assertEquals(404, send("GET", path, SECOND_CONSUMER, null).statusCode());
        var review = ok("GET", path + "/review", REVIEWER, null);
        assertEquals(migration.path("migrationHash"), review.path("migrationHash"));
        var comparison = review.path("proposal").path("comparison");
        assertTrue(comparison.path("oldExecutionBinding").isObject());
        assertTrue(comparison.path("newExecutionBinding").isObject());
        assertFalse(comparison.path("oldPhysicalProjections").isEmpty());
        assertFalse(comparison.path("newPhysicalProjections").isEmpty());
        assertEquals(migration.path("proposal").path("comparison"), comparison);
        assertEquals(
                "OPEN",
                ok("GET", "/api/feedback/" + feedback.path("id").asText(), CONSUMER, null)
                        .path("status")
                        .asText());
        assertEquals(
                403,
                send(
                                "POST",
                                path + "/approve",
                                AUTHOR,
                                Map.of("migrationHash", migration.path("migrationHash").asText()))
                        .statusCode());
        assertTrue(
                send(
                                        "POST",
                                        path + "/resolve",
                                        REVIEWER,
                                        Map.of(
                                                "replayId",
                                                premature.path("id").asText(),
                                                "note",
                                                "Unapproved migration cannot resolve feedback"))
                                .statusCode()
                        >= 400);
        assertTrue(
                send("POST", path + "/approve", REVIEWER, Map.of("migrationHash", "0".repeat(64)))
                                .statusCode()
                        >= 400);
        ok(
                "POST",
                path + "/approve",
                REVIEWER,
                Map.of("migrationHash", migration.path("migrationHash").asText()));
        assertEquals(
                "OPEN",
                ok("GET", "/api/feedback/" + feedback.path("id").asText(), CONSUMER, null)
                        .path("status")
                        .asText());
        assertEquals(
                400,
                send(
                                "POST",
                                path + "/resolve",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        failed.path("id").asText(),
                                        "note",
                                        "Old failed replay is not replacement evidence"))
                        .statusCode());
        assertEquals(
                400,
                send(
                                "POST",
                                path + "/resolve",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        premature.path("id").asText(),
                                        "note",
                                        "A replay preceding migration approval cannot certify its acceptance"))
                        .statusCode());

        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_obligation_migration_approval SET migration_hash=? WHERE migration_id=?::uuid",
                                "0".repeat(64),
                                migrationId));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_obligation_migration_approval WHERE migration_id=?::uuid",
                                migrationId));

        var passed = replay(newCase);
        assertTrue(passed.path("passed").asBoolean(), passed.toString());
        var resolution =
                ok(
                        "POST",
                        path + "/resolve",
                        REVIEWER,
                        Map.of(
                                "replayId",
                                passed.path("id").asText(),
                                "note",
                                "The reviewed replacement now satisfies the unchanged independent reference"));
        assertEquals(
                "RESOLVED_BY_REPLACEMENT",
                resolution.path("status").asText(),
                resolution.toString());
        assertEquals(
                "RESOLVED_BY_REPLACEMENT",
                ok("GET", "/api/feedback/" + feedback.path("id").asText(), CONSUMER, null)
                        .path("status")
                        .asText());
        assertEquals(
                2,
                ok("GET", "/api/data-tasks/" + oldOperation.path("id").asText(), CONSUMER, null)
                        .path("result")
                        .path("rows")
                        .get(0)
                        .path("value")
                        .asInt());
        assertFalse(
                ok("GET", "/api/regressions/replays/" + failed.path("id").asText(), CONSUMER, null)
                        .path("passed")
                        .asBoolean());
        assertEquals(
                oldCase.path("case_hash"),
                ok("GET", "/api/regressions/cases/" + oldCase.path("id").asText(), CONSUMER, null)
                        .path("case_hash"));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_regression_replay SET passed=true WHERE id=?::uuid",
                                failed.path("id").asText()));

        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_obligation_migration_resolution SET note='forged resolution' WHERE migration_id=?::uuid",
                                migrationId));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_obligation_migration_resolution WHERE migration_id=?::uuid",
                                migrationId));

        var recurrence = execute(original);
        var reopened = reportModelProblem(recurrence);
        assertEquals(feedback.path("id"), reopened.path("id"));
        assertEquals("OPEN", reopened.path("status").asText());
        var historicalMigration = ok("GET", path, CONSUMER, null);
        assertEquals(
                "REOPENED",
                historicalMigration.path("status").asText(),
                historicalMigration.toString());
        assertEquals("OPEN", historicalMigration.path("feedbackStatus").asText());
        assertFalse(historicalMigration.path("resolutionCurrent").asBoolean());

        assertEquals(
                400,
                send(
                                "POST",
                                path + "/resolve",
                                REVIEWER,
                                Map.of(
                                        "replayId",
                                        passed.path("id").asText(),
                                        "note",
                                        "A prior replacement proof cannot certify a new recurrence"))
                        .statusCode());
    }

    @Test
    void migrationRejectsChangedScopeDifferentFunctionChangedSignatureAndWeakenedReference()
            throws Exception {
        String expected = independentPopulationFingerprint();
        var oldOperation = execute(original);
        var feedback = reportModelProblem(oldOperation);
        var oldCase = reference(oldOperation, expected, CONSUMER);
        approveCase(oldCase);
        var changedRequest = metricRequest(corrected, key());
        ((ObjectNode) changedRequest.path("requirements")).put("from", "2026-08-18T01:00:00Z");
        var changedWindow = ok("POST", "/api/data-tasks", CONSUMER, changedRequest);
        var normalNew = execute(corrected);
        var variants =
                List.of(
                        reference(changedWindow, expected, CONSUMER),
                        reference(execute(other), expected, CONSUMER),
                        reference(execute(changedUnit), expected, CONSUMER),
                        reference(
                                normalNew, json.sha256(json.parse("[{\"value\":999}]")), CONSUMER),
                        reference(oldOperation, expected, CONSUMER));
        for (JsonNode candidate : variants) {
            approveCase(candidate);
            assertTrue(
                    send(
                                            "POST",
                                            migrationsPath(feedback),
                                            CONSUMER,
                                            migrationRequest(oldCase, candidate))
                                    .statusCode()
                            >= 400,
                    "Migration must preserve business request, Function identity, signature, revision and original reference: "
                            + candidate);
        }
        var unapproved = reference(normalNew, expected, CONSUMER);
        assertEquals(
                400,
                send(
                                "POST",
                                migrationsPath(feedback),
                                CONSUMER,
                                migrationRequest(oldCase, unapproved))
                        .statusCode());
        var foreignOperation =
                ok("POST", "/api/data-tasks", SECOND_CONSUMER, metricRequest(corrected, key()));
        var foreignCase = reference(foreignOperation, expected, SECOND_CONSUMER);
        approveCase(foreignCase);
        assertEquals(
                404,
                send(
                                "POST",
                                migrationsPath(feedback),
                                CONSUMER,
                                migrationRequest(oldCase, foreignCase))
                        .statusCode());
        var unapprovedOld = reference(oldOperation, expected, CONSUMER);
        var approvedNew = reference(normalNew, expected, CONSUMER);
        approveCase(approvedNew);
        assertEquals(
                400,
                send(
                                "POST",
                                migrationsPath(feedback),
                                CONSUMER,
                                migrationRequest(unapprovedOld, approvedNew))
                        .statusCode());
    }

    @Test
    void concurrentDifferentFeedbackProposalsCannotReuseOnePrincipalKeyAsTwoMigrations()
            throws Exception {
        String expected = independentPopulationFingerprint();
        var firstIssue = reportModelProblem(execute(original));
        var firstOldCase = reference(execute(original), expected, CONSUMER);
        approveCase(firstOldCase);
        var firstNewCase = reference(execute(corrected), expected, CONSUMER);
        approveCase(firstNewCase);
        var secondOldRequest = metricRequest(original, key());
        ((ObjectNode) secondOldRequest.path("constraints")).put("maxRows", 99);
        var secondOld = ok("POST", "/api/data-tasks", CONSUMER, secondOldRequest);
        var secondIssue = reportModelProblem(secondOld);
        assertNotEquals(firstIssue.path("id"), secondIssue.path("id"));
        var secondOldCase = reference(secondOld, expected, CONSUMER);
        approveCase(secondOldCase);
        var secondNewRequest = metricRequest(corrected, key());
        ((ObjectNode) secondNewRequest.path("constraints")).put("maxRows", 99);
        var secondNewCase =
                reference(
                        ok("POST", "/api/data-tasks", CONSUMER, secondNewRequest),
                        expected,
                        CONSUMER);
        approveCase(secondNewCase);
        String sharedKey = key();
        var firstProposal = new LinkedHashMap<>(migrationRequest(firstOldCase, firstNewCase));
        var secondProposal = new LinkedHashMap<>(migrationRequest(secondOldCase, secondNewCase));
        firstProposal.put("idempotencyKey", sharedKey);
        secondProposal.put("idempotencyKey", sharedKey);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a =
                    workers.submit(
                            () -> {
                                start.await();
                                return send(
                                        "POST",
                                        migrationsPath(firstIssue),
                                        CONSUMER,
                                        firstProposal);
                            });
            var b =
                    workers.submit(
                            () -> {
                                start.await();
                                return send(
                                        "POST",
                                        migrationsPath(secondIssue),
                                        CONSUMER,
                                        secondProposal);
                            });
            start.countDown();
            var responseA = a.get(40, TimeUnit.SECONDS);
            var responseB = b.get(40, TimeUnit.SECONDS);
            assertEquals(
                    List.of(200, 409),
                    List.of(responseA.statusCode(), responseB.statusCode()).stream()
                            .sorted()
                            .toList(),
                    responseA.body() + " / " + responseB.body());
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM awe_obligation_migration WHERE principal=? AND idempotency_key=?",
                            Integer.class,
                            PRINCIPAL,
                            sharedKey));
        } finally {
            workers.shutdownNow();
        }
    }

    private JsonNode probe(ObjectNode request, String key) throws Exception {
        return ok(
                "POST",
                "/api/regressions/observations",
                CONSUMER,
                Map.of("request", request, "idempotencyKey", key));
    }

    private ObjectNode outcomeExpectation(JsonNode observation) {
        return ((ObjectNode) observation.path("outcome").deepCopy()).put("type", "DATA_OUTCOME");
    }

    private JsonNode outcomeCase(JsonNode observation, ObjectNode expected) throws Exception {
        return ok(
                "POST",
                "/api/regressions/cases",
                CONSUMER,
                Map.of(
                        "observationId",
                        observation.path("id").asText(),
                        "expectation",
                        expected,
                        "referenceBasis",
                        "Reviewed refusal/readiness expectation over the exact request and policy",
                        "referenceEvidenceRef",
                        "reference:repair-domain:outcome"));
    }

    @Test
    void actualAuthorizationDenialIsObservableAndReplayedWithoutDisclosingHiddenMetadata()
            throws Exception {
        var request = metricRequest(corrected, key());
        ((ObjectNode) request.path("consumer")).put("purpose", "not-an-authorized-purpose");
        assertEquals(403, send("POST", "/api/data-tasks", CONSUMER, request).statusCode());
        String probeKey = key();
        var observation = probe(request, probeKey);
        assertEquals("DENIED", observation.path("status").asText(), observation.toString());
        assertEquals("DENIED", observation.path("outcome").path("status").asText());
        assertEquals(403, observation.path("outcome").path("httpStatus").asInt());
        assertEquals(
                "DENIED",
                observation.path("outcome").path("decision").path("authorization").asText());
        assertTrue(observation.path("outcome").path("noDataReturned").asBoolean());
        assertTrue(
                observation.path("resolved").isMissingNode()
                        || observation.path("resolved").isEmpty());
        assertFalse(observation.toString().contains("private-one"));
        assertFalse(observation.toString().contains(CONSUMER));
        assertEquals(observation.path("id"), probe(request, probeKey).path("id"));
        String path = "/api/regressions/observations/" + observation.path("id").asText();
        assertEquals(observation.path("id"), ok("GET", path, CONSUMER, null).path("id"));
        assertEquals(404, send("GET", path, SECOND_CONSUMER, null).statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM awe_metric_task WHERE principal=? AND request_doc->'consumer'->>'purpose'=?",
                        Integer.class,
                        PRINCIPAL,
                        "not-an-authorized-purpose"));
        String observationId = observation.path("id").asText();
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_regression_observation SET report='{}'::jsonb WHERE id=?::uuid",
                                observationId));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_regression_observation WHERE id=?::uuid",
                                observationId));
        var reference = outcomeCase(observation, outcomeExpectation(observation));
        approveCase(reference);
        String referenceId = reference.path("id").asText();
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_regression_expectation SET expectation='{}'::jsonb WHERE case_id=?::uuid",
                                referenceId));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_regression_expectation WHERE case_id=?::uuid",
                                referenceId));
        var replay = replay(reference);
        assertTrue(replay.path("passed").asBoolean(), replay.toString());
    }

    @Test
    void missingCapabilityIsNotDisclosedAndOutcomeCannotClaimADataSuccess() throws Exception {
        var hidden = metricRequest(corrected, key());
        ((ObjectNode) hidden.path("proposedFunction"))
                .put("releaseId", UUID.randomUUID().toString())
                .put("contentHash", "0".repeat(64));
        assertEquals(404, send("POST", "/api/data-tasks", CONSUMER, hidden).statusCode());
        var declined = probe(hidden, key());
        assertEquals("NOT_DISCLOSED", declined.path("status").asText(), declined.toString());
        assertEquals(404, declined.path("outcome").path("httpStatus").asInt());
        assertTrue(
                declined.path("resolved").isMissingNode() || declined.path("resolved").isEmpty());
        var reference = outcomeCase(declined, outcomeExpectation(declined));
        approveCase(reference);
        assertTrue(replay(reference).path("passed").asBoolean());
        var successful = probe(metricRequest(corrected, key()), key());
        assertEquals("QUERIED", successful.path("status").asText(), successful.toString());
        assertEquals(
                400,
                send(
                                "POST",
                                "/api/regressions/cases",
                                CONSUMER,
                                Map.of(
                                        "observationId",
                                        successful.path("id").asText(),
                                        "expectation",
                                        outcomeExpectation(successful),
                                        "referenceBasis",
                                        "A successful result cannot be recast as a correct refusal",
                                        "referenceEvidenceRef",
                                        "reference:invalid-outcome"))
                        .statusCode());
    }

    @Test
    void expectedMissingReadinessIsVerifiedButCannotCloseADataRepair() throws Exception {
        var request = metricRequest(corrected, key());
        ((ObjectNode) request.path("requirements")).put("view", "PERIOD_COMPLETE");
        var observation = probe(request, key());
        assertEquals("NOT_CONFIGURED", observation.path("status").asText(), observation.toString());
        assertTrue(observation.path("outcome").path("noDataReturned").asBoolean());
        assertFalse(observation.path("outcome").path("failureStage").asText().isBlank());
        var reference = outcomeCase(observation, outcomeExpectation(observation));
        approveCase(reference);
        var passed = replay(reference);
        assertTrue(passed.path("passed").asBoolean(), passed.toString());
        var wrong = outcomeExpectation(observation);
        wrong.put("status", "UNAVAILABLE");
        var wrongReference = outcomeCase(observation, wrong);
        approveCase(wrongReference);
        assertFalse(replay(wrongReference).path("passed").asBoolean());
        var feedback =
                ok(
                        "POST",
                        "/api/feedback",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                observation.path("id").asText(),
                                "issueCode",
                                "WRONG_RESULT",
                                "stage",
                                "READINESS",
                                "description",
                                "The underlying data/readiness issue is still unresolved"));
        ok(
                "POST",
                "/api/feedback/" + feedback.path("id").asText() + "/reviews",
                AUTHOR,
                Map.of(
                        "classification",
                        "DATA_QUALITY",
                        "ownerTeam",
                        "metric_team",
                        "externalIssueRef",
                        "warehouse:still-unready",
                        "note",
                        "A correct NOT_CONFIGURED response does not repair the warehouse"));
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
                                        "Refusal must not substitute for repaired data"))
                        .statusCode());
        var mixed =
                json.object(
                        json.write(
                                Map.of(
                                        "observationId",
                                        observation.path("id").asText(),
                                        "expectation",
                                        outcomeExpectation(observation),
                                        "expectedState",
                                        "NO_RECORD",
                                        "expectedFingerprint",
                                        "0".repeat(64),
                                        "referenceBasis",
                                        "Conflicting old/new expectation encodings must be rejected",
                                        "referenceEvidenceRef",
                                        "reference:invalid-mixed")));
        assertEquals(400, send("POST", "/api/regressions/cases", CONSUMER, mixed).statusCode());
    }

    @Test
    void actualSourceNotReadyPassesItsReviewedOutcomeAndFailsWhenTheSourceBecomesReady()
            throws Exception {
        db.update(
                "INSERT INTO "
                        + SqlBackedMetricGateway.READINESS
                        + " VALUES(?,'NOT_READY',?,?,clock_timestamp())",
                READINESS_CAPABILITY,
                Timestamp.from(Instant.parse(FROM)),
                Timestamp.from(Instant.parse(TO)));
        var request = metricRequest(readinessFunction, key());
        ((ObjectNode) request.path("requirements")).put("view", "PERIOD_COMPLETE");
        int queriesBefore = gateway().actualQueries();
        var observation = probe(request, key());
        assertEquals("NOT_READY", observation.path("status").asText(), observation.toString());
        assertEquals(
                "NOT_READY",
                observation.path("outcome").path("decision").path("readiness").asText());
        assertTrue(observation.path("outcome").path("noDataReturned").asBoolean());
        assertEquals(
                queriesBefore,
                gateway().actualQueries(),
                "not-ready source must not execute the metric or invent a zero");
        var reference = outcomeCase(observation, outcomeExpectation(observation));
        approveCase(reference);
        var correctRefusal = replay(reference);
        assertTrue(correctRefusal.path("passed").asBoolean(), correctRefusal.toString());
        db.update(
                "UPDATE "
                        + SqlBackedMetricGateway.READINESS
                        + " SET state='READY',updated_at=clock_timestamp() WHERE capability_ref=?",
                READINESS_CAPABILITY);
        var changedOutcome = replay(reference);
        assertFalse(changedOutcome.path("passed").asBoolean(), changedOutcome.toString());
        assertEquals(queriesBefore + 1, gateway().actualQueries());
    }

    @Test
    void verifiedBiProjectionAllowsFreshSnapshotDataReplaysButWrongProjectionCannotPass()
            throws Exception {
        String expected = independentPopulationFingerprint();
        var original = execute(readinessFunction);
        assertEquals("QUERIED", original.path("status").asText(), original.toString());
        assertEquals(
                BI_SOURCE_PROJECTION,
                original.path("executionEvidence").path("sourceProjectionRef").asText());
        String originalSnapshot = original.path("snapshots").path("bi").asText();
        assertFalse(originalSnapshot.isBlank());
        var reference =
                ok(
                        "POST",
                        "/api/regressions/cases",
                        CONSUMER,
                        Map.of(
                                "observationId",
                                original.path("id").asText(),
                                "expectation",
                                Map.of(
                                        "type",
                                        "DATA_RESULT",
                                        "state",
                                        "RECORDS",
                                        "fingerprint",
                                        expected),
                                "referenceBasis",
                                "Independent SQL over all authorized users; changing transaction snapshots share the verified registered source projection",
                                "referenceEvidenceRef",
                                "reference:repair-domain:bi-projection"));
        approveCase(reference);
        var first = replay(reference);
        var second = replay(reference);
        assertTrue(first.path("passed").asBoolean(), first.toString());
        assertTrue(second.path("passed").asBoolean(), second.toString());
        String firstSnapshot =
                first.path("proof").path("actualEvidence").path("snapshots").path("bi").asText();
        String secondSnapshot =
                second.path("proof").path("actualEvidence").path("snapshots").path("bi").asText();
        assertFalse(firstSnapshot.isBlank());
        assertNotEquals(
                originalSnapshot,
                firstSnapshot,
                "the replay must really execute a new source transaction");
        assertNotEquals(firstSnapshot, secondSnapshot, "stable projection is not snapshot reuse");
        try {
            gateway()
                    .mutateResponse(
                            response ->
                                    ((ObjectNode) response.path("readiness"))
                                            .put("sourceProjectionRef", "wrong.source.projection"));
            var blocked = execute(readinessFunction);
            assertEquals("BLOCKED", blocked.path("status").asText(), blocked.toString());
            assertTrue(blocked.path("result").path("rows").isEmpty());
            var rejectedReplay = replay(reference);
            assertFalse(rejectedReplay.path("passed").asBoolean(), rejectedReplay.toString());
        } finally {
            gateway().mutateResponse(ignored -> {});
        }
    }

    @Test
    void observationIdempotencyRejectsDifferentRequestsAndDoesNotExposeRows() throws Exception {
        var request = metricRequest(corrected, key());
        String key = key();
        var observation = probe(request, key);
        assertEquals("QUERIED", observation.path("status").asText(), observation.toString());
        assertTrue(
                observation.path("result").path("rows").isMissingNode()
                        || observation.path("result").path("rows").isEmpty());
        assertEquals(observation.path("id"), probe(request, key).path("id"));
        var changed = request.deepCopy();
        ((ObjectNode) changed.path("constraints")).put("maxRows", 99);
        assertEquals(
                409,
                send(
                                "POST",
                                "/api/regressions/observations",
                                CONSUMER,
                                Map.of("request", changed, "idempotencyKey", key))
                        .statusCode());
    }

    @Test
    void withdrawnPolicyHidesRecordedOutcomeAndItsReferenceCase() throws Exception {
        var request = metricRequest(corrected, key());
        ((ObjectNode) request.path("requirements")).put("view", "PERIOD_COMPLETE");
        var observation = probe(request, key());
        var reference = outcomeCase(observation, outcomeExpectation(observation));
        approveCase(reference);
        int originalPort = port;
        var settings = new LinkedHashMap<String, Object>();
        settings.put("server.port", "0");
        settings.put("server.address", "127.0.0.1");
        settings.put("awe.auth.author-token", AUTHOR);
        settings.put("awe.auth.reviewer-token", REVIEWER);
        settings.put("AWE_CONSUMER_POLICIES", policies(99));
        settings.put("AWE_WAREHOUSE_JDBC_URL", Objects.requireNonNull(System.getenv("AWE_DB_URL")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_USER", Objects.requireNonNull(System.getenv("AWE_DB_USER")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_PASSWORD",
                Objects.requireNonNull(System.getenv("AWE_DB_PASSWORD")));
        settings.put(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                "{\"postgres:metric.events\":\"public.awe_metric_events\"}");
        settings.put("AWE_HIVE_METADATA_URL", "");
        settings.put("AWE_BI_METADATA_URL", "");
        settings.put("AWE_BI_QUERY_URL", "");
        settings.put("AWE_DATA_READINESS_URL", "");
        settings.put("AWE_METRIC_VERIFICATION_URL", "");
        try (var restricted =
                new SpringApplicationBuilder(RegistryApplication.class)
                        .registerShutdownHook(false)
                        .initializers(
                                context ->
                                        context.getEnvironment()
                                                .getPropertySources()
                                                .addFirst(
                                                        new MapPropertySource(
                                                                "revoked-repair-policy", settings)))
                        .run()) {
            port = ((WebServerApplicationContext) restricted).getWebServer().getPort();
            assertEquals(
                    404,
                    send(
                                    "GET",
                                    "/api/regressions/observations/"
                                            + observation.path("id").asText(),
                                    CONSUMER,
                                    null)
                            .statusCode());
            assertEquals(
                    404,
                    send(
                                    "GET",
                                    "/api/regressions/cases/" + reference.path("id").asText(),
                                    CONSUMER,
                                    null)
                            .statusCode());
        } finally {
            port = originalPort;
        }
    }
}
