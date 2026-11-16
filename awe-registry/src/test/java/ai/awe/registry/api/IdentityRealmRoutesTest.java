package ai.awe.registry.api;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.production.PlatformAccess;
import ai.awe.registry.support.JsonDocuments;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdentityRealmRoutesTest {
    private final JsonDocuments json = new JsonDocuments();
    private static final String AUTHOR = "unit-model-author",
            REVIEWER = "unit-model-reviewer",
            CONSUMER = "unit-consumer-token",
            PLATFORM = "unit-platform-token";

    private ApiAuthFilter filter() {
        var env =
                new MockEnvironment()
                        .withProperty("awe.auth.author-token", AUTHOR)
                        .withProperty("awe.auth.reviewer-token", REVIEWER)
                        .withProperty(
                                "AWE_CONSUMER_POLICIES",
                                json.write(
                                        List.of(
                                                Map.of(
                                                        "principal",
                                                        "domain-reader",
                                                        "token",
                                                        CONSUMER,
                                                        "functionIds",
                                                        List.of("function.video.query"),
                                                        "purposes",
                                                        List.of("analysis"),
                                                        "fields",
                                                        List.of("value"),
                                                        "maxRows",
                                                        10,
                                                        "maxBytes",
                                                        1024,
                                                        "maxWindowSeconds",
                                                        3600))))
                        .withProperty(
                                "AWE_PRODUCTION_CALLERS",
                                json.write(
                                        List.of(
                                                Map.of(
                                                        "principal",
                                                        "production-platform",
                                                        "token",
                                                        PLATFORM,
                                                        "pipelineIds",
                                                        List.of("pipeline.video.refresh"),
                                                        "environments",
                                                        List.of("production")))));
        return new ApiAuthFilter(
                AUTHOR, REVIEWER, new ConsumerAccess(env, json), new PlatformAccess(env, json));
    }

    @Test
    void consumersCanUseOnlyConsumerOperationsAndReviewAuthorityStaysServerAssigned()
            throws Exception {
        for (String[] route :
                List.of(
                        new String[] {"POST", "/api/knowledge/resolve"},
                        new String[] {"POST", "/api/semantic-plans/validate"},
                        new String[] {"POST", "/api/mcp"},
                        new String[] {"GET", "/api/knowledge/coverage"},
                        new String[] {"GET", "/api/knowledge/observations/a"},
                        new String[] {"POST", "/api/regressions/cases"},
                        new String[] {"POST", "/api/regressions/cases/a/replays"},
                        new String[] {"GET", "/api/regressions/replays/a"})) {
            var request = new MockHttpServletRequest(route[0], route[1]);
            request.addHeader("Authorization", "Bearer " + CONSUMER);
            var response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            filter().doFilter(request, response, (req, res) -> called.set(true));
            assertTrue(called.get(), route[1]);
            assertNotNull(request.getAttribute("awe.identity"));
            assertEquals("domain-reader", request.getAttribute("awe.actor"));
        }
        for (String path :
                List.of(
                        "/api/knowledge/coverage/a/approve",
                        "/api/regressions/cases/a/approve",
                        "/api/feedback/a/closures",
                        "/api/production/admissions/a/approve")) {
            var request = new MockHttpServletRequest("POST", path);
            request.addHeader("Authorization", "Bearer " + AUTHOR);
            var response = new MockHttpServletResponse();
            filter().doFilter(request, response, (req, res) -> fail("Author cannot approve"));
            assertEquals(403, response.getStatus(), path);
            var approved = new MockHttpServletRequest("POST", path);
            approved.addHeader("Authorization", "Bearer " + REVIEWER);
            AtomicBoolean called = new AtomicBoolean();
            filter().doFilter(
                            approved,
                            new MockHttpServletResponse(),
                            (req, res) -> called.set(true));
            assertTrue(called.get(), path);
        }
        var review = new MockHttpServletRequest("POST", "/api/feedback/a/reviews");
        review.addHeader("Authorization", "Bearer " + AUTHOR);
        AtomicBoolean reviewed = new AtomicBoolean();
        filter().doFilter(review, new MockHttpServletResponse(), (req, res) -> reviewed.set(true));
        assertTrue(reviewed.get());
    }

    @Test
    void consumeAndRealizationRequireIndependentPlatformCredentialIncludingEncodedPaths()
            throws Exception {
        for (String path :
                List.of(
                        "/api/production/admissions/a/consume",
                        "/api/production/admissions/a/realizations",
                        "/%61pi/production/admissions/a/consum%65",
                        "/api;x=y/production/admissions/a/realizations")) {
            for (String token : List.of(AUTHOR, REVIEWER, CONSUMER)) {
                var request = new MockHttpServletRequest("POST", path);
                request.addHeader("Authorization", "Bearer " + token);
                var response = new MockHttpServletResponse();
                filter().doFilter(
                                request,
                                response,
                                (req, res) ->
                                        fail(
                                                "Non-platform credential reached deployment authorization"));
                assertEquals(401, response.getStatus(), path);
            }
            var request = new MockHttpServletRequest("POST", path);
            request.addHeader("Authorization", "Bearer " + PLATFORM);
            AtomicBoolean called = new AtomicBoolean();
            filter().doFilter(
                            request, new MockHttpServletResponse(), (req, res) -> called.set(true));
            assertTrue(called.get(), path);
            assertEquals("production-platform", request.getAttribute("awe.actor"));
            assertInstanceOf(PlatformAccess.Grant.class, request.getAttribute("awe.platform"));
        }
    }
}
