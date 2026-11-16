package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Formal published metric plans execute real PostgreSQL computations through the public API. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=metric-query-author",
            "awe.auth.reviewer-token=metric-query-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class MetricQueryIntegrationTest extends MetricQueryTestSupport {
    private static final List<String> NAMES =
            List.of(
                    "dau",
                    "amount_sum",
                    "filtered_amount_sum",
                    "channel_dau",
                    "ratio",
                    "new_users",
                    "high_frequency",
                    "city_dau");
    private static final String SHORT_WINDOW_CONSUMER = "metric-short-window-consumer";
    private final Map<String, JsonNode> functions = new HashMap<>();
    private final Map<String, JsonNode> metrics = new HashMap<>();

    @DynamicPropertySource
    static void configuredNativeQuery(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
        properties.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () ->
                        "{\"postgres:metric.events\":\"public.awe_metric_events\",\"postgres:metric.users\":\"public.awe_metric_users\"}");
        properties.add("AWE_CONSUMER_POLICIES", MetricQueryIntegrationTest::policies);
        properties.add("AWE_HIVE_METADATA_URL", () -> "");
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_BI_QUERY_URL", () -> "");
        properties.add("AWE_DATA_READINESS_URL", () -> "");
        properties.add("AWE_METRIC_VERIFICATION_URL", () -> "");
        properties.add("AWE_WAREHOUSE_MAX_ROWS", () -> "32");
    }

    private static String policies() {
        var policy =
                Map.of(
                        "principal",
                        "metric-consumer",
                        "token",
                        CONSUMER,
                        "functionIds",
                        NAMES.stream().map(name -> "function." + DOMAIN + "." + name).toList(),
                        "purposes",
                        List.of(PURPOSE),
                        "fields",
                        List.of(
                                "event_id",
                                "user_id",
                                "event_time",
                                "channel",
                                "amount",
                                "private_value",
                                "value",
                                "numerator",
                                "denominator",
                                "active_days",
                                "activity_day",
                                "city",
                                "segment"),
                        "maxRows",
                        100,
                        "maxBytes",
                        16384,
                        "maxWindowSeconds",
                        8 * 86400);
        var second = new LinkedHashMap<String, Object>(policy);
        second.put("principal", "metric-second-consumer");
        second.put("token", SECOND_CONSUMER);
        var shortWindow = new LinkedHashMap<String, Object>(policy);
        shortWindow.put("principal", "metric-short-window-consumer");
        shortWindow.put("token", SHORT_WINDOW_CONSUMER);
        shortWindow.put("maxWindowSeconds", 86400);
        return new ai.awe.registry.support.JsonDocuments()
                .write(List.of(policy, second, shortWindow));
    }

    @BeforeAll
    void publishFormalMetricPlans() throws Exception {
        createDomain(DOMAIN);
        SourceAssets events = publishEventSource(DOMAIN);
        register(
                "dau",
                "COUNT_DISTINCT",
                "users",
                aggregate(
                        excludeUnknownIdentity(scan()),
                        List.of(),
                        List.of(measure("value", "COUNT_DISTINCT", field("events.user_id")))),
                List.of(output("value", "INTEGER", "users", false)),
                List.of());
        register(
                "amount_sum",
                "SUM",
                "currency",
                aggregate(
                        scan(),
                        List.of(),
                        List.of(measure("value", "SUM", field("events.amount")))),
                List.of(output("value", "DECIMAL", "currency", true)),
                List.of());
        Object filteredEmpty =
                Map.of(
                        "kind",
                        "FILTER",
                        "input",
                        scan(),
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
                                        "kind",
                                        "LITERAL",
                                        "type",
                                        "STRING",
                                        "value",
                                        "absent-channel")));
        register(
                "filtered_amount_sum",
                "SUM",
                "currency",
                aggregate(
                        filteredEmpty,
                        List.of(),
                        List.of(measure("value", "SUM", field("events.amount")))),
                List.of(output("value", "DECIMAL", "currency", true)),
                List.of());

        Object projection =
                Map.of(
                        "kind",
                        "PROJECT",
                        "input",
                        excludeUnknownIdentity(scan()),
                        "columns",
                        List.of(
                                Map.of("name", "channel", "expression", field("events.channel")),
                                Map.of("name", "user_id", "expression", field("events.user_id"))));
        register(
                "channel_dau",
                "COUNT_DISTINCT",
                "users",
                aggregate(
                        projection,
                        List.of("channel"),
                        List.of(measure("value", "COUNT_DISTINCT", field("user_id")))),
                List.of(
                        output("channel", "STRING", "channel", false),
                        output("value", "INTEGER", "users", false)),
                List.of("channel"));

        Object ratioInput =
                aggregate(
                        excludeUnknownIdentity(scan()),
                        List.of(),
                        List.of(
                                measure("numerator", "SUM", field("events.amount")),
                                measure("denominator", "COUNT_DISTINCT", field("events.user_id"))));
        Object ratioPlan =
                Map.of(
                        "kind",
                        "PROJECT",
                        "input",
                        ratioInput,
                        "columns",
                        List.of(
                                Map.of(
                                        "name",
                                        "value",
                                        "expression",
                                        Map.of(
                                                "kind",
                                                "ARITHMETIC",
                                                "operation",
                                                "DIVIDE",
                                                "left",
                                                field("numerator"),
                                                "right",
                                                field("denominator")))));
        register(
                "ratio",
                "RATIO",
                "currency_per_user",
                ratioPlan,
                List.of(output("value", "DECIMAL", "currency_per_user", true)),
                List.of());

        Object today =
                Map.of(
                        "kind",
                        "PROJECT",
                        "input",
                        excludeUnknownIdentity(scan()),
                        "columns",
                        List.of(Map.of("name", "user_id", "expression", field("events.user_id"))));
        Object yesterday =
                Map.of(
                        "kind",
                        "PROJECT",
                        "input",
                        excludeUnknownIdentity(
                                Map.of(
                                        "kind",
                                        "SCAN",
                                        "source",
                                        "events",
                                        "fromDayOffset",
                                        -1,
                                        "toDayOffset",
                                        -1)),
                        "columns",
                        List.of(Map.of("name", "user_id", "expression", field("events.user_id"))));
        register(
                "new_users",
                "COUNT_DISTINCT",
                "users",
                aggregate(
                        Map.of(
                                "kind",
                                "SET",
                                "left",
                                today,
                                "right",
                                yesterday,
                                "operation",
                                "EXCEPT_DISTINCT"),
                        List.of(),
                        List.of(measure("value", "COUNT_DISTINCT", field("user_id")))),
                List.of(output("value", "INTEGER", "users", false)),
                List.of());

        Object days =
                Map.of(
                        "kind",
                        "PROJECT",
                        "input",
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
                        "columns",
                        List.of(
                                Map.of("name", "user_id", "expression", field("events.user_id")),
                                Map.of(
                                        "name",
                                        "activity_day",
                                        "expression",
                                        Map.of(
                                                "kind",
                                                "BUSINESS_DATE",
                                                "input",
                                                field("events.event_time")))));
        Object activeDays =
                aggregate(
                        days,
                        List.of("user_id"),
                        List.of(measure("active_days", "COUNT_DISTINCT", field("activity_day"))));
        Object frequent =
                Map.of(
                        "kind",
                        "FILTER",
                        "input",
                        activeDays,
                        "predicate",
                        Map.of(
                                "kind",
                                "COMPARE",
                                "operation",
                                "GE",
                                "left",
                                field("active_days"),
                                "right",
                                Map.of("kind", "LITERAL", "type", "INTEGER", "value", "3")));
        register(
                "high_frequency",
                "COUNT",
                "users",
                aggregate(
                        frequent,
                        List.of(),
                        List.of(
                                measure(
                                        "value",
                                        "COUNT_ROWS",
                                        Map.of(
                                                "kind", "LITERAL", "type", "INTEGER", "value",
                                                "1")))),
                List.of(output("value", "INTEGER", "users", false)),
                List.of());

        publishStaticUserSource(DOMAIN, events.subject());
        publish(
                "LinkType",
                spec(
                        model(
                                "ref",
                                ref("link." + DOMAIN + ".user_city"),
                                "fromType",
                                ref("dataset." + DOMAIN + ".events"),
                                "toType",
                                ref("dataset." + DOMAIN + ".users"),
                                "meaning",
                                "HAS_DIMENSION",
                                "cardinality",
                                "MANY_TO_ONE",
                                "temporalPolicy",
                                "TIME_INDEPENDENT",
                                "identityMappingRef",
                                "columns.equal.v1",
                                "certified",
                                true)));
        ObjectNode cityMetric =
                metricSpec(
                        DOMAIN,
                        "city_dau",
                        "COUNT_DISTINCT",
                        "users",
                        cityPlan("user_city"),
                        List.of(
                                output("city", "STRING", "city", false),
                                output("value", "INTEGER", "users", false)),
                        List.of("city"),
                        null);
        withUserSource(cityMetric);
        metrics.put("city_dau", publish("Metric", cityMetric));
        functions.put(
                "city_dau",
                publish("FunctionDefinition", queryFunctionSpec(DOMAIN, "city_dau", false)));
    }

    private void register(
            String name,
            String aggregation,
            String unit,
            Object plan,
            List<Map<String, Object>> outputs,
            List<String> grain)
            throws Exception {
        metrics.put(
                name,
                publish(
                        "Metric",
                        metricSpec(DOMAIN, name, aggregation, unit, plan, outputs, grain, null)));
        functions.put(name, publish("FunctionDefinition", queryFunctionSpec(DOMAIN, name, false)));
    }

    private JsonNode query(String name) throws Exception {
        return ok("POST", "/api/data-tasks", CONSUMER, metricRequest(functions.get(name), key()));
    }

    private Object cityPlan(String linkName) {
        Object join =
                Map.of(
                        "kind",
                        "JOIN",
                        "left",
                        excludeUnknownIdentity(scan()),
                        "right",
                        Map.of(
                                "kind",
                                "SCAN",
                                "source",
                                "users",
                                "fromDayOffset",
                                0,
                                "toDayOffset",
                                0),
                        "joinKind",
                        "INNER",
                        "link",
                        ref("link." + DOMAIN + "." + linkName),
                        "keys",
                        List.of(
                                Map.of(
                                        "leftField",
                                        "events.user_id",
                                        "rightField",
                                        "users.user_id")));
        Object projection =
                Map.of(
                        "kind",
                        "PROJECT",
                        "input",
                        join,
                        "columns",
                        List.of(
                                Map.of("name", "city", "expression", field("users.city")),
                                Map.of("name", "user_id", "expression", field("events.user_id"))));
        return aggregate(
                projection,
                List.of("city"),
                List.of(measure("value", "COUNT_DISTINCT", field("user_id"))));
    }

    private void withUserSource(ObjectNode metric) {
        ((ObjectNode) metric.path("metricContract"))
                .set(
                        "sources",
                        json.parse(
                                json.write(
                                        List.of(
                                                Map.of(
                                                        "alias",
                                                        "events",
                                                        "readFunction",
                                                        ref("function." + DOMAIN + ".events_read")),
                                                Map.of(
                                                        "alias",
                                                        "users",
                                                        "readFunction",
                                                        ref(
                                                                "function."
                                                                        + DOMAIN
                                                                        + ".users_read"))))));
    }

    private JsonNode successful(JsonNode operation) {
        assertEquals("QUERIED", operation.path("status").asText(), operation.toString());
        assertEquals("RESOLVED", operation.path("decision").path("resolution").asText());
        assertEquals("ALLOWED", operation.path("decision").path("authorization").asText());
        assertEquals("UNKNOWN", operation.path("decision").path("readiness").asText());
        assertEquals("OBSERVED_SNAPSHOT", operation.path("result").path("scope").asText());
        assertFalse(operation.has("overallTaskSuccess"));
        return operation.path("result").path("rows");
    }

    @Test
    void exactDistinctAndSumMatchIndependentQueriesOnTheSameWindow() throws Exception {
        JsonNode dau = query("dau");
        JsonNode rows = successful(dau);
        Number expected =
                (Number)
                        oracle(
                                        "SELECT COUNT(DISTINCT user_id) AS value FROM "
                                                + EVENTS
                                                + " WHERE event_time>=? AND event_time<? AND user_id IS NOT NULL")
                                .getFirst()
                                .get("value");
        assertEquals(expected.longValue(), rows.get(0).path("value").asLong());
        assertEquals(3, rows.get(0).path("value").asInt());
        assertEquals(
                metrics.get("dau").path("content_hash"),
                dau.path("resolved").path("metric").path("contentHash"));
        JsonNode sum = successful(query("amount_sum"));
        BigDecimal amount =
                (BigDecimal)
                        oracle(
                                        "SELECT SUM(amount) AS value FROM "
                                                + EVENTS
                                                + " WHERE event_time>=? AND event_time<?")
                                .getFirst()
                                .get("value");
        assertEquals(0, amount.compareTo(sum.get(0).path("value").decimalValue()));
    }

    @Test
    void publishedGroupingAndRatioPreserveDistinctPopulationAndPrecision() throws Exception {
        var groups = new TreeMap<String, Long>();
        for (JsonNode row : successful(query("channel_dau")))
            groups.put(row.path("channel").asText(), row.path("value").asLong());
        assertEquals(Map.of("native", 2L, "external", 1L), groups);
        JsonNode ratio = successful(query("ratio"));
        assertEquals(0, new BigDecimal("2.5").compareTo(ratio.get(0).path("value").decimalValue()));
    }

    @Test
    void formalSetDifferenceAndCalendarWindowExecuteReusableOperators() throws Exception {
        assertEquals(2, successful(query("new_users")).get(0).path("value").asInt());
        ObjectNode body = metricRequest(functions.get("high_frequency"), key());
        ((ObjectNode) body.path("requirements"))
                .put("from", "2026-08-20T00:00:00Z")
                .put("to", "2026-08-21T00:00:00Z");
        JsonNode result = successful(ok("POST", "/api/data-tasks", CONSUMER, body));
        long oracle =
                db.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT user_id FROM "
                                + EVENTS
                                + " WHERE event_time>='2026-08-14T00:00:00Z' AND event_time<'2026-08-21T00:00:00Z' AND user_id IS NOT NULL"
                                + " GROUP BY user_id HAVING COUNT(DISTINCT (event_time AT TIME ZONE 'Asia/Shanghai')::date)>=3) q",
                        Long.class);
        assertEquals(oracle, result.get(0).path("value").asLong());
        assertEquals(1, oracle);
    }

    @Test
    void certifiedJoinUsesRealRightHandPrimaryKeyAndAnExplicitStaticSnapshot() throws Exception {
        JsonNode rows = successful(query("city_dau"));
        var actual = new TreeMap<String, Long>();
        for (JsonNode row : rows) actual.put(row.path("city").asText(), row.path("value").asLong());
        var expected = new TreeMap<String, Long>();
        for (var row :
                oracle(
                        "SELECT u.city,COUNT(DISTINCT e.user_id) AS value FROM "
                                + EVENTS
                                + " e JOIN "
                                + USERS
                                + " u ON u.user_id=e.user_id WHERE e.event_time>=? AND e.event_time<? AND e.user_id IS NOT NULL GROUP BY u.city"))
            expected.put((String) row.get("city"), ((Number) row.get("value")).longValue());
        assertEquals(Map.of("A", 2L, "B", 1L), expected);
        assertEquals(expected, actual);
    }

    @Test
    void knownFanoutAndTemporalAmbiguityCannotBeRegisteredAsExecutableMetrics() throws Exception {
        for (String variant : List.of("MANY_TO_MANY", "ONE_TO_MANY", "AS_OF")) {
            String suffix = "bad_" + variant.toLowerCase(Locale.ROOT);
            String cardinality = variant.equals("AS_OF") ? "MANY_TO_ONE" : variant;
            publish(
                    "LinkType",
                    spec(
                            model(
                                    "ref",
                                    ref("link." + DOMAIN + "." + suffix),
                                    "fromType",
                                    ref("dataset." + DOMAIN + ".events"),
                                    "toType",
                                    ref("dataset." + DOMAIN + ".users"),
                                    "meaning",
                                    "HAS_DIMENSION",
                                    "cardinality",
                                    cardinality,
                                    "temporalPolicy",
                                    variant.equals("AS_OF") ? "AS_OF" : "TIME_INDEPENDENT",
                                    "identityMappingRef",
                                    "columns.equal.v1",
                                    "certified",
                                    true)));
            ObjectNode document =
                    metricSpec(
                            DOMAIN,
                            suffix,
                            "COUNT_DISTINCT",
                            "users",
                            cityPlan(suffix),
                            List.of(
                                    output("city", "STRING", "city", false),
                                    output("value", "INTEGER", "users", false)),
                            List.of("city"),
                            null);
            withUserSource(document);
            var draft =
                    ok(
                            "POST",
                            "/api/drafts",
                            AUTHOR,
                            Map.of(
                                    "domain",
                                    DOMAIN,
                                    "entityId",
                                    "metric." + DOMAIN + "." + suffix,
                                    "entityType",
                                    "Metric",
                                    "spec",
                                    document));
            JsonNode validation =
                    ok(
                            "POST",
                            "/api/drafts/" + draft.path("id").asText() + "/validate",
                            AUTHOR,
                            Map.of());
            assertFalse(validation.path("valid").asBoolean(), validation.toString());
            assertEquals(
                    400,
                    send(
                                    "POST",
                                    "/api/drafts/" + draft.path("id").asText() + "/submit",
                                    AUTHOR,
                                    Map.of())
                            .statusCode());
        }
    }

    @Test
    void droppingPhysicalJoinUniquenessCannotTurnAReviewedLinkIntoFanout() throws Exception {
        db.execute("ALTER TABLE " + USERS + " DROP CONSTRAINT awe_metric_users_pkey");
        try {
            db.update("INSERT INTO " + USERS + " VALUES('u1','B','duplicate-without-key')");
            JsonNode operation = query("city_dau");
            assertNotEquals("QUERIED", operation.path("status").asText(), operation.toString());
            assertTrue(operation.path("result").path("rows").isEmpty());
        } finally {
            db.update("DELETE FROM " + USERS + " WHERE segment='duplicate-without-key'");
            db.execute("ALTER TABLE " + USERS + " ADD PRIMARY KEY(user_id)");
        }
    }

    @Test
    void completePeriodCannotBeInventedFromReadablePostgresRows() throws Exception {
        ObjectNode body = metricRequest(functions.get("dau"), key());
        ((ObjectNode) body.path("requirements")).put("view", "PERIOD_COMPLETE");
        JsonNode operation = ok("POST", "/api/data-tasks", CONSUMER, body);
        assertEquals("NOT_CONFIGURED", operation.path("status").asText(), operation.toString());
        assertEquals("UNKNOWN", operation.path("decision").path("readiness").asText());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertNotEquals("ZERO", operation.path("result").path("state").asText());
    }

    @Test
    void requestAuthorityUnknownPlansAndHashesAreNotClientControlled() throws Exception {
        ObjectNode tampered = metricRequest(functions.get("dau"), key());
        ((ObjectNode) tampered.path("proposedFunction")).put("contentHash", "0".repeat(64));
        assertEquals(400, send("POST", "/api/data-tasks", CONSUMER, tampered).statusCode());
        ObjectNode freePlan = metricRequest(functions.get("dau"), key());
        freePlan.set("plan", json.parse("{\"kind\":\"SQL\",\"sql\":\"select * from private\"}"));
        assertEquals(400, send("POST", "/api/data-tasks", CONSUMER, freePlan).statusCode());
        var principal = metricRequest(functions.get("dau"), key());
        ((ObjectNode) principal.path("consumer")).put("principal", "admin");
        assertEquals(400, send("POST", "/api/data-tasks", CONSUMER, principal).statusCode());
        assertEquals(
                401,
                send("POST", "/api/data-tasks", AUTHOR, metricRequest(functions.get("dau"), key()))
                        .statusCode());
    }

    @Test
    void evidenceIsPrincipalScopedIdempotentAndCannotBeRewritten() throws Exception {
        ObjectNode body = metricRequest(functions.get("dau"), key());
        JsonNode first = ok("POST", "/api/data-tasks", CONSUMER, body);
        successful(first);
        db.update(
                "INSERT INTO "
                        + EVENTS
                        + " VALUES('after_query','late_user','2026-08-18T12:00:00Z','native',0,'private-late')");
        assertEquals(first, ok("POST", "/api/data-tasks", CONSUMER, body));
        String id = first.path("id").asText();
        assertEquals(404, send("GET", "/api/data-tasks/" + id, SECOND_CONSUMER, null).statusCode());
        var changed = body.deepCopy();
        ((ObjectNode) changed.path("constraints")).put("maxRows", 99);
        assertEquals(409, send("POST", "/api/data-tasks", CONSUMER, changed).statusCode());
    }

    @Test
    void calendarLookbackCannotExpandBeyondTheAuthenticatedInputWindow() throws Exception {
        JsonNode allowed =
                ok(
                        "POST",
                        "/api/data-tasks",
                        SHORT_WINDOW_CONSUMER,
                        metricRequest(functions.get("dau"), key()));
        successful(allowed);
        assertEquals(3, allowed.path("result").path("rows").get(0).path("value").asInt());
        var request = metricRequest(functions.get("high_frequency"), key());
        ((ObjectNode) request.path("requirements"))
                .put("from", "2026-08-20T00:00:00Z")
                .put("to", "2026-08-21T00:00:00Z");
        assertEquals(
                403, send("POST", "/api/data-tasks", SHORT_WINDOW_CONSUMER, request).statusCode());
    }

    @Test
    void scalarZeroAndUndefinedRatioRemainDifferentPublishedStates() throws Exception {
        var count = metricRequest(functions.get("dau"), key());
        ((ObjectNode) count.path("requirements"))
                .put("from", "2026-08-21T00:00:00Z")
                .put("to", "2026-08-22T00:00:00Z");
        JsonNode zero = ok("POST", "/api/data-tasks", CONSUMER, count);
        successful(zero);
        assertEquals("ZERO", zero.path("result").path("state").asText());
        assertEquals(0, zero.path("result").path("rows").get(0).path("value").asInt());
        var ratio = metricRequest(functions.get("ratio"), key());
        ((ObjectNode) ratio.path("requirements"))
                .put("from", "2026-08-21T00:00:00Z")
                .put("to", "2026-08-22T00:00:00Z");
        JsonNode undefined = ok("POST", "/api/data-tasks", CONSUMER, ratio);
        successful(undefined);
        assertEquals("NULL", undefined.path("result").path("state").asText());
        assertTrue(undefined.path("result").path("rows").get(0).path("value").isNull());
        assertNotEquals(zero.path("resultHash"), undefined.path("resultHash"));
        var sumEmpty = metricRequest(functions.get("amount_sum"), key());
        ((ObjectNode) sumEmpty.path("requirements"))
                .put("from", "2026-08-21T00:00:00Z")
                .put("to", "2026-08-22T00:00:00Z");
        JsonNode emptySum = ok("POST", "/api/data-tasks", CONSUMER, sumEmpty);
        successful(emptySum);
        assertEquals("ZERO", emptySum.path("result").path("state").asText());
        assertEquals(
                0,
                emptySum.path("result").path("rows").get(0).path("value").decimalValue().signum());
        var allNull = metricRequest(functions.get("amount_sum"), key());
        ((ObjectNode) allNull.path("requirements"))
                .put("from", "2026-08-18T03:00:00Z")
                .put("to", "2026-08-18T04:00:00Z");
        JsonNode nullSum = ok("POST", "/api/data-tasks", CONSUMER, allNull);
        successful(nullSum);
        assertEquals("NULL", nullSum.path("result").path("state").asText());
        assertTrue(nullSum.path("result").path("rows").get(0).path("value").isNull());
        JsonNode filteredSum = query("filtered_amount_sum");
        successful(filteredSum);
        assertEquals("ZERO", filteredSum.path("result").path("state").asText());
    }

    @Test
    void aggregateDoesNotHideAnExceededPhysicalSourceRowBudget() throws Exception {
        db.execute(
                "INSERT INTO "
                        + EVENTS
                        + " SELECT 'bulk_'||i,'bulk_user_'||i,'2026-08-18T12:00:00Z'::timestamptz,'native',0,'private-bulk' FROM generate_series(1,40) AS i");
        long matching =
                ((Number)
                                oracle(
                                                "SELECT COUNT(*) AS value FROM "
                                                        + EVENTS
                                                        + " WHERE event_time>=? AND event_time<?")
                                        .getFirst()
                                        .get("value"))
                        .longValue();
        assertTrue(matching > 32);
        JsonNode operation = query("dau");
        assertEquals("INCONCLUSIVE", operation.path("status").asText(), operation.toString());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertNotEquals("ZERO", operation.path("result").path("state").asText());
    }
}
