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
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real PostgreSQL schemas and HTTP publication; no fabricated metadata gateway or release evidence.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DatasetConsumptionTestSupport {
    static final String AUTHOR = "consumption-integration-author";
    static final String REVIEWER = "consumption-integration-reviewer";
    static final String ALICE = "consumption-alice-token";
    static final String BOB = "consumption-bob-token";
    static final String CHARLIE = "consumption-charlie-token";
    static final String TABLE = "public.awe_consume_events";
    static final String FROM = "2026-08-18T00:00:00Z";
    static final String TO = "2026-08-18T01:00:00Z";
    static final String PURPOSE = "quality-audit";
    @Autowired JsonDocuments json;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JsonNode subject, rowSchema, dataset, function;

    abstract String domain();

    boolean bootstrapThroughConfiguredServer() {
        return false;
    }

    @DynamicPropertySource
    static void sharedConsumptionConfiguration(DynamicPropertyRegistry properties) {
        properties.add("AWE_CONSUMER_POLICIES", DatasetConsumptionTestSupport::policies);
        properties.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () -> "{\"postgres:consumption.events\":\"public.awe_consume_events\"}");
        properties.add("AWE_HIVE_METADATA_URL", () -> "");
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_CONSUMPTION_RESULT_TTL_SECONDS", () -> "900");
    }

    static String policies() {
        var ids =
                List.of(
                        "function.consume.extract",
                        "function.consume_missing.extract",
                        "function.consume.bpchar_extract");
        return new JsonDocuments()
                .write(
                        List.of(
                                Map.of(
                                        "principal",
                                        "consumer-alice",
                                        "token",
                                        ALICE,
                                        "functionIds",
                                        ids,
                                        "purposes",
                                        List.of(PURPOSE),
                                        "fields",
                                        List.of(
                                                "event_id",
                                                "user_id",
                                                "event_time",
                                                "sensitive_value"),
                                        "maxRows",
                                        20,
                                        "maxBytes",
                                        4096,
                                        "maxWindowSeconds",
                                        86400),
                                Map.of(
                                        "principal",
                                        "consumer-bob",
                                        "token",
                                        BOB,
                                        "functionIds",
                                        ids,
                                        "purposes",
                                        List.of(PURPOSE),
                                        "fields",
                                        List.of("event_id", "event_time"),
                                        "maxRows",
                                        5,
                                        "maxBytes",
                                        1024,
                                        "maxWindowSeconds",
                                        3600),
                                Map.of(
                                        "principal",
                                        "consumer-charlie",
                                        "token",
                                        CHARLIE,
                                        "functionIds",
                                        List.of("function.not_granted.extract"),
                                        "purposes",
                                        List.of(PURPOSE),
                                        "fields",
                                        List.of("event_id"),
                                        "maxRows",
                                        20,
                                        "maxBytes",
                                        4096,
                                        "maxWindowSeconds",
                                        86400)));
    }

    @BeforeAll
    void publishRealNativeBoundModels() throws Exception {
        db.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLE
                        + " (event_id TEXT PRIMARY KEY,user_id TEXT NOT NULL,event_time TIMESTAMPTZ NOT NULL,sensitive_value TEXT)");
        if (!bootstrapThroughConfiguredServer()) {
            publishModels();
            return;
        }
        // The NOT_CONFIGURED context consumes genuinely published evidence from a separate real
        // server.
        // No source snapshot or VERIFIED record is manufactured through SQL.
        var settings = new LinkedHashMap<String, Object>();
        settings.put("server.port", "0");
        settings.put("server.address", "127.0.0.1");
        settings.put("awe.auth.author-token", AUTHOR);
        settings.put("awe.auth.reviewer-token", REVIEWER);
        settings.put("AWE_CONSUMER_POLICIES", policies());
        settings.put("AWE_WAREHOUSE_JDBC_URL", Objects.requireNonNull(System.getenv("AWE_DB_URL")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_USER", Objects.requireNonNull(System.getenv("AWE_DB_USER")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_PASSWORD",
                Objects.requireNonNull(System.getenv("AWE_DB_PASSWORD")));
        settings.put(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                "{\"postgres:consumption.events\":\"public.awe_consume_events\"}");
        int originalPort = port;
        try (var configured =
                new SpringApplicationBuilder(RegistryApplication.class)
                        .registerShutdownHook(false)
                        .initializers(
                                context ->
                                        context.getEnvironment()
                                                .getPropertySources()
                                                .addFirst(
                                                        new MapPropertySource(
                                                                "configured-consumption-fixture",
                                                                settings)))
                        .run()) {
            port = ((WebServerApplicationContext) configured).getWebServer().getPort();
            publishModels();
        } finally {
            port = originalPort;
        }
    }

    @BeforeEach
    void restoreKnownRows() {
        db.execute("TRUNCATE TABLE " + TABLE);
        db.execute(
                "INSERT INTO "
                        + TABLE
                        + " VALUES "
                        + "('before','user_before','2026-08-17T23:59:59.999999Z','fixture-private-before'),"
                        + "('at_from','user_1','2026-08-18T00:00:00Z','fixture-private-one'),"
                        + "('inside','user_2','2026-08-18T00:30:00Z','fixture-private-two'),"
                        + "('at_to','user_3','2026-08-18T01:00:00Z','fixture-private-three')");
    }

    private void publishModels() throws Exception {
        String domain = domain();
        ok(
                "POST",
                "/api/domains",
                AUTHOR,
                Map.of(
                        "code",
                        domain,
                        "displayName",
                        "Native PostgreSQL consumption",
                        "ownerTeam",
                        "warehouse_team"));
        subject =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("entity." + domain + ".user"),
                                        "family",
                                        "BUSINESS_ENTITY",
                                        "description",
                                        "User identity",
                                        "properties",
                                        List.of(property("user_id", "STRING", true, false)),
                                        "identityKeys",
                                        List.of("user_id"))));
        rowSchema =
                publish(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("dataset." + domain + ".event_row"),
                                        "family",
                                        "DATASET",
                                        "description",
                                        "Native event row projection",
                                        "properties",
                                        List.of(
                                                property("event_id", "STRING", true, false),
                                                property("user_id", "STRING", true, false),
                                                property("event_time", "TIMESTAMP", true, false),
                                                property("sensitive_value", "STRING", false, true)),
                                        "identityKeys",
                                        List.of("event_id"))));
        var imported =
                ok(
                        "POST",
                        "/api/sources/postgres/imports",
                        AUTHOR,
                        Map.of("externalRef", "consumption.events"));
        assertEquals("IMPORTED", imported.path("status").asText(), imported.toString());
        assertTrue(imported.path("fingerprint").asText().matches("[a-f0-9]{64}"));
        var definition =
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
        definition.set(
                "bindings",
                json.parse(
                        json.write(
                                List.of(
                                        Map.of(
                                                "sourceSystem",
                                                "postgres",
                                                "externalRef",
                                                "consumption.events",
                                                "fingerprint",
                                                imported.path("fingerprint").asText(),
                                                "timeFields",
                                                Map.of("EVENT_TIME", "event_time"))))));
        dataset = publish("Dataset", definition);
        function =
                publish(
                        "FunctionDefinition",
                        spec(
                                model(
                                        "ref",
                                        ref("function." + domain + ".extract"),
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
    }

    ObjectNode request(String key) {
        return json.object(
                json.write(
                        Map.of(
                                "protocolVersion",
                                1,
                                "intent",
                                "EXTRACT",
                                "externalTaskRef",
                                Map.of("issuer", "integration", "id", "external_" + key),
                                "proposedFunction",
                                Map.of(
                                        "releaseId",
                                        function.path("id").asText(),
                                        "contentHash",
                                        function.path("content_hash").asText()),
                                "requirements",
                                Map.of(
                                        "fields",
                                        List.of("event_id", "user_id", "event_time"),
                                        "from",
                                        FROM,
                                        "to",
                                        TO,
                                        "view",
                                        "OBSERVED_SNAPSHOT"),
                                "consumer",
                                Map.of("purpose", PURPOSE),
                                "constraints",
                                Map.of("maxRows", 20, "maxBytes", 4096),
                                "idempotencyKey",
                                key)));
    }

    static String key() {
        return "consume_" + UUID.randomUUID().toString().replace("-", "");
    }

    static Map<String, String> ref(String id) {
        return Map.of("id", id, "modelVersion", "1.0.0");
    }

    static Map<String, Object> property(
            String name, String type, boolean required, boolean sensitive) {
        return Map.of("name", name, "type", type, "required", required, "sensitive", sensitive);
    }

    static Map<String, Object> model(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    ObjectNode spec(Map<String, Object> definition) {
        return json.object(
                json.write(
                        Map.of(
                                "schemaVersion",
                                2,
                                "name",
                                "Consumption fixture",
                                "definition",
                                "Published real source binding",
                                "ownerTeam",
                                "warehouse_team",
                                "model",
                                definition)));
    }

    JsonNode createDraft(String type, JsonNode spec) throws Exception {
        return ok(
                "POST",
                "/api/drafts",
                AUTHOR,
                Map.of(
                        "domain",
                        domain(),
                        "entityId",
                        spec.path("model").path("ref").path("id").asText(),
                        "entityType",
                        type,
                        "spec",
                        spec));
    }

    JsonNode publish(String type, JsonNode spec) throws Exception {
        String id = createDraft(type, spec).path("id").asText();
        ok("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
        return ok("POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", "1.0.0"));
    }

    HttpResponse<String> send(String method, String path, String token, Object body)
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

    JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = send(method, path, token, body);
        assertEquals(
                200, response.statusCode(), () -> method + " " + path + ": " + response.body());
        return json.parse(response.body());
    }
}
