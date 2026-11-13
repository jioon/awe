package ai.awe.registry.api;

import ai.awe.registry.consumption.ConsumerAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

@Component
public final class ApiAuthFilter extends OncePerRequestFilter {
    private final String author, reviewer;
    private final ConsumerAccess consumers;

    public ApiAuthFilter(
            @Value("${awe.auth.author-token:}") String author,
            @Value("${awe.auth.reviewer-token:}") String reviewer,
            ConsumerAccess consumers) {
        this.author = author;
        this.reviewer = reviewer;
        this.consumers = consumers;
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
        if (path.equals("/api/data-tasks")
                || path.startsWith("/api/data-tasks/")
                || path.equals("/api/capabilities")) {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                res.sendError(401, "Bearer token required");
                return;
            }
            try {
                var grant = consumers.authenticate(auth.substring(7));
                req.setAttribute("awe.consumer", grant);
                req.setAttribute("awe.actor", grant.principal());
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
            boolean approve = path.matches("/api/drafts/[^/]+/(approve|publish)");
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

    private static String normalizePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (!context.isEmpty() && uri.startsWith(context)) uri = uri.substring(context.length());
        // MVC removes matrix parameters and decodes path segments before matching. Authentication
        // must use that same path rather than letting encoded aliases select a weaker role.
        return UriUtils.decode(uri, StandardCharsets.UTF_8).replaceAll(";[^/]*", "");
    }
}
