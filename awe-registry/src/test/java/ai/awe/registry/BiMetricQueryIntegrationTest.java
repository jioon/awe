package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Configured BI protocol reads real source rows and source readiness, without claiming enterprise
 * deployment.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=metric-query-author",
            "awe.auth.reviewer-token=metric-query-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class BiMetricQueryIntegrationTest extends MetricQueryTestSupport {
    private static final String BI_DOMAIN = "metric_bi";
    private static final String BINDING = "bi.reference.active_users";
    private static final String CAPABILITY = "active_users";
    private static final String QUERY =
            "SELECT COUNT(DISTINCT user_id) AS value FROM public.awe_metric_events WHERE event_time>=? AND event_time<? AND user_id IS NOT NULL";
    private static final String REFERENCE_QUERY =
            "SELECT COUNT(*) AS value FROM (SELECT user_id FROM public.awe_metric_events WHERE event_time>=? AND event_time<? AND user_id IS NOT NULL GROUP BY user_id) independently_grouped_users";
    private static final String ROLLING_BINDING = "bi.reference.rolling_users";
    private static final String ROLLING_CAPABILITY = "rolling_users";
    private static final String ROLLING_QUERY =
            "SELECT COUNT(DISTINCT user_id) AS value FROM public.awe_metric_events WHERE event_time>=((?::timestamptz AT TIME ZONE 'Asia/Shanghai')-INTERVAL '6 days') AT TIME ZONE 'Asia/Shanghai' AND event_time<? AND user_id IS NOT NULL";
    private static SqlBackedMetricGateway gateway;
    private JsonNode metric, function, rollingFunction;

    private static synchronized SqlBackedMetricGateway gateway() {
        if (gateway == null) {
            gateway = new SqlBackedMetricGateway();
            gateway.register(CAPABILITY, QUERY, REFERENCE_QUERY);
            gateway.register(ROLLING_CAPABILITY, ROLLING_QUERY);
        }
        return gateway;
    }

    @DynamicPropertySource
    static void realBiProtocol(DynamicPropertyRegistry properties) {
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
        properties.add("AWE_METRIC_VERIFICATION_URL", () -> gateway().baseUrl());
        properties.add("AWE_METRIC_VERIFICATION_TOKEN", () -> SqlBackedMetricGateway.TOKEN);
        properties.add(
                "AWE_METRIC_VERIFICATION_CHECKERS",
                () ->
                        new ai.awe.registry.support.JsonDocuments()
                                .write(
                                        Map.of(
                                                "checker.reference.independent_sql",
                                                Map.of(
                                                        "implementationVersion",
                                                        "reference-v1",
                                                        "implementationHash",
                                                        SqlBackedMetricGateway.implementationHash(
                                                                REFERENCE_QUERY)))));
        properties.add("AWE_DATA_READINESS_URL", () -> "");
        properties.add(
                "AWE_BI_QUERY_BINDINGS",
                () ->
                        new ai.awe.registry.support.JsonDocuments()
                                .write(
                                        Map.of(
                                                BINDING,
                                                Map.of(
                                                        "capabilityRef",
                                                        CAPABILITY,
                                                        "implementationVersion",
                                                        "reference-v1",
                                                        "implementationHash",
                                                        SqlBackedMetricGateway.implementationHash(
                                                                QUERY)),
                                                ROLLING_BINDING,
                                                Map.of(
                                                        "capabilityRef",
                                                        ROLLING_CAPABILITY,
                                                        "implementationVersion",
                                                        "reference-v1",
                                                        "implementationHash",
                                                        SqlBackedMetricGateway.implementationHash(
                                                                ROLLING_QUERY)))));
        properties.add(
                "AWE_CONSUMER_POLICIES",
                () ->
                        new ai.awe.registry.support.JsonDocuments()
                                .write(
                                        List.of(
                                                Map.of(
                                                        "principal",
                                                        "metric-bi-consumer",
                                                        "token",
                                                        CONSUMER,
                                                        "functionIds",
                                                        List.of(
                                                                "function." + BI_DOMAIN + ".dau",
                                                                "function."
                                                                        + BI_DOMAIN
                                                                        + ".rolling"),
                                                        "purposes",
                                                        List.of(PURPOSE),
                                                        "fields",
                                                        List.of(
                                                                "event_id",
                                                                "user_id",
                                                                "event_time",
                                                                "value"),
                                                        "maxRows",
                                                        100,
                                                        "maxBytes",
                                                        16384,
                                                        "maxWindowSeconds",
                                                        8 * 86400))));
    }

    @BeforeAll
    void publishDirectMetric() throws Exception {
        createDomain(BI_DOMAIN);
        publishEventSource(BI_DOMAIN);
        var plan =
                aggregate(
                        excludeUnknownIdentity(scan()),
                        List.of(),
                        List.of(measure("value", "COUNT_DISTINCT", field("events.user_id"))));
        metric =
                publish(
                        "Metric",
                        metricSpec(
                                BI_DOMAIN,
                                "dau",
                                "COUNT_DISTINCT",
                                "users",
                                plan,
                                List.of(output("value", "INTEGER", "users", false)),
                                List.of(),
                                Map.of(
                                        "bindingRef",
                                        BINDING,
                                        "implementationVersion",
                                        "reference-v1",
                                        "implementationHash",
                                        SqlBackedMetricGateway.implementationHash(QUERY))));
        function = publish("FunctionDefinition", queryFunctionSpec(BI_DOMAIN, "dau", true));
        gateway().bindMetric(CAPABILITY, metric);
        Object rollingPlan =
                aggregate(
                        excludeUnknownIdentity(
                                Map.of(
                                        "kind",
                                        "SCAN",
                                        "source",
                                        "events",
                                        "fromDayOffset",
                                        -6,
                                        "toDayOffset",
                                        0)),
                        List.of(),
                        List.of(measure("value", "COUNT_DISTINCT", field("events.user_id"))));
        JsonNode rolling =
                publish(
                        "Metric",
                        metricSpec(
                                BI_DOMAIN,
                                "rolling",
                                "COUNT_DISTINCT",
                                "users",
                                rollingPlan,
                                List.of(output("value", "INTEGER", "users", false)),
                                List.of(),
                                Map.of(
                                        "bindingRef",
                                        ROLLING_BINDING,
                                        "implementationVersion",
                                        "reference-v1",
                                        "implementationHash",
                                        SqlBackedMetricGateway.implementationHash(ROLLING_QUERY))));
        rollingFunction =
                publish("FunctionDefinition", queryFunctionSpec(BI_DOMAIN, "rolling", true));
        gateway().bindMetric(ROLLING_CAPABILITY, rolling);
    }

    @BeforeEach
    void actualSourceRunIsComplete() {
        gateway().mutateResponse(ignored -> {});
        gateway().mutateVerification(ignored -> {});
        gateway().delay(0);
        gateway().clearExecutions();
        db.update(
                "INSERT INTO "
                        + SqlBackedMetricGateway.READINESS
                        + " VALUES(?,'READY',?,?,clock_timestamp())",
                CAPABILITY,
                Timestamp.from(Instant.parse(FROM)),
                Timestamp.from(Instant.parse(TO)));
        db.update(
                "INSERT INTO "
                        + SqlBackedMetricGateway.READINESS
                        + " VALUES(?,'READY',?,?,clock_timestamp())",
                ROLLING_CAPABILITY,
                Timestamp.from(Instant.parse(FROM)),
                Timestamp.from(Instant.parse(TO)));
    }

    @Override
    void beforeWarehouseReset() {
        gateway().clearExecutions();
    }

    @AfterAll
    static synchronized void stopBiFixture() {
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }
    }

    private ObjectNode completeRequest() {
        ObjectNode request = metricRequest(function, key());
        ((ObjectNode) request.path("requirements")).put("view", "PERIOD_COMPLETE");
        return request;
    }

    @Test
    void
            directMetricReturnsActualCountWithWindowBoundReadinessAndIndependentSnapshotReconciliation()
                    throws Exception {
        int before = gateway().actualQueries();
        int referenceBefore = gateway().referenceQueries();
        JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, completeRequest());
        assertEquals("QUERIED", operation.path("status").asText(), operation.toString());
        assertEquals(before + 1, gateway().actualQueries());
        assertEquals("READY", operation.path("decision").path("readiness").asText());
        assertEquals("DIRECT", operation.path("decision").path("supply").asText());
        long expected = ((Number) oracle(QUERY).getFirst().get("value")).longValue();
        assertEquals(expected, operation.path("result").path("rows").get(0).path("value").asLong());
        assertEquals(3, expected);
        assertEquals(
                "PASSED",
                operation.path("verification").path("independentReconciliation").asText());
        assertEquals(referenceBefore + 1, gateway().referenceQueries());
        assertEquals("PERIOD_COMPLETE", operation.path("result").path("scope").asText());
        JsonNode actualRequest = gateway().lastRequest();
        assertEquals(metric.path("id"), actualRequest.path("metric").path("releaseId"));
        assertEquals(metric.path("content_hash"), actualRequest.path("metric").path("contentHash"));
        assertEquals(FROM, actualRequest.path("scope").path("from").asText());
        assertFalse(actualRequest.has("sql"));
        assertFalse(operation.has("overallTaskSuccess"));
    }

    @Test
    void sourceNotReadyDoesNotExecuteMetricOrReturnAnInventedZero() throws Exception {
        db.update(
                "UPDATE "
                        + SqlBackedMetricGateway.READINESS
                        + " SET state='NOT_READY',updated_at=clock_timestamp() WHERE capability_ref=?",
                CAPABILITY);
        int before = gateway().actualQueries();
        JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, completeRequest());
        assertEquals("NOT_READY", operation.path("status").asText(), operation.toString());
        assertEquals("NOT_READY", operation.path("decision").path("readiness").asText());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertNotEquals("ZERO", operation.path("result").path("state").asText());
        assertEquals(before, gateway().actualQueries());
    }

    @Test
    void genuineQueriesCannotReuseWrongMetricImplementationWindowOrStaleProof() throws Exception {
        List<Consumer<ObjectNode>> mismatches =
                List.of(
                        response -> response.put("requestHash", "0".repeat(64)),
                        response -> response.put("operationId", UUID.randomUUID().toString()),
                        response ->
                                ((ObjectNode) response.path("metric"))
                                        .put("contentHash", "0".repeat(64)),
                        response ->
                                ((ObjectNode) response.path("capability"))
                                        .put("implementationHash", "0".repeat(64)),
                        response ->
                                ((ObjectNode) response.path("scope"))
                                        .put("to", "2026-08-20T00:00:00Z"),
                        response ->
                                ((ObjectNode) response.path("readiness"))
                                        .put("observedAt", "2020-01-01T00:00:00Z"),
                        response ->
                                ((ObjectNode) response.path("readiness"))
                                        .put("completeThrough", FROM),
                        response ->
                                ((ObjectNode) response.path("result").path("rows").get(0))
                                        .put("private_value", "must-not-leak"));
        for (Consumer<ObjectNode> mutation : mismatches) {
            gateway().mutateResponse(mutation);
            JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, completeRequest());
            assertNotEquals("QUERIED", operation.path("status").asText(), operation.toString());
            assertTrue(operation.path("result").path("rows").isEmpty(), operation.toString());
            assertFalse(operation.toString().contains("must-not-leak"));
        }
    }

    @Test
    void structurallyValidWrongValueFailsAnIndependentQueryOverTheActualSameSnapshot()
            throws Exception {
        gateway()
                .mutateResponse(
                        response -> {
                            ObjectNode row =
                                    (ObjectNode) response.path("result").path("rows").get(0);
                            row.put(
                                    "value",
                                    row.path("value")
                                            .bigIntegerValue()
                                            .add(java.math.BigInteger.ONE));
                        });
        ObjectNode request = completeRequest();
        ((ObjectNode) request.path("requirements")).put("verification", "REQUIRE_INDEPENDENT");
        int referenceBefore = gateway().referenceQueries();
        JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, request);
        assertEquals(
                "FAILED_VERIFICATION", operation.path("status").asText(), operation.toString());
        assertEquals(
                "FAILED",
                operation.path("verification").path("independentReconciliation").asText());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertEquals(referenceBefore + 1, gateway().referenceQueries());
        assertFalse(operation.toString().contains("private-one"));
    }

    @Test
    void expiredPhysicalSnapshotCannotBeReplacedByAnAssertedSuccessfulCheck() throws Exception {
        gateway().mutateResponse(response -> gateway().expireSnapshots());
        ObjectNode request = completeRequest();
        ((ObjectNode) request.path("requirements")).put("verification", "REQUIRE_INDEPENDENT");
        JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, request);
        assertEquals("INCONCLUSIVE", operation.path("status").asText(), operation.toString());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertNotEquals(
                "PASSED",
                operation.path("verification").path("independentReconciliation").asText());
    }

    @Test
    void registeredCheckerTransportDoesNotAuthorizeAnotherCheckerImplementation() throws Exception {
        gateway()
                .mutateVerification(response -> response.put("implementationHash", "0".repeat(64)));
        ObjectNode request = completeRequest();
        ((ObjectNode) request.path("requirements")).put("verification", "REQUIRE_INDEPENDENT");
        JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, request);
        assertEquals("INCONCLUSIVE", operation.path("status").asText(), operation.toString());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertNotEquals(
                "PASSED",
                operation.path("verification").path("independentReconciliation").asText());
    }

    @Test
    void completeRequestedDayCannotCoverSixUnprovenRollingInputDays() throws Exception {
        ObjectNode request = metricRequest(rollingFunction, key());
        ((ObjectNode) request.path("requirements")).put("view", "PERIOD_COMPLETE");
        JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, request);
        assertTrue(
                Set.of("NOT_READY", "INCONCLUSIVE").contains(operation.path("status").asText()),
                operation.toString());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertNotEquals("READY", operation.path("decision").path("readiness").asText());
        assertNotEquals("ZERO", operation.path("result").path("state").asText());
    }
}
