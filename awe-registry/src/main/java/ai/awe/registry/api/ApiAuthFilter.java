package ai.awe.registry.api;

import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.production.PlatformAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

@Component
public final class ApiAuthFilter extends OncePerRequestFilter {
    private final String author, reviewer;
    private final ConsumerAccess consumers;
    private final PlatformAccess platforms;

    @Autowired
    public ApiAuthFilter(
            @Value("${awe.auth.author-token:}") String author,
            @Value("${awe.auth.reviewer-token:}") String reviewer,
            ConsumerAccess consumers,
            PlatformAccess platforms) {
        this.author = author;
        this.reviewer = reviewer;
        this.consumers = consumers;
        this.platforms = platforms;
    }

    /** 保留显式构造的单元测试与非平台嵌入调用；该形态不授予任何平台权限。 */
    public ApiAuthFilter(String author, String reviewer, ConsumerAccess consumers) {
        this(author, reviewer, consumers, null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        try {
            String path = normalizePath(req);
            return !path.equals("/api") && !path.startsWith("/api/");
        } catch (IllegalArgumentException invalidPath) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Cache-Control", "no-store");
        String path;
        try {
            path = normalizePath(req);
        } catch (IllegalArgumentException invalidPath) {
            res.sendError(400, "Invalid request path");
            return;
        }
        if (path.matches("/api/production/admissions/[^/]+/(consume|realizations)")) {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                res.sendError(401, "Bearer token required");
                return;
            }
            if (platforms == null) {
                res.sendError(503, "Production caller policies are missing or invalid");
                return;
            }
            try {
                var grant = platforms.authenticate(auth.substring(7));
                req.setAttribute("awe.platform", grant);
                req.setAttribute("awe.platformGrant", grant);
                req.setAttribute("awe.actor", grant.principal());
            } catch (ResponseStatusException denied) {
                res.sendError(denied.getStatusCode().value(), denied.getReason());
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        if (consumerPath(path, req.getMethod())) {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                res.sendError(401, "Bearer token required");
                return;
            }
            try {
                var authenticated = consumers.authenticateContext(auth.substring(7));
                var grant = authenticated.grant();
                req.setAttribute("awe.consumer", grant);
                req.setAttribute("awe.actor", grant.principal());
                req.setAttribute("awe.identity", authenticated.identity());
            } catch (ResponseStatusException denied) {
                res.sendError(denied.getStatusCode().value(), denied.getReason());
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        if (author.isBlank() || reviewer.isBlank() || secureEquals(author, reviewer)) {
            res.sendError(503, "Distinct bootstrap author and reviewer credentials required");
            return;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            res.sendError(401, "Bearer token required");
            return;
        }
        String provided = auth.substring(7);
        String role =
                secureEquals(provided, author)
                        ? "author"
                        : secureEquals(provided, reviewer) ? "reviewer" : null;
        if (role == null) {
            res.sendError(401, "Invalid bearer token");
            return;
        }
        boolean write =
                !switch (req.getMethod()) {
                    case "GET", "HEAD", "OPTIONS" -> true;
                    default -> false;
                };
        if (write) {
            boolean approve =
                    path.matches("/api/drafts/[^/]+/(approve|publish)")
                            || path.matches("/api/knowledge/coverage/[^/]+/approve")
                            || path.matches("/api/regressions/cases/[^/]+/approve")
                            || path.matches("/api/feedback/[^/]+/closures")
                            || path.matches(
                                    "/api/feedback/[^/]+/migrations/[^/]+/(approve|resolve)")
                            || path.matches("/api/production/admissions/[^/]+/approve");
            if (approve && !role.equals("reviewer")) {
                res.sendError(403, "Reviewer required");
                return;
            }
            if (!approve && !role.equals("author")) {
                res.sendError(403, "Author required");
                return;
            }
        }
        req.setAttribute("awe.actor", "bootstrap-" + role);
        chain.doFilter(req, res);
    }

    private static boolean secureEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean consumerPath(String path, String method) {
        return path.equals("/api/data-tasks")
                || path.startsWith("/api/data-tasks/")
                || path.equals("/api/capabilities")
                || path.equals("/api/knowledge/resolve")
                || path.equals("/api/knowledge/assess")
                || path.equals("/api/semantic-plans/validate")
                || path.equals("/api/mcp")
                || path.equals("/api/feedback")
                || path.equals("/api/regressions/cases")
                || path.equals("/api/regressions/observations")
                || path.matches("/api/feedback/[^/]+/migrations")
                || path.matches("/api/regressions/cases/[^/]+/replays")
                || (method.equals("GET") || method.equals("HEAD"))
                        && (path.matches("/api/feedback/[^/]+")
                                || path.matches("/api/regressions/cases/[^/]+")
                                || path.equals("/api/knowledge/coverage")
                                || path.matches("/api/knowledge/observations/[^/]+")
                                || path.matches("/api/regressions/replays/[^/]+")
                                || path.matches("/api/regressions/observations/[^/]+")
                                || path.matches("/api/feedback/[^/]+/migrations/[^/]+"));
    }

    private static String normalizePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (!context.isEmpty() && uri.startsWith(context)) uri = uri.substring(context.length());
        // MVC removes matrix parameters and decodes path segments before matching. Authentication
        // must use that same path rather than letting encoded aliases select a weaker role.
        return UriUtils.decode(uri, StandardCharsets.UTF_8).replaceAll(";[^/]*", "");
    }
}
