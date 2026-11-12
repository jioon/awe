package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared HTTP authoring fixture; warehouse rows are genuine PostgreSQL rows, not gateway results.
 */
abstract class DataRunTestSupport {
    protected static final String AUTHOR = "data-run-integration-author";
    protected static final String REVIEWER = "data-run-integration-reviewer";
    protected static final String EXTERNAL_REF = "runtime.events";
    protected static final String TABLE = "public.awe_test_events";
    private static MetadataGatewayFixture gateway;
    protected final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Autowired protected JsonDocuments json;
    @Autowired protected JdbcTemplate db;
    @LocalServerPort protected int port;

    @DynamicPropertySource
    static void metadataConfiguration(DynamicPropertyRegistry properties) {
        properties.add("AWE_HIVE_METADATA_URL", () -> gateway().baseUrl());
        properties.add("AWE_HIVE_METADATA_TOKEN", () -> "");
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_BI_METADATA_TOKEN", () -> "");
        properties.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () -> "{\"hive:runtime.events\":\"public.awe_test_events\"}");
    }

    protected static synchronized MetadataGatewayFixture gateway() {
        if (gateway == null) gateway = new MetadataGatewayFixture();
        return gateway;
    }

    @AfterAll
    static synchronized void closeMetadataGateway() {
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }
    }

    @BeforeEach
    void createRealWarehouseTable() {
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE
                        + " (event_id TEXT, user_id TEXT, event_time TIMESTAMPTZ, available_at TIMESTAMPTZ)");
        db.execute("TRUNCATE TABLE " + TABLE);
        gateway().put(EXTERNAL_REF, metadata("runtime-schema-v1"));
    }

    protected ObjectNode metadata(String sourceVersion) {
        return json.object(
                json.write(
                        Map.of(
                                "externalRef",
                                EXTERNAL_REF,
                                "sourceVersion",
                                sourceVersion,
                                "columns",
                                List.of(
                                        Map.of(
                                                "name",
                                                "event_id",
                                                "type",
                                                "text",
                                                "nullable",
                                                true),
                                        Map.of("name", "user_id", "type", "text", "nullable", true),
                                        Map.of(
                                                "name",
                                                "event_time",
                                                "type",
                                                "timestamptz",
                                                "nullable",
                                                true),
                                        Map.of(
                                                "name",
                                                "available_at",
                                                "type",
                                                "timestamptz",
                                                "nullable",
                                                true)),
                                "grainKeys",
                                List.of("event_id"),
                                "timeFields",
                                List.of("event_time", "available_at"))));
    }

    protected void insertBadRows() {
        db.execute(
                "INSERT INTO "
                        + TABLE
                        + " VALUES "
                        + "('event_1','user_1','2026-11-12T00:00:00Z','2026-11-12T00:01:00Z'),"
                        + "('event_1','user_1','2026-11-12T00:00:00Z','2026-11-12T00:01:00Z'),"
                        + "(NULL,'user_3','2026-11-12T00:00:00Z','2026-11-12T00:01:00Z'),"
                        + "('event_4','user_4','2026-11-12T02:00:00Z','2026-11-12T00:00:00Z')");
    }

    protected void replaceWithGoodRows() {
        db.execute("TRUNCATE TABLE " + TABLE);
        db.execute(
                "INSERT INTO "
                        + TABLE
                        + " VALUES "
                        + "('event_1','user_1','2026-11-12T00:00:00Z','2026-11-12T00:01:00Z'),"
                        + "('event_2','user_2','2026-11-12T00:00:00Z','2026-11-12T00:01:00Z')");
    }

    protected record Published(JsonNode dataset, JsonNode contract) {}

    protected Published publishExecutionContract() throws Exception {
        String domain = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ok(
                "POST",
                "/api/domains",
                AUTHOR,
                Map.of(
                        "code",
                        domain,
                        "displayName",
                        "Real warehouse runtime integration",
                        "ownerTeam",
                        "warehouse_team"));
        publish(
                domain,
                "ObjectType",
                spec(
                        model(
                                "ref",
                                ref("entity." + domain + ".user"),
                                "family",
                                "BUSINESS_ENTITY",
                                "description",
                                "Stable source user identity",
                                "properties",
                                List.of(
                                        Map.of(
                                                "name",
                                                "user_id",
                                                "type",
                                                "STRING",
                                                "required",
                                                true,
                                                "sensitive",
                                                false)),
                                "identityKeys",
                                List.of("user_id"))));
        JsonNode imported =
                ok(
                        "POST",
                        "/api/sources/hive/imports",
                        AUTHOR,
                        Map.of("externalRef", EXTERNAL_REF));
        assertEquals("IMPORTED", imported.path("status").asText());
        var datasetSpec =
                spec(
                        model(
                                "ref",
                                ref("dataset." + domain + ".events"),
                                "grain",
                                Map.of("kind", "EVENT", "keys", List.of("event_id")),
                                "time",
                                Map.of(
                                        "roles",
                                        List.of("EVENT_TIME", "AVAILABLE_AT"),
                                        "businessZone",
                                        "UTC",
                                        "backfillPossible",
                                        true),
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
                                                "hive",
                                                "externalRef",
                                                EXTERNAL_REF,
                                                "fingerprint",
                                                imported.path("fingerprint").asText(),
                                                "timeFields",
                                                Map.of(
                                                        "EVENT_TIME",
                                                        "event_time",
                                                        "AVAILABLE_AT",
                                                        "available_at"))))));
        JsonNode dataset = publish(domain, "Dataset", datasetSpec);
        return new Published(dataset, publishContractFor(dataset, "row_checks"));
    }

    protected JsonNode publishContractFor(JsonNode dataset, String name) throws Exception {
        String domain = dataset.path("domain").asText();
        return publish(
                domain,
                "ContractDefinition",
                spec(
                        model(
                                "ref",
                                ref("contract." + domain + "." + name),
                                "subject",
                                ref(dataset.path("entity_id").asText()),
                                "kind",
                                "QUALITY",
                                "ownerTeam",
                                "warehouse_team",
                                "obligations",
                                List.of(
                                        Map.of(
                                                "ruleId",
                                                "check.grain.unique",
                                                "at",
                                                "EXECUTE",
                                                "blocking",
                                                true),
                                        Map.of(
                                                "ruleId",
                                                "check.grain.not_null",
                                                "at",
                                                "EXECUTE",
                                                "blocking",
                                                true),
                                        Map.of(
                                                "ruleId",
                                                "check.time.available_after_event",
                                                "at",
                                                "EXECUTE",
                                                "blocking",
                                                true)))));
    }

    protected Map<String, Object> runRequest(Published published, String key) {
        var request = new LinkedHashMap<String, Object>();
        request.put("releaseId", published.dataset().path("id").asText());
        request.put("contentHash", published.dataset().path("content_hash").asText());
        request.put("contractReleaseId", published.contract().path("id").asText());
        request.put("contractContentHash", published.contract().path("content_hash").asText());
        request.put("idempotencyKey", key);
        return request;
    }

    protected static String key() {
        return "run_" + UUID.randomUUID().toString().replace("-", "");
    }

    protected static Map<String, String> ref(String id) {
        return Map.of("id", id, "modelVersion", "1.0.0");
    }

    protected static Map<String, Object> model(Object... entries) {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) values.put((String) entries[i], entries[i + 1]);
        return values;
    }

    protected ObjectNode spec(Map<String, Object> definition) {
        return json.object(
                json.write(
                        Map.of(
                                "schemaVersion",
                                2,
                                "name",
                                "Runtime-owned contract",
                                "definition",
                                "Published warehouse model consumed by real row validation",
                                "ownerTeam",
                                "warehouse_team",
                                "model",
                                definition)));
    }

    protected JsonNode publish(String domain, String type, JsonNode spec) throws Exception {
        String id =
                ok(
                                "POST",
                                "/api/drafts",
                                AUTHOR,
                                Map.of(
                                        "domain",
                                        domain,
                                        "entityId",
                                        spec.path("model").path("ref").path("id").asText(),
                                        "entityType",
                                        type,
                                        "spec",
                                        spec))
                        .path("id")
                        .asText();
        ok("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
        return ok("POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", "1.0.0"));
    }

    protected HttpResponse<String> send(String method, String path, String token, Object body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.write(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = send(method, path, token, body);
        assertEquals(
                200, response.statusCode(), () -> method + " " + path + ": " + response.body());
        return json.parse(response.body());
    }
}
