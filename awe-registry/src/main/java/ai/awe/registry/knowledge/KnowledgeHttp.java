package ai.awe.registry.knowledge;

import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.identity.TrustedOidcVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class KnowledgeHttp {
    private KnowledgeHttp() {}

    public static ConsumerAccess.Grant grant(HttpServletRequest request) {
        Object grant = request.getAttribute("awe.consumer");
        if (!(grant instanceof ConsumerAccess.Grant g))
            throw new IllegalArgumentException("Authenticated consumer context is required");
        return g;
    }

    public static String actor(HttpServletRequest request) {
        Object actor = request.getAttribute("awe.actor");
        if (!(actor instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("Authenticated actor is required");
        return text;
    }

    public static Map<String, Object> identity(HttpServletRequest request) {
        Object value = request.getAttribute("awe.identity");
        if (value instanceof TrustedOidcVerifier.Identity identity) return identity.toEvidence();
        throw new IllegalArgumentException("Authenticated identity evidence is required");
    }

    public static void bounded(String body) {
        if (body.getBytes(StandardCharsets.UTF_8).length > 262144)
            throw new IllegalArgumentException("Knowledge request exceeds 256 KiB");
    }
}
