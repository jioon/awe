package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real warehouse fixtures shared by metric HTTP and adapter contract tests. Query outcomes come
 * from PostgreSQL rows, never a canned successful gateway result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MetricQueryTestSupport {
    static final String AUTHOR = "metric-query-author";
    static final String REVIEWER = "metric-query-reviewer";
    static final String CONSUMER = "metric-query-consumer";
    static final String SECOND_CONSUMER = "metric-query-second-consumer";
    static final String PURPOSE = "metric-analysis";
    static final String DOMAIN = "metric_query";
    static final String EVENTS = "public.awe_metric_events";
    static final String USERS = "public.awe_metric_users";
    static final String FROM = "2026-08-18T00:00:00Z";
    static final String TO = "2026-08-19T00:00:00Z";
    @Autowired JsonDocuments json;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    void createActualWarehouseRelations() {
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + USERS
                        + " (user_id TEXT PRIMARY KEY,city TEXT NOT NULL,segment TEXT NOT NULL)");
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + EVENTS
                        + " (event_id TEXT PRIMARY KEY,user_id TEXT,event_time TIMESTAMPTZ NOT NULL,channel TEXT NOT NULL,amount NUMERIC(40,21),private_value TEXT)");
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + SqlBackedMetricGateway.READINESS
                        + " (capability_ref TEXT PRIMARY KEY,state TEXT NOT NULL,complete_from TIMESTAMPTZ,complete_through TIMESTAMPTZ,updated_at TIMESTAMPTZ NOT NULL)");
    }

    @BeforeEach
    void restoreIndependentMetricRows() {
        beforeWarehouseReset();
        db.execute("TRUNCATE TABLE " + EVENTS + "," + USERS);
        db.execute("TRUNCATE TABLE " + SqlBackedMetricGateway.READINESS);
        db.execute(
                "INSERT INTO "
                        + USERS
                        + " VALUES ('u1','A','core'),('u2','A','new'),('u3','B','core'),('u4','B','new')");
        db.execute(
                "INSERT INTO "
                        + EVENTS
                        + " VALUES "
                        + "('day0','u1','2026-08-16T16:00:00Z','native',1000,'private-history'),"
                        + "('before','u1','2026-08-17T23:59:59.999999Z','native',100,'private-before'),"
                        + "('lower','u1','2026-08-18T00:00:00Z','native',1.25,'private-one'),"
                        + "('repeat','u1','2026-08-18T01:00:00Z','native',2.50,'private-two'),"
                        + "('other','u2','2026-08-18T02:00:00Z','external',3.75,'private-three'),"
                        + "('third','u3','2026-08-18T03:00:00Z','native',NULL,'private-four'),"
                        + "('null_identity',NULL,'2026-08-18T04:00:00Z','native',0,'private-five'),"
                        + "('upper','u4','2026-08-19T00:00:00Z','native',100,'private-upper'),"
                        + "('day3','u1','2026-08-20T00:00:00Z','native',1234567890.123456789012345678901,'private-day3')");
    }

    void beforeWarehouseReset() {}

    JsonNode publish(String type, ObjectNode document) throws Exception {
        String id = document.path("model").path("ref").path("id").asText();
        String domain = id.split("\\.")[1];
        JsonNode draft =
                ok(
                        "POST",
                        "/api/drafts",
                        AUTHOR,
                        Map.of(
                                "domain",
                                domain,
                                "entityId",
                                id,
                                "entityType",
                                type,
                                "spec",
                                document));
        ok("POST", "/api/drafts/" + draft.path("id").asText() + "/submit", AUTHOR, Map.of());
        ok("POST", "/api/drafts/" + draft.path("id").asText() + "/approve", REVIEWER, Map.of());
        return ok(
                "POST",
                "/api/drafts/" + draft.path("id").asText() + "/publish",
                REVIEWER,
                Map.of(
                        "version",
                        document.path("model").path("ref").path("modelVersion").asText()));
    }

    JsonNode observe(String externalRef) throws Exception {
        JsonNode observation =
                ok(
                        "POST",
                        "/api/sources/postgres/imports",
                        AUTHOR,
                        Map.of("externalRef", externalRef));
        assertEquals("IMPORTED", observation.path("status").asText(), observation.toString());
        assertTrue(observation.path("fingerprint").asText().matches("[a-f0-9]{64}"));
        return observation;
    }

    record SourceAssets(
            JsonNode subject, JsonNode rowSchema, JsonNode dataset, JsonNode readFunction) {}

    SourceAssets publishEventSource(String domain) throws Exception {
        JsonNode subject =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("entity." + domain + ".user"),
                                        "family",
                                        "BUSINESS_ENTITY",
                                        "description",
                                        "Authoritative user identity",
                                        "properties",
                                        List.of(property("user_id", "STRING", true, false)),
                                        "identityKeys",
                                        List.of("user_id"))));
        JsonNode schema =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("dataset." + domain + ".event_row"),
                                        "family",
                                        "DATASET",
                                        "description",
                                        "Explicit metric event row",
                                        "properties",
                                        List.of(
                                                property("event_id", "STRING", true, false),
                                                property("user_id", "STRING", false, false),
                                                property("event_time", "TIMESTAMP", true, false),
                                                property("channel", "STRING", true, false),
                                                property("amount", "DECIMAL", false, false),
                                                property("private_value", "STRING", false, true)),
                                        "identityKeys",
                                        List.of("event_id"))));
        JsonNode observation = observe("metric.events");
        ObjectNode datasetSpec =
                spec(
                        model(
                                "ref",
                                ref("dataset." + domain + ".events"),
                                "grain",
                                Map.of("kind", "EVENT", "keys", List.of("event_id")),
                                "time",
                                Map.of(
                                        "roles",
                                        List.of("EVENT_TIME"),
                                        "businessZone",
                                        "Asia/Shanghai",
                                        "backfillPossible",
                                        false),
                                "subjectType",
                                ref("entity." + domain + ".user"),
                                "dimensions",
                                List.of()));
        datasetSpec.set(
                "bindings",
                json.parse(
                        json.write(
                                List.of(
                                        Map.of(
                                                "sourceSystem",
                                                "postgres",
                                                "externalRef",
                                                "metric.events",
                                                "fingerprint",
                                                observation.path("fingerprint").asText(),
                                                "timeFields",
                                                Map.of("EVENT_TIME", "event_time"))))));
        JsonNode dataset = publish("Dataset", datasetSpec);
        JsonNode function =
                publish(
                        "FunctionDefinition",
                        spec(
                                model(
                                        "ref",
                                        ref("function." + domain + ".events_read"),
                                        "ownerType",
                                        ref("dataset." + domain + ".events"),
                                        "operation",
                                        "dataset.extract.v1",
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
                                        "dataset." + domain + ".event_row@1.0.0",
                                        "executorBindingRef",
                                        "postgres.dataset.extract.v1",
                                        "requiredAssertions",
                                        List.of())));
        return new SourceAssets(subject, schema, dataset, function);
    }

    SourceAssets publishStaticUserSource(String domain, JsonNode subject) throws Exception {
        JsonNode schema =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("dataset." + domain + ".user_row"),
                                        "family",
                                        "DATASET",
                                        "description",
                                        "Current static user attributes; no fabricated event-time column",
                                        "properties",
                                        List.of(
                                                property("user_id", "STRING", true, false),
                                                property("city", "STRING", true, false),
                                                property("segment", "STRING", true, false)),
                                        "identityKeys",
                                        List.of("user_id"))));
        JsonNode observation = observe("metric.users");
        ObjectNode document =
                spec(
                        model(
                                "ref",
                                ref("dataset." + domain + ".users"),
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
                                ref("entity." + domain + ".user"),
                                "dimensions",
                                List.of()));
        document.put("schemaVersion", 3);
        document.set(
                "datasetContract",
                json.parse(
                        json.write(
                                model(
                                        "rowSchema",
                                        ref("dataset." + domain + ".user_row"),
                                        "mode",
                                        "OBSERVED_SOURCE",
                                        "targetBindingRef",
                                        null,
                                        "readMode",
                                        "STATIC_SNAPSHOT",
                                        "rangeRole",
                                        "OBSERVED_AT"))));
        document.set(
                "bindings",
                json.parse(
                        json.write(
                                List.of(
                                        Map.of(
                                                "sourceSystem",
                                                "postgres",
                                                "externalRef",
                                                "metric.users",
                                                "fingerprint",
                                                observation.path("fingerprint").asText(),
                                                "timeFields",
                                                Map.of())))));
        JsonNode dataset = publish("Dataset", document);
        JsonNode function =
                publish(
                        "FunctionDefinition",
                        spec(
                                model(
                                        "ref",
                                        ref("function." + domain + ".users_read"),
                                        "ownerType",
                                        ref("dataset." + domain + ".users"),
                                        "operation",
                                        "dataset.snapshot.v1",
                                        "parameters",
                                        List.of(),
                                        "resultSchemaRef",
                                        "dataset." + domain + ".user_row@1.0.0",
                                        "executorBindingRef",
                                        "postgres.dataset.snapshot.v1",
                                        "requiredAssertions",
                                        List.of())));
        return new SourceAssets(subject, schema, dataset, function);
    }

    void createDomain(String code) throws Exception {
        ok(
                "POST",
                "/api/domains",
                AUTHOR,
                Map.of(
                        "code",
                        code,
                        "displayName",
                        "Actual metric warehouse",
                        "ownerTeam",
                        "metric_team"));
    }

    ObjectNode spec(Map<String, Object> model) {
        return json.object(
                json.write(
                        Map.of(
                                "schemaVersion", 2,
                                "name", "Registered metric acceptance asset",
                                "definition",
                                        "Real PostgreSQL metric acceptance; not an enterprise BI connection",
                                "ownerTeam", "metric_team",
                                "model", model)));
    }

    static Map<String, Object> ref(String id) {
        return Map.of("id", id, "modelVersion", "1.0.0");
    }

    static Map<String, Object> property(
            String name, String type, boolean required, boolean sensitive) {
        return Map.of("name", name, "type", type, "required", required, "sensitive", sensitive);
    }

    static Map<String, Object> model(Object... pairs) {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    static String key() {
        return "metric_" + UUID.randomUUID().toString().replace("-", "");
    }

    ObjectNode metricSpec(
            String domain,
            String name,
            String aggregation,
            String unit,
            Object plan,
            List<Map<String, Object>> outputs,
            List<String> grain,
            Object direct) {
        ObjectNode document =
                spec(
                        model(
                                "ref", ref("metric." + domain + "." + name),
                                "grain", Map.of("kind", "AGGREGATE", "keys", grain),
                                "time",
                                        Map.of(
                                                "roles",
                                                List.of("EVENT_TIME"),
                                                "businessZone",
                                                "Asia/Shanghai",
                                                "backfillPossible",
                                                false),
                                "subjectType", ref("entity." + domain + ".user"),
                                "sourceDataset", ref("dataset." + domain + ".events"),
                                "aggregation",
                                        Map.of(
                                                "kind",
                                                aggregation,
                                                "permittedRollupAxes",
                                                List.of(),
                                                "forbiddenRollupAxes",
                                                List.of("day")),
                                "allowedDimensions", List.of()));
        document.put("schemaVersion", 3);
        document.set(
                "metricContract",
                json.parse(
                        json.write(
                                model(
                                        "statisticalSubject",
                                        ref("entity." + domain + ".user"),
                                        "identityField",
                                        "events.user_id",
                                        "identityNullPolicy",
                                        "IGNORE",
                                        "unit",
                                        unit,
                                        "timeRole",
                                        "EVENT_TIME",
                                        "businessZone",
                                        "Asia/Shanghai",
                                        "sources",
                                        List.of(
                                                Map.of(
                                                        "alias",
                                                        "events",
                                                        "readFunction",
                                                        ref(
                                                                "function."
                                                                        + domain
                                                                        + ".events_read"))),
                                        "plan",
                                        plan,
                                        "outputs",
                                        outputs,
                                        "grain",
                                        grain,
                                        "emptyPolicy",
                                        grain.isEmpty()
                                                        && Set.of("COUNT", "COUNT_DISTINCT", "SUM")
                                                                .contains(aggregation)
                                                ? "ZERO"
                                                : "NO_RECORD",
                                        "zeroDivisionPolicy",
                                        "NULL",
                                        "direct",
                                        direct))));
        return document;
    }

    ObjectNode queryFunctionSpec(String domain, String name, boolean bi) {
        return spec(
                model(
                        "ref", ref("function." + domain + "." + name),
                        "ownerType", ref("metric." + domain + "." + name),
                        "operation", "metric.query.v1",
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
                        "resultSchemaRef", "metric." + domain + "." + name + "@1.0.0",
                        "executorBindingRef",
                                bi ? "bi.metric.query.v1" : "postgres.metric.query.v1",
                        "requiredAssertions", List.of()));
    }

    ObjectNode metricRequest(JsonNode function, String idempotencyKey) {
        return json.object(
                json.write(
                        Map.of(
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
                                Map.of("from", FROM, "to", TO, "view", "OBSERVED_SNAPSHOT"),
                                "consumer",
                                Map.of("purpose", PURPOSE),
                                "constraints",
                                Map.of("maxRows", 100, "maxBytes", 16384),
                                "idempotencyKey",
                                idempotencyKey)));
    }

    static Map<String, Object> scan() {
        return Map.of("kind", "SCAN", "source", "events", "fromDayOffset", 0, "toDayOffset", 0);
    }

    static Map<String, Object> field(String name) {
        return Map.of("kind", "FIELD", "name", name);
    }

    static Map<String, Object> excludeUnknownIdentity(Object input) {
        return Map.of(
                "kind",
                "FILTER",
                "input",
                input,
                "predicate",
                Map.of("kind", "IS_NULL", "input", field("events.user_id"), "negated", true));
    }

    static Map<String, Object> measure(String name, String kind, Object expression) {
        return Map.of("name", name, "kind", kind, "expression", expression);
    }

    static Map<String, Object> aggregate(Object input, List<?> groupBy, List<?> measures) {
        return Map.of(
                "kind", "AGGREGATE", "input", input, "groupBy", groupBy, "measures", measures);
    }

    static Map<String, Object> output(String name, String type, String unit, boolean nullable) {
        return Map.of("name", name, "type", type, "unit", unit, "nullable", nullable);
    }

    List<Map<String, Object>> oracle(String query) {
        return db.queryForList(
                query, Timestamp.from(Instant.parse(FROM)), Timestamp.from(Instant.parse(TO)));
    }

    HttpResponse<String> send(String method, String path, String token, Object body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(25));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.write(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = send(method, path, token, body);
        assertEquals(200, response.statusCode(), response.body());
        return json.parse(response.body());
    }
}
