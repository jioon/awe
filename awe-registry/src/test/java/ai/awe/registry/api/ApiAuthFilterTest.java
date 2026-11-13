package ai.awe.registry.api;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.support.JsonDocuments;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAuthFilterTest {
    private static final String AUTHOR = "author-only-local-test-token-0123456789";
    private static final String REVIEWER = "reviewer-only-local-test-token-0123456789";
    private static final String CONSUMER = "consumer-only-local-test-token-0123456789";
    private static final String PRINCIPAL = "reporting-service";
    private static final String POLICIES =
            """
            [{"principal":"reporting-service",
              "token":"consumer-only-local-test-token-0123456789",
              "functionIds":["function.video.report"],
              "purposes":["reporting"],"fields":["user_id","event_count"],
              "maxRows":100,"maxBytes":1048576,"maxWindowSeconds":3600}]
            """;

    private ConsumerAccess consumers(boolean bootstrapConfigured) {
        var env = new MockEnvironment().withProperty("AWE_CONSUMER_POLICIES", POLICIES);
        if (bootstrapConfigured) {
            env.withProperty("AWE_AUTHOR_TOKEN", AUTHOR);
            env.withProperty("AWE_REVIEWER_TOKEN", REVIEWER);
        }
        return new ConsumerAccess(env, new JsonDocuments());
    }

    private ApiAuthFilter configuredFilter() {
        return new ApiAuthFilter(AUTHOR, REVIEWER, consumers(true));
    }

    private record Result(
            MockHttpServletRequest request,
            MockHttpServletResponse response,
            boolean chainInvoked) {}

    private Result invoke(ApiAuthFilter filter, String method, String path, String auth)
            throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (auth != null) request.addHeader("Authorization", auth);
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        return new Result(request, response, invoked.get());
    }

    private void assertDenied(Result result, int status) {
        assertEquals(status, result.response().getStatus());
        assertFalse(result.chainInvoked());
        assertNull(result.request().getAttribute("awe.actor"));
        assertNull(result.request().getAttribute("awe.consumer"));
        assertEquals("no-store", result.response().getHeader("Cache-Control"));
    }

    @Test
    void consumerEndpointsAuthenticateIndependentlyOfBootstrapConfiguration() throws Exception {
        var filter = new ApiAuthFilter("", "", consumers(false));
        for (String path :
                List.of("/api/data-tasks", "/api/data-tasks/operation-123", "/api/capabilities")) {
            var result = invoke(filter, "GET", path, "Bearer " + CONSUMER);
            assertEquals(200, result.response().getStatus());
            assertTrue(result.chainInvoked());
            assertEquals(PRINCIPAL, result.request().getAttribute("awe.actor"));
            var grant =
                    assertInstanceOf(
                            ConsumerAccess.Grant.class,
                            result.request().getAttribute("awe.consumer"));
            assertEquals(PRINCIPAL, grant.principal());
            assertEquals("no-store", result.response().getHeader("Cache-Control"));
        }
    }

    @Test
    void bootstrapCredentialsDoNotGrantDataConsumption() throws Exception {
        var filter = configuredFilter();
        for (String token : List.of(AUTHOR, REVIEWER)) {
            for (String path : List.of("/api/data-tasks", "/api/capabilities")) {
                assertDenied(invoke(filter, "POST", path, "Bearer " + token), 401);
            }
        }
    }

    @Test
    void consumerCredentialCannotReadRegistryOrWriteModels() throws Exception {
        var filter = configuredFilter();
        assertDenied(invoke(filter, "GET", "/api/domains", "Bearer " + CONSUMER), 401);
        assertDenied(invoke(filter, "GET", "/api/releases", "Bearer " + CONSUMER), 401);
        assertDenied(invoke(filter, "POST", "/api/drafts", "Bearer " + CONSUMER), 401);
        assertDenied(invoke(filter, "POST", "/api/drafts/id/approve", "Bearer " + CONSUMER), 401);
    }

    @Test
    void authorAndReviewerRetainSeparatedModelAuthoringPermissions() throws Exception {
        var filter = configuredFilter();
        var authored = invoke(filter, "POST", "/api/drafts", "Bearer " + AUTHOR);
        assertTrue(authored.chainInvoked());
        assertEquals("bootstrap-author", authored.request().getAttribute("awe.actor"));
        assertNull(authored.request().getAttribute("awe.consumer"));
        assertDenied(invoke(filter, "POST", "/api/drafts", "Bearer " + REVIEWER), 403);
        assertDenied(invoke(filter, "POST", "/api/drafts/id/approve", "Bearer " + AUTHOR), 403);
        assertDenied(invoke(filter, "POST", "/api/drafts/id/publish", "Bearer " + AUTHOR), 403);
        for (String action : List.of("approve", "publish")) {
            var reviewed = invoke(filter, "POST", "/api/drafts/id/" + action, "Bearer " + REVIEWER);
            assertTrue(reviewed.chainInvoked());
            assertEquals("bootstrap-reviewer", reviewed.request().getAttribute("awe.actor"));
            assertNull(reviewed.request().getAttribute("awe.consumer"));
        }
    }

    @Test
    void similarlyNamedPathsDoNotReceiveConsumerAuthentication() throws Exception {
        var filter = configuredFilter();
        for (String path : List.of("/api/data-tasks-other", "/api/capabilities-other")) {
            assertDenied(invoke(filter, "GET", path, "Bearer " + CONSUMER), 401);
            var bootstrap = invoke(filter, "GET", path, "Bearer " + AUTHOR);
            assertTrue(bootstrap.chainInvoked());
            assertEquals("bootstrap-author", bootstrap.request().getAttribute("awe.actor"));
        }
    }

    @Test
    void missingAndMalformedConsumerAuthorizationNeverReachControllers() throws Exception {
        var filter = configuredFilter();
        assertDenied(invoke(filter, "GET", "/api/capabilities", null), 401);
        for (String header :
                List.of(
                        "",
                        "Basic " + CONSUMER,
                        "Bearer",
                        "bearer " + CONSUMER,
                        "Bearer ",
                        "Bearer unknown-local-test-token")) {
            assertDenied(invoke(filter, "GET", "/api/capabilities", header), 401);
        }
    }

    @Test
    void absentConsumerPoliciesFailClosedWithoutBlockingRegistryAuthentication() throws Exception {
        var missing = new ConsumerAccess(new MockEnvironment(), new JsonDocuments());
        var filter = new ApiAuthFilter(AUTHOR, REVIEWER, missing);
        assertDenied(invoke(filter, "GET", "/api/capabilities", "Bearer " + CONSUMER), 503);
        var registry = invoke(filter, "GET", "/api/domains", "Bearer " + AUTHOR);
        assertTrue(registry.chainInvoked());
        assertEquals("bootstrap-author", registry.request().getAttribute("awe.actor"));
    }

    @Test
    void invalidConsumerPoliciesFailClosedWithoutFallingBackToBootstrap() throws Exception {
        var env =
                new MockEnvironment().withProperty("AWE_CONSUMER_POLICIES", "[{\"principal\":42}]");
        var filter =
                new ApiAuthFilter(AUTHOR, REVIEWER, new ConsumerAccess(env, new JsonDocuments()));
        assertDenied(invoke(filter, "GET", "/api/data-tasks", "Bearer " + AUTHOR), 503);
    }

    @Test
    void encodedAndMatrixPrefixesCannotSkipApiAuthentication() throws Exception {
        for (String path :
                List.of(
                        "/%61pi/releases",
                        "/api;/releases", "/api;v=1/releases", "/%61pi/domains")) {
            assertDenied(invoke(configuredFilter(), "GET", path, null), 401);
            assertDenied(invoke(configuredFilter(), "GET", path, "Bearer " + CONSUMER), 401);
        }
    }

    @Test
    void encodedAndMatrixConsumerPathsNeverFallBackToBootstrap() throws Exception {
        for (String path :
                List.of(
                        "/api;v=1/data-tasks",
                        "/%61pi/data-tasks",
                        "/api/data-tasks;v=1/operation",
                        "/api;v=1/capabilities")) {
            assertDenied(invoke(configuredFilter(), "GET", path, "Bearer " + AUTHOR), 401);
            var allowed = invoke(configuredFilter(), "GET", path, "Bearer " + CONSUMER);
            assertTrue(allowed.chainInvoked());
            assertEquals(PRINCIPAL, allowed.request().getAttribute("awe.actor"));
        }
    }

    @Test
    void encodedOrMatrixApprovalCannotSelectAuthorRole() throws Exception {
        for (String path :
                List.of(
                        "/api/drafts/id/approv%65",
                        "/api;v=1/drafts/id/approve",
                        "/api/drafts/id/approve;v=1",
                        "/%61pi/drafts/id/publ%69sh")) {
            assertDenied(invoke(configuredFilter(), "POST", path, "Bearer " + AUTHOR), 403);
            var allowed = invoke(configuredFilter(), "POST", path, "Bearer " + REVIEWER);
            assertTrue(allowed.chainInvoked());
            assertEquals("bootstrap-reviewer", allowed.request().getAttribute("awe.actor"));
        }
    }

    @Test
    void malformedEncodedPathFailsClosed() throws Exception {
        assertDenied(invoke(configuredFilter(), "GET", "/%XXpi/releases", null), 400);
        assertDenied(
                invoke(configuredFilter(), "GET", "/api/drafts/id/approve%", "Bearer " + AUTHOR),
                400);
    }
}
