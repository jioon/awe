package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real JWT authentication must reach the same mandatory source policy as a service identity. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=metric-query-author",
            "awe.auth.reviewer-token=metric-query-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class DelegatedMetricQueryIntegrationTest extends MetricQueryTestSupport {
    private static final String DELEGATED_DOMAIN = "metric_delegated";
    private static final String ISSUER = "http://127.0.0.1/metric-test-idp";
    private static final String AUDIENCE = "awe-metric-consumption";
    private static final KeyPair KEYS = keys();
    private JsonNode function;

    @DynamicPropertySource
    static void delegatedNativeQuery(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
        properties.add(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                () -> "{\"postgres:metric.events\":\"public.awe_metric_events\"}");
        properties.add("AWE_CONSUMER_POLICIES", () -> policies(true, true));
        properties.add("AWE_OIDC_ISSUER", () -> ISSUER);
        properties.add("AWE_OIDC_AUDIENCE", () -> AUDIENCE);
        properties.add("AWE_OIDC_JWKS_JSON", DelegatedMetricQueryIntegrationTest::publicKeys);
        properties.add("AWE_OIDC_JWKS_URL", () -> "");
        properties.add("AWE_HIVE_METADATA_URL", () -> "");
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_BI_QUERY_URL", () -> "");
        properties.add("AWE_DATA_READINESS_URL", () -> "");
        properties.add("AWE_METRIC_VERIFICATION_URL", () -> "");
    }

    @BeforeAll
    void publishDelegatedMetric() throws Exception {
        createDomain(DELEGATED_DOMAIN);
        publishEventSource(DELEGATED_DOMAIN);
        publish(
                "Metric",
                metricSpec(
                        DELEGATED_DOMAIN,
                        "dau",
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
                        null));
        function = publish("FunctionDefinition", queryFunctionSpec(DELEGATED_DOMAIN, "dau", false));
    }

    @Test
    void actualJwtDelegationReceivesOnlyTheMandatoryNativeChannelRows() throws Exception {
        String token = token(AUDIENCE, "approved-client", "service-agent");
        JsonNode operation = ok("POST", "/api/data-tasks", token, metricRequest(function, key()));
        assertEquals("QUERIED", operation.path("status").asText(), operation.toString());
        assertEquals("metric-delegated-alice", operation.path("principal").asText());
        assertEquals(2, operation.path("result").path("rows").get(0).path("value").asInt());
        long all =
                ((Number)
                                oracle(
                                                "SELECT COUNT(DISTINCT user_id) AS value FROM "
                                                        + EVENTS
                                                        + " WHERE event_time>=? AND event_time<?")
                                        .getFirst()
                                        .get("value"))
                        .longValue();
        long allowed =
                ((Number)
                                oracle(
                                                "SELECT COUNT(DISTINCT user_id) AS value FROM "
                                                        + EVENTS
                                                        + " WHERE event_time>=? AND event_time<? AND channel='native'")
                                        .getFirst()
                                        .get("value"))
                        .longValue();
        assertEquals(3, all);
        assertEquals(2, allowed);
        assertEquals(allowed, operation.path("result").path("rows").get(0).path("value").asLong());
        JsonNode identity = operation.path("authenticatedIdentity");
        assertEquals("OIDC_JWT", identity.path("mechanism").asText());
        assertEquals("alice", identity.path("subject").asText());
        assertEquals("approved-client", identity.path("authorizedParty").asText());
        assertEquals("service-agent", identity.path("delegatedActors").get(0).asText());
        assertFalse(operation.toString().contains(token));
        assertFalse(identity.has("roles"));
        var forged = metricRequest(function, key());
        ((ObjectNode) forged.path("consumer")).put("role", "admin");
        assertEquals(400, send("POST", "/api/data-tasks", token, forged).statusCode());
        assertEquals(
                401,
                send(
                                "POST",
                                "/api/data-tasks",
                                token("another-api", "approved-client", "service-agent"),
                                metricRequest(function, key()))
                        .statusCode());
        assertTrue(
                send(
                                        "POST",
                                        "/api/data-tasks",
                                        token(AUDIENCE, "unapproved-client", "service-agent"),
                                        metricRequest(function, key()))
                                .statusCode()
                        >= 400);
        assertTrue(
                send(
                                        "POST",
                                        "/api/data-tasks",
                                        token(AUDIENCE, "approved-client", "unapproved-agent"),
                                        metricRequest(function, key()))
                                .statusCode()
                        >= 400);
    }

    @Test
    void currentInputOrOutputRevocationHidesEvenPreviouslySuccessfulEvidence() throws Exception {
        String token = token(AUDIENCE, "approved-client", "service-agent");
        JsonNode operation = ok("POST", "/api/data-tasks", token, metricRequest(function, key()));
        assertEquals("QUERIED", operation.path("status").asText(), operation.toString());
        String operationId = operation.path("id").asText();
        int original = port;
        for (boolean keepOutput : List.of(true, false)) {
            Map<String, Object> settings = secondarySettings(policies(keepOutput, !keepOutput));
            try (var restricted =
                    new SpringApplicationBuilder(RegistryApplication.class)
                            .registerShutdownHook(false)
                            .initializers(
                                    context ->
                                            context.getEnvironment()
                                                    .getPropertySources()
                                                    .addFirst(
                                                            new MapPropertySource(
                                                                    "revoked-metric-scope",
                                                                    settings)))
                            .run()) {
                port = ((WebServerApplicationContext) restricted).getWebServer().getPort();
                assertEquals(
                        404,
                        send("GET", "/api/data-tasks/" + operationId, token, null).statusCode());
                assertEquals(
                        403,
                        send("POST", "/api/data-tasks", token, metricRequest(function, key()))
                                .statusCode());
                assertTrue(ok("GET", "/api/capabilities", token, null).isEmpty());
            } finally {
                port = original;
            }
        }
    }

    private static Map<String, Object> secondarySettings(String policy) {
        var settings = new LinkedHashMap<String, Object>();
        settings.put("server.port", "0");
        settings.put("server.address", "127.0.0.1");
        settings.put("awe.auth.author-token", AUTHOR);
        settings.put("awe.auth.reviewer-token", REVIEWER);
        settings.put("AWE_WAREHOUSE_JDBC_URL", Objects.requireNonNull(System.getenv("AWE_DB_URL")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_USER", Objects.requireNonNull(System.getenv("AWE_DB_USER")));
        settings.put(
                "AWE_WAREHOUSE_JDBC_PASSWORD",
                Objects.requireNonNull(System.getenv("AWE_DB_PASSWORD")));
        settings.put(
                "AWE_WAREHOUSE_ASSET_BINDINGS",
                "{\"postgres:metric.events\":\"public.awe_metric_events\"}");
        settings.put("AWE_CONSUMER_POLICIES", policy);
        settings.put("AWE_OIDC_ISSUER", ISSUER);
        settings.put("AWE_OIDC_AUDIENCE", AUDIENCE);
        settings.put("AWE_OIDC_JWKS_JSON", publicKeys());
        settings.put("AWE_OIDC_JWKS_URL", "");
        settings.put("AWE_BI_QUERY_URL", "");
        settings.put("AWE_DATA_READINESS_URL", "");
        settings.put("AWE_METRIC_VERIFICATION_URL", "");
        return settings;
    }

    private static String policies(boolean allowOutput, boolean allowIdentityInput) {
        var fields = new ArrayList<>(List.of("event_id", "event_time", "channel"));
        if (allowIdentityInput) fields.add("user_id");
        return new JsonDocuments()
                .write(
                        List.of(
                                Map.of(
                                        "principal",
                                        "metric-delegated-alice",
                                        "oidc",
                                        Map.of(
                                                "issuer",
                                                ISSUER,
                                                "subject",
                                                "alice",
                                                "authorizedParty",
                                                "approved-client",
                                                "delegatedActors",
                                                List.of("service-agent")),
                                        "functionIds",
                                        List.of("function." + DELEGATED_DOMAIN + ".dau"),
                                        "purposes",
                                        List.of(PURPOSE),
                                        "fields",
                                        allowOutput ? List.of("value") : List.of("revoked_output"),
                                        "maxRows",
                                        100,
                                        "maxBytes",
                                        16384,
                                        "maxWindowSeconds",
                                        86400,
                                        "rowScopes",
                                        List.of(
                                                Map.of(
                                                        "datasetId",
                                                        "dataset." + DELEGATED_DOMAIN + ".events",
                                                        "fields",
                                                        fields,
                                                        "filters",
                                                        List.of(
                                                                Map.of(
                                                                        "field",
                                                                        "channel",
                                                                        "operation",
                                                                        "EQ",
                                                                        "type",
                                                                        "STRING",
                                                                        "values",
                                                                        List.of("native"))))))));
    }

    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String publicKeys() {
        RSAPublicKey key = (RSAPublicKey) KEYS.getPublic();
        return new JsonDocuments()
                .write(
                        Map.of(
                                "keys",
                                List.of(
                                        Map.of(
                                                "kty",
                                                "RSA",
                                                "kid",
                                                "metric-idp-key",
                                                "alg",
                                                "RS256",
                                                "use",
                                                "sig",
                                                "n",
                                                unsigned(key.getModulus()),
                                                "e",
                                                unsigned(key.getPublicExponent())))));
    }

    private static String unsigned(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String token(String audience, String party, String actor)
            throws GeneralSecurityException {
        var json = new JsonDocuments();
        Instant now = Instant.now();
        var claims =
                Map.of(
                        "iss",
                        ISSUER,
                        "sub",
                        "alice",
                        "aud",
                        audience,
                        "iat",
                        now.getEpochSecond(),
                        "exp",
                        now.plusSeconds(180).getEpochSecond(),
                        "azp",
                        party,
                        "act",
                        Map.of("sub", actor),
                        "roles",
                        List.of("admin"));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header =
                encoder.encodeToString(
                        json.write(Map.of("alg", "RS256", "typ", "JWT", "kid", "metric-idp-key"))
                                .getBytes(StandardCharsets.UTF_8));
        String payload =
                encoder.encodeToString(json.write(claims).getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(KEYS.getPrivate());
        signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + encoder.encodeToString(signature.sign());
    }
}
