package ai.awe.registry.identity;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.RelationalIR;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** 身份反例使用真实 RSA 签名与本机 JWKS HTTP，不以解码 payload 冒充验签。 */
class TrustedIdentityTest {
    private static final String ISSUER = "https://identity.example.test";
    private static final String AUDIENCE = "awe-consumption";
    private static final String FUNCTION = "function.video.summary";
    private static final Instant FROM = Instant.parse("2026-11-12T00:00:00Z");
    private static RSAKey signingKey, outsider;
    private final JsonDocuments json = new JsonDocuments();

    @BeforeAll
    static void realVerificationKeys() throws Exception {
        signingKey =
                new RSAKeyGenerator(2048)
                        .keyID("trusted-rotation-key")
                        .algorithm(JWSAlgorithm.RS256)
                        .generate();
        outsider =
                new RSAKeyGenerator(2048)
                        .keyID("untrusted-key")
                        .algorithm(JWSAlgorithm.RS256)
                        .generate();
    }

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("AWE_OIDC_ISSUER", ISSUER)
                .withProperty("AWE_OIDC_AUDIENCE", AUDIENCE)
                .withProperty(
                        "AWE_OIDC_JWKS_JSON", new JWKSet(signingKey.toPublicJWK()).toString());
    }

    private String token(Consumer<JWTClaimsSet.Builder> changes) throws Exception {
        return token(signingKey, signingKey.getKeyID(), changes);
    }

    private String token(RSAKey key, String kid, Consumer<JWTClaimsSet.Builder> changes)
            throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject("sso-user-42")
                        .audience(AUDIENCE)
                        .issueTime(Date.from(Instant.now().minusSeconds(1)))
                        .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim("azp", "data-agent");
        changes.accept(claims);
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .type(JOSEObjectType.JWT)
                                .keyID(kid)
                                .build(),
                        claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private ObjectNode oidcPolicy() {
        return json.object(
                """
                {"principal":"domain-user-42","oidc":{"issuer":"https://identity.example.test","subject":"sso-user-42","authorizedParty":"data-agent","delegatedActors":["trusted-agent-service"]},
                 "functionIds":["function.video.summary"],"purposes":["analysis"],"fields":["count"],
                 "maxRows":100,"maxBytes":4096,"maxWindowSeconds":86400,
                 "rowScopes":[{"datasetId":"dataset.video.events","fields":["tenant_id","user_id"],
                    "filters":[{"field":"tenant_id","operation":"EQ","type":"STRING","values":["tenant-a"]}]}]}
                """);
    }

    private ConsumerAccess access(ObjectNode policy) {
        return new ConsumerAccess(
                environment().withProperty("AWE_CONSUMER_POLICIES", json.write(List.of(policy))),
                json);
    }

    private void status(int code, Runnable operation) {
        var denied = assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(code, denied.getStatusCode().value());
        assertNull(denied.getCause());
        assertFalse(denied.getMessage().contains("eyJ"));
    }

    @Test
    void verifiesActualRs256AndMapsOnlyServerOwnedPrincipalAndScopes() throws Exception {
        var access = access(oidcPolicy());
        String jwt =
                token(
                        claims ->
                                claims.claim("roles", List.of("admin"))
                                        .claim("tenant", "tenant-b"));
        var authenticated = access.authenticateContext(jwt);
        assertEquals("domain-user-42", authenticated.grant().principal());
        assertEquals(Set.of("count"), authenticated.grant().fields());
        assertEquals("sso-user-42", authenticated.identity().subject());
        assertEquals("data-agent", authenticated.identity().authorizedParty());
        assertEquals("OIDC_JWT", authenticated.identity().mechanism());
        assertFalse(authenticated.identity().toEvidence().toString().contains(jwt));
        assertDoesNotThrow(
                () ->
                        access.authorizeQuery(
                                authenticated.grant(),
                                FUNCTION,
                                "analysis",
                                List.of("count"),
                                10,
                                1024,
                                FROM,
                                FROM.plusSeconds(3600)));
        status(
                403,
                () ->
                        access.authorizeQuery(
                                authenticated.grant(),
                                "function.other.all",
                                "analysis",
                                List.of("count"),
                                10,
                                1024,
                                FROM,
                                FROM.plusSeconds(3600)));
        status(
                403,
                () ->
                        access.authorize(
                                authenticated.grant(),
                                FUNCTION,
                                "analysis",
                                List.of("count"),
                                10,
                                1024,
                                FROM,
                                FROM.plusSeconds(3600)));
    }

    @Test
    void rejectsWrongSignatureUnknownKeyIssuerAudienceExpiryNbfAndMissingExpiry() throws Exception {
        var verifier = new TrustedOidcVerifier(environment(), json);
        List<String> bad = new ArrayList<>();
        bad.add(token(outsider, signingKey.getKeyID(), claims -> {}));
        bad.add(token(signingKey, "unknown-key", claims -> {}));
        bad.add(token(claims -> claims.issuer("https://attacker.example")));
        bad.add(token(claims -> claims.audience("some-other-api")));
        bad.add(token(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120)))));
        bad.add(token(claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(120)))));
        bad.add(token(claims -> claims.expirationTime(null)));
        bad.add(token(claims -> claims.subject(null)));
        for (String jwt : bad) status(401, () -> verifier.verify(jwt));
    }

    @Test
    void rejectsTamperedPayloadUnsignedJwtAndMissingTrustConfiguration() throws Exception {
        var verifier = new TrustedOidcVerifier(environment(), json);
        String good = token(claims -> {});
        String[] pieces = good.split("\\.");
        String forgedClaims =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                json.canonical(
                                                json.object(
                                                                new String(
                                                                        Base64.getUrlDecoder()
                                                                                .decode(pieces[1]),
                                                                        StandardCharsets.UTF_8))
                                                        .put("sub", "administrator"))
                                        .getBytes(StandardCharsets.UTF_8));
        status(401, () -> verifier.verify(pieces[0] + "." + forgedClaims + "." + pieces[2]));
        String none =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"none\",\"kid\":\"trusted-rotation-key\"}"
                                        .getBytes(StandardCharsets.UTF_8));
        status(401, () -> verifier.verify(none + "." + pieces[1] + "."));
        status(503, () -> new TrustedOidcVerifier(new MockEnvironment(), json).verify(good));
        status(
                503,
                () ->
                        new TrustedOidcVerifier(
                                        environment()
                                                .withProperty(
                                                        "AWE_OIDC_JWKS_JSON",
                                                        new JWKSet(signingKey).toString(false)),
                                        json)
                                .verify(good));
    }

    @Test
    void bindsAuthorizedPartyAndExplicitDelegationRatherThanTrustingRoleClaims() throws Exception {
        var access = access(oidcPolicy());
        String wrongParty = token(claims -> claims.claim("azp", "other-agent"));
        String unknownUser = token(claims -> claims.subject("sso-user-99"));
        String badActor =
                token(claims -> claims.claim("act", Map.of("sub", "other-agent-service")));
        String allowedActor =
                token(claims -> claims.claim("act", Map.of("sub", "trusted-agent-service")));
        status(401, () -> access.authenticate(wrongParty));
        status(401, () -> access.authenticate(unknownUser));
        status(401, () -> access.authenticate(badActor));
        assertEquals(
                List.of("trusted-agent-service"),
                access.authenticateContext(allowedActor).identity().delegatedActors());
    }

    @Test
    void scopesActualDatasetAliasAndTypeWithoutAcceptingCallerConstructedGrant() throws Exception {
        var access = access(oidcPolicy());
        var grant = access.authenticate(token(claims -> {}));
        var rowSchema =
                new ObjectType(
                        new EntityRef("dataset.video.event_row", "1.0.0"),
                        ObjectType.Family.DATASET,
                        "Actual event row",
                        List.of(
                                new ObjectType.Property(
                                        "tenant_id", ObjectType.DataType.STRING, true, false),
                                new ObjectType.Property(
                                        "user_id", ObjectType.DataType.STRING, true, false)),
                        List.of("user_id"));
        var predicate =
                access.scope(grant, "dataset.video.events", "events", rowSchema).orElseThrow();
        assertEquals(
                new RelationalIR.Compare(
                        RelationalIR.CompareOp.EQ,
                        new RelationalIR.Field("events.tenant_id"),
                        new RelationalIR.Literal(ObjectType.DataType.STRING, "tenant-a")),
                predicate);
        assertEquals(
                Set.of("tenant_id", "user_id"), access.inputFields(grant, "dataset.video.events"));
        status(403, () -> access.scope(grant, "dataset.other.events", "events", rowSchema));
        var widened =
                new ConsumerAccess.Grant(
                        grant.principal(),
                        grant.functionIds(),
                        grant.purposes(),
                        grant.fields(),
                        grant.maxRows(),
                        grant.maxBytes(),
                        grant.maxWindowSeconds(),
                        grant.policyHash());
        status(403, () -> access.scope(widened, "dataset.video.events", "events", rowSchema));
        var wrong =
                new ObjectType(
                        rowSchema.ref(),
                        rowSchema.family(),
                        rowSchema.description(),
                        List.of(
                                new ObjectType.Property(
                                        "tenant_id", ObjectType.DataType.INTEGER, true, false),
                                new ObjectType.Property(
                                        "user_id", ObjectType.DataType.STRING, true, false)),
                        List.of("user_id"));
        status(403, () -> access.scope(grant, "dataset.video.events", "events", wrong));
    }

    @Test
    void validatesRowRulesAndHashesPolicyIndependentlyOfScopeSetOrder() throws Exception {
        var policy = oidcPolicy();
        var grant = access(policy).authenticate(token(claims -> {}));
        var reordered = oidcPolicy();
        ((ObjectNode) reordered.path("rowScopes").get(0))
                .putArray("fields")
                .add("user_id")
                .add("tenant_id");
        assertEquals(
                grant.policyHash(),
                access(reordered).authenticate(token(claims -> {})).policyHash());
        var bad = oidcPolicy();
        ((ObjectNode) bad.path("rowScopes").get(0).path("filters").get(0)).put("operation", "SQL");
        status(503, () -> access(bad).authenticate("not-used"));
        var badType = oidcPolicy();
        ((ObjectNode) badType.path("rowScopes").get(0).path("filters").get(0))
                .put("type", "INTEGER");
        status(503, () -> access(badType).authenticate("not-used"));
    }

    @Test
    void fetchesOnlyConfiguredJwksAndCachesSuccessfulBoundedVerification() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/keys",
                exchange -> {
                    hits.incrementAndGet();
                    byte[] data =
                            new JWKSet(signingKey.toPublicJWK())
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/jwk-set+json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var env = environment();
            env.setProperty("AWE_OIDC_JWKS_JSON", "");
            env.setProperty(
                    "AWE_OIDC_JWKS_URL",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/keys");
            var verifier = new TrustedOidcVerifier(env, json);
            String jwt = token(claims -> {});
            assertEquals("sso-user-42", verifier.verify(jwt).subject());
            assertEquals("sso-user-42", verifier.verify(jwt).subject());
            assertEquals(1, hits.get());
            String unknown = token(signingKey, "missing-kid", claims -> {});
            status(401, () -> verifier.verify(unknown));
            assertEquals(1, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsJwksRedirectsAndOversizedBodiesWithoutFollowingTokenChosenUrls() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "/other");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/other",
                exchange -> {
                    followed.incrementAndGet();
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                });
        server.createContext(
                "/oversized",
                exchange -> {
                    byte[] data = " ".repeat(270_000).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    try {
                        exchange.getResponseBody().write(data);
                    } catch (java.io.IOException ignored) {
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            String jwt = token(claims -> {});
            for (String path : List.of("redirect", "oversized")) {
                var env = environment();
                env.setProperty("AWE_OIDC_JWKS_JSON", "");
                env.setProperty(
                        "AWE_OIDC_JWKS_URL",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/" + path);
                var verifier = new TrustedOidcVerifier(env, json);
                status(503, () -> verifier.verify(jwt));
            }
            assertEquals(0, followed.get());
        } finally {
            server.stop(0);
        }
    }
}
