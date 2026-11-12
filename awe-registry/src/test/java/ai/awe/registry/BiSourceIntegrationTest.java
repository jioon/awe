package ai.awe.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Configured BI HTTP adapter contract over real Registry/PostgreSQL, not actual enterprise BI access. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "awe.auth.author-token=bi-integration-author",
    "awe.auth.reviewer-token=bi-integration-reviewer"
})
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class BiSourceIntegrationTest {
    private static MetadataGatewayFixture gateway;
    private static final String AUTHOR = "bi-integration-author", REVIEWER = "bi-integration-reviewer";
    @Autowired JsonDocuments json;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void sourceConfiguration(DynamicPropertyRegistry properties) {
        properties.add("AWE_HIVE_METADATA_URL", () -> gateway().baseUrl());
        properties.add("AWE_BI_METADATA_URL", () -> gateway().baseUrl());
        properties.add("AWE_HIVE_METADATA_TOKEN", () -> "");
        properties.add("AWE_BI_METADATA_TOKEN", () -> "");
    }
    private static synchronized MetadataGatewayFixture gateway() {
        if (gateway == null) gateway = new MetadataGatewayFixture();
        return gateway;
    }
    @AfterAll static void closeGateway() { if (gateway != null) gateway.close(); }

    @Test void configuredBiMetricRequiresBothLiveMetadataAndExactDeclaredSemantics() throws Exception {
        String domain = "bi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ok("POST", "/api/domains", AUTHOR, Map.of("code", domain, "displayName", "BI adapter contract", "ownerTeam", "warehouse_team"));
        publish(domain, "ObjectType", spec(model("ref", ref("entity." + domain + ".user", "1.0.0"),
            "family", "BUSINESS_ENTITY", "description", "Source user identity",
            "properties", List.of(Map.of("name", "user_id", "type", "STRING", "required", true, "sensitive", false)),
            "identityKeys", List.of("user_id"))));

        String hiveAsset = "hive." + domain + ".events";
        gateway().put(hiveAsset, metadata(hiveAsset, "event_id", "v1"));
        var hive = ok("POST", "/api/sources/hive/imports", AUTHOR, Map.of("externalRef", hiveAsset));
        assertEquals("IMPORTED", hive.path("status").asText());
        var dataset = spec(model("ref", ref("dataset." + domain + ".events", "1.0.0"),
            "grain", Map.of("kind", "EVENT", "keys", List.of("event_id")), "time", time(),
            "subjectType", ref("entity." + domain + ".user", "1.0.0"), "dimensions", List.of()));
        bind(dataset, "hive", hiveAsset, hive.path("fingerprint").asText());
        publish(domain, "Dataset", dataset);

        String biAsset = "bi." + domain + ".dau";
        var metric = spec(model("ref", ref("metric." + domain + ".dau", "1.0.0"),
            "grain", Map.of("kind", "AGGREGATE", "keys", List.of("user_id")), "time", time(),
            "subjectType", ref("entity." + domain + ".user", "1.0.0"), "sourceDataset", ref("dataset." + domain + ".events", "1.0.0"),
            "aggregation", Map.of("kind", "COUNT_DISTINCT", "permittedRollupAxes", List.of(), "forbiddenRollupAxes", List.of("day")),
            "allowedDimensions", List.of()));
        var authoritative = metadata(biAsset, "user_id", "v1");
        ObjectNode definition = ((ObjectNode) new TypedModels().canonicalSpec("Metric", metric).path("model")).deepCopy();
        definition.remove("ref");
        authoritative.set("definition", definition);
        gateway().put(biAsset, authoritative);
        var observed = ok("POST", "/api/sources/bi/imports", AUTHOR, Map.of("externalRef", biAsset));
        assertEquals("IMPORTED", observed.path("status").asText());
        bind(metric, "bi", biAsset, observed.path("fingerprint").asText());
        var release = publish(domain, "Metric", metric);
        assertEquals("VERIFIED", release.path("bindings").path(0).path("verification_status").asText());
        assertEquals("COUNT_DISTINCT", release.path("spec").path("model").path("aggregation").path("kind").asText());

        // Matching physical schema and freshly imported fingerprint still cannot hide a changed BI definition.
        ObjectNode changed = authoritative.deepCopy();
        changed.put("sourceVersion", "v2");
        ((ObjectNode) changed.path("definition").path("aggregation")).put("kind", "SUM");
        gateway().put(biAsset, changed);
        var changedObservation = ok("POST", "/api/sources/bi/imports", AUTHOR, Map.of("externalRef", biAsset));
        assertEquals("IMPORTED", changedObservation.path("status").asText());
        ((ObjectNode) metric.path("model").path("ref")).put("modelVersion", "2.0.0");
        bind(metric, "bi", biAsset, changedObservation.path("fingerprint").asText());
        String draftId = ok("POST", "/api/drafts", AUTHOR, Map.of("domain", domain, "entityId", "metric." + domain + ".dau", "entityType", "Metric", "spec", metric)).path("id").asText();
        var report = ok("POST", "/api/drafts/" + draftId + "/validate", AUTHOR, Map.of());
        assertFalse(report.path("valid").asBoolean(), report.toString());
        assertTrue(report.toString().contains("CONTRACT_MISMATCH"), report.toString());
        assertTrue(report.toString().contains("Authoritative BI semantic definition"), report.toString());
        ok("POST", "/api/drafts/" + draftId + "/submit", AUTHOR, Map.of());
        ok("POST", "/api/drafts/" + draftId + "/approve", REVIEWER, Map.of());
        var rejected = send("POST", "/api/drafts/" + draftId + "/publish", REVIEWER, Map.of("version", "2.0.0"));
        assertTrue(rejected.statusCode() == 400 || rejected.statusCode() == 409, rejected.body());
        assertEquals("COUNT_DISTINCT", ok("GET", "/api/releases/" + release.path("id").asText(), AUTHOR, null)
            .path("spec").path("model").path("aggregation").path("kind").asText());
    }

    private ObjectNode spec(Map<String, Object> definition) {
        return json.object(json.write(Map.of("schemaVersion", 2, "name", "BI integration contract", "definition", "Warehouse-owned metric semantics", "ownerTeam", "warehouse_team", "model", definition)));
    }
    private static Map<String, String> ref(String id, String version) { return Map.of("id", id, "modelVersion", version); }
    private static Map<String, Object> time() { return Map.of("roles", List.of("EVENT_TIME", "AVAILABLE_AT"), "businessZone", "Asia/Shanghai", "backfillPossible", true); }
    private static Map<String, Object> model(Object... entries) {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) values.put((String) entries[i], entries[i + 1]);
        return values;
    }
    private ObjectNode metadata(String external, String grain, String version) {
        return json.object(json.write(Map.of("externalRef", external, "sourceVersion", version,
            "columns", List.of(Map.of("name", "event_id", "type", "STRING", "nullable", false), Map.of("name", "user_id", "type", "STRING", "nullable", false),
                Map.of("name", "event_time", "type", "TIMESTAMP", "nullable", false), Map.of("name", "available_at", "type", "TIMESTAMP", "nullable", false)),
            "grainKeys", List.of(grain), "timeFields", List.of("event_time", "available_at"))));
    }
    private void bind(ObjectNode spec, String system, String external, String fingerprint) {
        spec.set("bindings", json.parse(json.write(List.of(Map.of("sourceSystem", system, "externalRef", external, "fingerprint", fingerprint,
            "timeFields", Map.of("EVENT_TIME", "event_time", "AVAILABLE_AT", "available_at"))))));
    }
    private JsonNode publish(String domain, String type, JsonNode spec) throws Exception {
        String id = ok("POST", "/api/drafts", AUTHOR, Map.of("domain", domain, "entityId", spec.path("model").path("ref").path("id").asText(), "entityType", type, "spec", spec)).path("id").asText();
        ok("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
        return ok("POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", spec.path("model").path("ref").path("modelVersion").asText()));
    }
    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = send(method, path, token, body);
        assertEquals(200, response.statusCode(), () -> method + " " + path + ": " + response.body());
        return json.parse(response.body());
    }
}
