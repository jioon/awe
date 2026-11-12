package ai.awe.registry.source;

import ai.awe.registry.JsonDocuments;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP transport against an isolated test gateway; does not claim a live Hive/BI connection. */
class HttpMetadataAdapterTest {
    private final JsonDocuments json = new JsonDocuments();
    private HttpServer server;
    private String response;
    private int code;
    private final List<String> requested = new ArrayList<>();
    @BeforeEach void start() throws Exception {
        response = document("v1", "[{\"name\":\"event_time\",\"type\":\"TIMESTAMP\",\"nullable\":false},{\"name\":\"event_id\",\"type\":\"STRING\",\"nullable\":false}]");
        code = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/assets/", exchange -> {
            requested.add(exchange.getRequestURI().getRawPath());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            if (code == 302) exchange.getResponseHeaders().add("Location", "/must-not-follow");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private String document(String version, String columns) {
        return "{\"externalRef\":\"hive.db.events\",\"sourceVersion\":\"" + version + "\",\"columns\":" + columns
            + ",\"grainKeys\":[\"event_id\"],\"timeFields\":[\"event_time\"]}";
    }
    private HttpMetadataAdapter adapter() {
        return new HttpMetadataAdapter("hive", "http://127.0.0.1:" + server.getAddress().getPort(), "local-test-only", json);
    }
    @Test void absentConnectionIsExplicitAndDoesNotMakeARequest() {
        var adapter = new HttpMetadataAdapter("hive", "", "", json);
        assertFalse(adapter.configured());
        var observation = adapter.fetch("hive.db.events");
        assertEquals("NOT_CONFIGURED", observation.status());
        assertNull(observation.metadata());
        assertTrue(requested.isEmpty());
    }
    @Test void importsRealHttpMetadataAndCanonicalizesSchemaWithoutClaimingDataTruth() {
        var first = adapter().fetch("hive.db.events");
        assertEquals("IMPORTED", first.status());
        assertEquals("event_id", first.metadata().path("columns").get(0).path("name").asText());
        assertEquals("string", first.metadata().path("columns").get(0).path("type").asText());
        assertFalse(first.limitations().isEmpty());
        response = document("v1", "[{\"nullable\":false,\"type\":\"string\",\"name\":\"event_id\"},{\"nullable\":false,\"type\":\"timestamp\",\"name\":\"event_time\"}]");
        var reordered = adapter().fetch("hive.db.events");
        assertEquals(json.sha256(first.metadata()), json.sha256(reordered.metadata()));
        response = response.replace("v1", "v2");
        assertNotEquals(json.sha256(first.metadata()), json.sha256(adapter().fetch("hive.db.events").metadata()));
        assertEquals(List.of("/assets/hive.db.events", "/assets/hive.db.events", "/assets/hive.db.events"), requested);
    }
    @Test void failedCoverageAndRedirectsNeverBecomeSuccess() {
        code = 404;
        assertEquals("NOT_FOUND", adapter().fetch("hive.db.events").status());
        code = 302;
        assertEquals("UNAVAILABLE", adapter().fetch("hive.db.events").status());
        assertEquals(2, requested.size());
    }
    @Test void rejectsSpoofedIdentityUnknownFieldsMissingColumnsAndInvalidPrimitive() {
        response = response.replace("hive.db.events", "hive.other.asset");
        assertEquals("INVALID_RESPONSE", adapter().fetch("hive.db.events").status());
        response = document("v1", "[]");
        assertEquals("INVALID_RESPONSE", adapter().fetch("hive.db.events").status());
        response = document("v1", "[{\"name\":\"event_id\",\"type\":\"string\",\"nullable\":\"false\"}]");
        assertEquals("INVALID_RESPONSE", adapter().fetch("hive.db.events").status());
        response = document("v1", "[{\"name\":\"event_id\",\"type\":\"string\",\"nullable\":false}]").replace("\"grainKeys\"", "\"surprise\"");
        assertEquals("INVALID_RESPONSE", adapter().fetch("hive.db.events").status());
    }
    @Test void rejectsOversizedGatewayBodyBeforeUnboundedBuffering() {
        response = " ".repeat(1_048_577);
        assertEquals("INVALID_RESPONSE", adapter().fetch("hive.db.events").status());
    }
    @Test void nonTlsRemoteAndEmbeddedCredentialsAreNotConfigured() {
        assertFalse(new HttpMetadataAdapter("hive", "http://example.com", "", json).configured());
        var unsafe = new HttpMetadataAdapter("hive", "https://secret@example.com", "", json);
        assertFalse(unsafe.configured());
        assertEquals("UNAVAILABLE", unsafe.fetch("hive.db.events").status());
    }
    @Test void honorsInheritedProxyAndNoProxyWithoutExposingCredentials() {
        var selector = new EnvironmentProxySelector(Map.of("HTTPS_PROXY", "http://127.0.0.1:1234", "NO_PROXY", ".internal.test"));
        assertEquals(Proxy.Type.HTTP, selector.select(URI.create("https://metadata.example.test")).getFirst().type());
        assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://127.0.0.1:8090")).getFirst());
        assertEquals(Proxy.NO_PROXY, selector.select(URI.create("https://api.internal.test")).getFirst());
    }
}
