package ai.awe.registry.consumption;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

class ConsumerAccessTest {
    private static final String TOKEN = "independent-consumer-unit-test-token";
    private static final String FUNCTION = "function.video.report";
    private static final Instant FROM = Instant.parse("2026-11-12T00:00:00Z");
    private final JsonDocuments json = new JsonDocuments();

    private ObjectNode policy() {
        return json.object(
                """
                {"principal":"reporting-service","token":"independent-consumer-unit-test-token",
                 "functionIds":["function.video.report","function.video.summary"],
                 "purposes":["reporting","analysis"],"fields":["user_id","event_count"],
                 "maxRows":100,"maxBytes":1024,"maxWindowSeconds":3600}
                """);
    }

    private ConsumerAccess access(ObjectNode... policies) {
        return new ConsumerAccess(
                new MockEnvironment()
                        .withProperty("AWE_CONSUMER_POLICIES", json.write(List.of(policies))),
                json);
    }

    private void assertStatus(int status, Runnable operation) {
        var error = assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(status, error.getStatusCode().value());
        assertFalse(error.toString().contains(TOKEN));
        assertNull(error.getCause());
    }

    @Test
    void authenticatesOnlyIndependentCredentialsAndReturnsImmutableTokenFreeGrant() {
        var access = access(policy());
        var grant = access.authenticate(TOKEN);
        assertEquals("reporting-service", grant.principal());
        assertTrue(grant.policyHash().matches("[a-f0-9]{64}"));
        assertFalse(grant.toString().contains(TOKEN));
        assertThrows(UnsupportedOperationException.class, () -> grant.fields().add("secret"));
        for (String bad : List.of("", "unknown", " " + TOKEN, TOKEN + " "))
            assertStatus(401, () -> access.authenticate(bad));
        assertStatus(401, () -> access.authenticate(null));
    }

    @Test
    void missingMalformedAndDuplicateJsonConfigurationsFailClosedWithoutCredentialErrors() {
        for (String raw :
                List.of(
                        "",
                        " ",
                        "null",
                        "{}",
                        "[]",
                        "[null]",
                        "[] []",
                        "[{\"principal\":\"a\",\"principal\":\"b\"}]",
                        "not-json")) {
            var access =
                    new ConsumerAccess(
                            new MockEnvironment().withProperty("AWE_CONSUMER_POLICIES", raw), json);
            assertStatus(503, () -> access.authenticate(TOKEN));
        }
    }

    @Test
    void rejectsUnknownMissingOrMistypedPolicyFieldsAndNonpositiveOrExcessiveLimits() {
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(policy().put("roles", "admin"));
        var missing = policy();
        missing.remove("maxWindowSeconds");
        invalid.add(missing);
        invalid.add(policy().put("principal", "bootstrap-author"));
        invalid.add(policy().put("principal", "x".repeat(101)));
        invalid.add(policy().put("principal", "service with spaces"));
        invalid.add(policy().put("token", " " + TOKEN));
        invalid.add(policy().put("maxRows", 0));
        invalid.add(policy().put("maxRows", -1));
        invalid.add(policy().put("maxRows", 100_001));
        invalid.add(policy().put("maxRows", 1.5));
        invalid.add(policy().put("maxRows", "100"));
        invalid.add(policy().put("maxBytes", 1_048_577));
        invalid.add(policy().put("maxWindowSeconds", 0));
        invalid.add(policy().put("maxWindowSeconds", "3600"));
        var oversized = policy();
        oversized.set("maxWindowSeconds", json.parse("9223372036854775808"));
        invalid.add(oversized);
        for (ObjectNode bad : invalid) assertStatus(503, () -> access(bad).authenticate(TOKEN));
    }

    @Test
    void rejectsEmptyDuplicatedWildcardOrMistypedScopes() {
        for (String field : List.of("functionIds", "purposes", "fields")) {
            for (String badValue :
                    List.of(
                            "[]",
                            "null",
                            "\"all\"",
                            "[null]",
                            "[\"\"]",
                            "[\"x\",\"x\"]",
                            "[\" x\"]",
                            "[\"x\\n\"]")) {
                var bad = policy();
                bad.set(field, json.parse(badValue));
                assertStatus(503, () -> access(bad).authenticate(TOKEN));
            }
        }
        for (String function :
                List.of("*", "dataset.video.events", "function.video", "function.Video.report")) {
            var bad = policy();
            bad.putArray("functionIds").add(function);
            assertStatus(503, () -> access(bad).authenticate(TOKEN));
        }
        for (String field : List.of("*", "user_id,secret", "table.user_id")) {
            var bad = policy();
            bad.putArray("fields").add(field);
            assertStatus(503, () -> access(bad).authenticate(TOKEN));
        }
    }

    @Test
    void oneInvalidOrDuplicatePolicyDisablesAllPrincipalsRatherThanPartiallyApplying() {
        var other =
                policy().put("principal", "another-service").put("token", "another-consumer-token");
        assertEquals(
                "another-service",
                access(policy(), other).authenticate("another-consumer-token").principal());
        assertStatus(503, () -> access(policy(), other.put("maxRows", 0)).authenticate(TOKEN));
        assertStatus(
                503,
                () -> access(policy(), policy().put("token", "other-token")).authenticate(TOKEN));
        assertStatus(
                503,
                () ->
                        access(policy(), policy().put("principal", "other-service"))
                                .authenticate(TOKEN));
    }

    @Test
    void rejectsTokensCollidingWithEnvironmentOrResolvedBootstrapProperties() {
        for (String key :
                List.of(
                        "AWE_AUTHOR_TOKEN",
                        "AWE_REVIEWER_TOKEN",
                        "awe.auth.author-token",
                        "awe.auth.reviewer-token")) {
            var env =
                    new MockEnvironment()
                            .withProperty("AWE_CONSUMER_POLICIES", json.write(List.of(policy())))
                            .withProperty(key, TOKEN);
            assertStatus(503, () -> new ConsumerAccess(env, json).authenticate(TOKEN));
        }
    }

    @Test
    void principalFitsExistingHundredCharacterAuditColumns() {
        String principal = "a".repeat(100);
        assertEquals(
                principal,
                access(policy().put("principal", principal)).authenticate(TOKEN).principal());
        assertStatus(
                503, () -> access(policy().put("principal", principal + "a")).authenticate(TOKEN));
    }

    @Test
    void policyHashCanonicalizesScopeOrderAndExcludesTokenButChangesWithAuthority() {
        var original = access(policy()).authenticate(TOKEN);
        var rotated = policy().put("token", "rotated-consumer-token");
        rotated.putArray("fields").add("event_count").add("user_id");
        rotated.putArray("purposes").add("analysis").add("reporting");
        rotated.putArray("functionIds").add("function.video.summary").add(FUNCTION);
        assertEquals(
                original.policyHash(),
                access(rotated).authenticate("rotated-consumer-token").policyHash());
        assertNotEquals(
                original.policyHash(),
                access(policy().put("maxRows", 99)).authenticate(TOKEN).policyHash());
        assertNotEquals(
                original.policyHash(),
                access(policy().put("principal", "other-service"))
                        .authenticate(TOKEN)
                        .policyHash());
    }

    @Test
    void authorizesExactScopeAndInclusiveQuotaAndTimeWindowBoundaries() {
        var access = access(policy());
        var grant = access.authenticate(TOKEN);
        assertDoesNotThrow(
                () ->
                        access.authorize(
                                grant,
                                FUNCTION,
                                "reporting",
                                List.of("user_id", "event_count"),
                                100,
                                1024,
                                FROM,
                                FROM.plusSeconds(3600)));
        assertDoesNotThrow(
                () ->
                        access.authorize(
                                grant,
                                FUNCTION,
                                "analysis",
                                List.of("user_id"),
                                1,
                                1,
                                FROM,
                                FROM.plusNanos(1)));
    }

    @Test
    void deniesFunctionPurposeFieldsBudgetAndPreciseTimeWindowEscalation() {
        var access = access(policy());
        var g = access.authenticate(TOKEN);
        assertStatus(
                403,
                () ->
                        access.authorize(
                                g,
                                "function.video.secret",
                                "reporting",
                                List.of("user_id"),
                                1,
                                1,
                                FROM,
                                FROM.plusSeconds(1)));
        assertStatus(
                403,
                () ->
                        access.authorize(
                                g,
                                null,
                                "reporting",
                                List.of("user_id"),
                                1,
                                1,
                                FROM,
                                FROM.plusSeconds(1)));
        assertStatus(
                403,
                () ->
                        access.authorize(
                                g,
                                FUNCTION,
                                "marketing",
                                List.of("user_id"),
                                1,
                                1,
                                FROM,
                                FROM.plusSeconds(1)));
        assertStatus(
                403,
                () ->
                        access.authorize(
                                g,
                                FUNCTION,
                                null,
                                List.of("user_id"),
                                1,
                                1,
                                FROM,
                                FROM.plusSeconds(1)));
        for (List<String> fields :
                List.of(
                        List.of("secret"),
                        List.of("*"),
                        List.of("user_id", "user_id"),
                        List.<String>of(),
                        Arrays.asList("user_id", null)))
            assertStatus(
                    403,
                    () ->
                            access.authorize(
                                    g,
                                    FUNCTION,
                                    "reporting",
                                    fields,
                                    1,
                                    1,
                                    FROM,
                                    FROM.plusSeconds(1)));
        assertStatus(
                403,
                () ->
                        access.authorize(
                                g, FUNCTION, "reporting", null, 1, 1, FROM, FROM.plusSeconds(1)));
        for (int rows : new int[] {-1, 0, 101})
            assertStatus(
                    403,
                    () ->
                            access.authorize(
                                    g,
                                    FUNCTION,
                                    "reporting",
                                    List.of("user_id"),
                                    rows,
                                    1,
                                    FROM,
                                    FROM.plusSeconds(1)));
        for (int bytes : new int[] {-1, 0, 1025})
            assertStatus(
                    403,
                    () ->
                            access.authorize(
                                    g,
                                    FUNCTION,
                                    "reporting",
                                    List.of("user_id"),
                                    1,
                                    bytes,
                                    FROM,
                                    FROM.plusSeconds(1)));
        for (Instant to :
                List.of(FROM, FROM.minusNanos(1), FROM.plusSeconds(3600).plusNanos(1), Instant.MAX))
            assertStatus(
                    403,
                    () ->
                            access.authorize(
                                    g, FUNCTION, "reporting", List.of("user_id"), 1, 1, FROM, to));
        assertStatus(
                403,
                () ->
                        access.authorize(
                                g, FUNCTION, "reporting", List.of("user_id"), 1, 1, null, FROM));
        assertStatus(
                403,
                () ->
                        access.authorize(
                                g, FUNCTION, "reporting", List.of("user_id"), 1, 1, FROM, null));
    }

    @Test
    void callerCannotForgeLargerOrUnconfiguredGrants() {
        var access = access(policy());
        var g = access.authenticate(TOKEN);
        var forged =
                new ConsumerAccess.Grant(
                        g.principal(),
                        g.functionIds(),
                        g.purposes(),
                        Set.of("secret"),
                        g.maxRows(),
                        g.maxBytes(),
                        g.maxWindowSeconds(),
                        g.policyHash());
        assertStatus(
                403,
                () ->
                        access.authorize(
                                forged,
                                FUNCTION,
                                "reporting",
                                List.of("secret"),
                                1,
                                1,
                                FROM,
                                FROM.plusSeconds(1)));
        var absent =
                new ConsumerAccess.Grant(
                        "not-configured",
                        g.functionIds(),
                        g.purposes(),
                        g.fields(),
                        g.maxRows(),
                        g.maxBytes(),
                        g.maxWindowSeconds(),
                        g.policyHash());
        assertStatus(
                403,
                () ->
                        access.authorize(
                                absent,
                                FUNCTION,
                                "reporting",
                                List.of("user_id"),
                                1,
                                1,
                                FROM,
                                FROM.plusSeconds(1)));
        assertStatus(
                403,
                () ->
                        access.authorize(
                                null,
                                FUNCTION,
                                "reporting",
                                List.of("user_id"),
                                1,
                                1,
                                FROM,
                                FROM.plusSeconds(1)));
    }
}
