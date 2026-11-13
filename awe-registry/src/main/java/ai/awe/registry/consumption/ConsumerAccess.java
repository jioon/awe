package ai.awe.registry.consumption;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit internal service policies for data consumption. Bootstrap model roles never imply data
 * access; these policies do not implement enterprise identity or warehouse row-level security.
 */
@Component
public final class ConsumerAccess {
    private static final Set<String> POLICY_FIELDS =
            Set.of(
                    "principal",
                    "token",
                    "functionIds",
                    "purposes",
                    "fields",
                    "maxRows",
                    "maxBytes",
                    "maxWindowSeconds");
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_BYTES = 1_048_576;

    public record Grant(
            String principal,
            Set<String> functionIds,
            Set<String> purposes,
            Set<String> fields,
            int maxRows,
            int maxBytes,
            long maxWindowSeconds,
            String policyHash) {
        public Grant {
            functionIds = Set.copyOf(functionIds);
            purposes = Set.copyOf(purposes);
            fields = Set.copyOf(fields);
        }
    }

    private record Credential(byte[] digest, Grant grant) {}

    private final List<Credential> credentials;
    private final Map<String, Grant> grants;

    public ConsumerAccess(Environment env, JsonDocuments json) {
        List<Credential> parsed = new ArrayList<>();
        Map<String, Grant> byPrincipal = new HashMap<>();
        try {
            String raw = env.getProperty("AWE_CONSUMER_POLICIES", "");
            if (raw.isBlank() || raw.length() > 262_144) throw invalid();
            JsonNode policies = json.parse(raw);
            if (!policies.isArray() || policies.isEmpty() || policies.size() > 128) throw invalid();
            Set<String> tokenDigests = new HashSet<>();
            Set<String> bootstrapTokens = new HashSet<>();
            for (String key :
                    List.of(
                            "AWE_AUTHOR_TOKEN",
                            "AWE_REVIEWER_TOKEN",
                            "awe.auth.author-token",
                            "awe.auth.reviewer-token")) {
                String value = env.getProperty(key, "");
                if (!value.isEmpty()) bootstrapTokens.add(value);
            }
            for (JsonNode policy : policies) {
                if (!policy.isObject()) throw invalid();
                Set<String> keys = new HashSet<>();
                policy.fieldNames().forEachRemaining(keys::add);
                if (!keys.equals(POLICY_FIELDS)) throw invalid();
                String principal = text(policy.path("principal"), 100);
                if (!principal.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                        || principal.startsWith("bootstrap-")) throw invalid();
                String token = text(policy.path("token"), 4096);
                if (!token.matches("[A-Za-z0-9._~+/-]+={0,2}") || bootstrapTokens.contains(token))
                    throw invalid();
                byte[] digest = digest(token);
                if (!tokenDigests.add(HexFormat.of().formatHex(digest))) throw invalid();
                Set<String> functions = strings(policy.path("functionIds"), 240);
                if (functions.stream()
                        .anyMatch(f -> !f.matches("function(?:\\.[a-z][a-z0-9_-]*){2,}")))
                    throw invalid();
                Set<String> purposes = strings(policy.path("purposes"), 160);
                Set<String> fields = strings(policy.path("fields"), 128);
                if (fields.stream().anyMatch(f -> !f.matches("[A-Za-z_][A-Za-z0-9_]*")))
                    throw invalid();
                int maxRows = (int) positiveInteger(policy.path("maxRows"), MAX_ROWS);
                int maxBytes = (int) positiveInteger(policy.path("maxBytes"), MAX_BYTES);
                long maxWindow = positiveInteger(policy.path("maxWindowSeconds"), Long.MAX_VALUE);
                // Canonicalize the authorization scope, never the raw token or its digest.
                String hash =
                        json.sha256(
                                json.parse(
                                        json.write(
                                                Map.of(
                                                        "principal", principal,
                                                        "functionIds", new TreeSet<>(functions),
                                                        "purposes", new TreeSet<>(purposes),
                                                        "fields", new TreeSet<>(fields),
                                                        "maxRows", maxRows,
                                                        "maxBytes", maxBytes,
                                                        "maxWindowSeconds", maxWindow))));
                Grant grant =
                        new Grant(
                                principal, functions, purposes, fields, maxRows, maxBytes,
                                maxWindow, hash);
                if (byPrincipal.put(principal, grant) != null) throw invalid();
                parsed.add(new Credential(digest, grant));
            }
        } catch (RuntimeException invalidConfiguration) {
            // Keep the application available for authoring while every consumption request fails
            // closed. Do not propagate parser exceptions that could contain a credential.
            parsed.clear();
            byPrincipal.clear();
        }
        credentials = List.copyOf(parsed);
        grants = Map.copyOf(byPrincipal);
    }

    public Grant authenticate(String bearer) {
        if (credentials.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Consumer policies are missing or invalid");
        }
        if (bearer == null || bearer.isEmpty() || bearer.length() > 4096) throw unauthenticated();
        byte[] provided = digest(bearer);
        Grant matched = null;
        for (Credential credential : credentials) {
            if (MessageDigest.isEqual(provided, credential.digest())) matched = credential.grant();
        }
        if (matched == null) throw unauthenticated();
        return matched;
    }

    public void authorize(
            Grant g,
            String functionId,
            String purpose,
            List<String> fields,
            int maxRows,
            int maxBytes,
            Instant from,
            Instant to) {
        // A caller-created record cannot increase the immutable configured grant.
        if (g == null
                || g.principal() == null
                || !g.equals(grants.get(g.principal()))
                || functionId == null
                || purpose == null
                || !g.functionIds().contains(functionId)
                || !g.purposes().contains(purpose)
                || fields == null
                || fields.isEmpty()
                || fields.stream().anyMatch(Objects::isNull)
                || new HashSet<>(fields).size() != fields.size()
                || !g.fields().containsAll(fields)
                || maxRows <= 0
                || maxRows > g.maxRows()
                || maxBytes <= 0
                || maxBytes > g.maxBytes()
                || from == null
                || to == null
                || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofSeconds(g.maxWindowSeconds()))
                        > 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Consumer policy does not authorize this request");
        }
    }

    private static Set<String> strings(JsonNode array, int maxLength) {
        if (!array.isArray() || array.isEmpty() || array.size() > 256) throw invalid();
        Set<String> values = new HashSet<>();
        for (JsonNode value : array) if (!values.add(text(value, maxLength))) throw invalid();
        return Set.copyOf(values);
    }

    private static String text(JsonNode value, int maxLength) {
        if (!value.isTextual()) throw invalid();
        String text = value.textValue();
        if (text.isBlank()
                || text.length() > maxLength
                || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    private static long positiveInteger(JsonNode value, long maximum) {
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() <= 0
                || value.longValue() > maximum) throw invalid();
        return value.longValue();
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid consumer policy configuration");
    }

    private static ResponseStatusException unauthenticated() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid consumer credential");
    }
}
