package ai.awe.registry.production;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 平台部署身份独立于模型作者与审批者；外部请求中的角色、环境声明不能自授权。 */
@Component
public final class PlatformAccess {
    public record Grant(
            String principal,
            Set<String> pipelineIds,
            Set<String> environments,
            String policyHash) {
        public Grant {
            pipelineIds = Set.copyOf(pipelineIds);
            environments = Set.copyOf(environments);
        }
    }

    private record Credential(byte[] digest, Grant grant) {}

    private final List<Credential> credentials;
    private final Map<String, Grant> grants;

    public PlatformAccess(Environment env, JsonDocuments json) {
        var parsed = new ArrayList<Credential>();
        var byPrincipal = new HashMap<String, Grant>();
        try {
            String raw = env.getProperty("AWE_PRODUCTION_CALLERS", "");
            if (raw.isBlank() || raw.length() > 262144) throw invalid();
            JsonNode array = json.parse(raw);
            if (!array.isArray() || array.isEmpty() || array.size() > 128) throw invalid();
            var reserved = new HashSet<String>();
            for (String key :
                    List.of(
                            "AWE_AUTHOR_TOKEN",
                            "AWE_REVIEWER_TOKEN",
                            "awe.auth.author-token",
                            "awe.auth.reviewer-token")) {
                String token = env.getProperty(key, "");
                if (!token.isEmpty()) reserved.add(token);
            }
            String consumerPolicies = env.getProperty("AWE_CONSUMER_POLICIES", "");
            if (!consumerPolicies.isBlank())
                for (JsonNode policy : json.parse(consumerPolicies))
                    if (policy.path("token").isTextual())
                        reserved.add(policy.path("token").asText());
            var digests = new HashSet<String>();
            for (JsonNode node : array) {
                fields(node, Set.of("principal", "token", "pipelineIds", "environments"));
                String principal = text(node, "principal", 100), token = text(node, "token", 4096);
                if (!principal.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                        || principal.startsWith("bootstrap-")
                        || !token.matches("[A-Za-z0-9._~+/-]+={0,2}")
                        || reserved.contains(token)) throw invalid();
                Set<String> pipelines = strings(node.path("pipelineIds")),
                        environments = strings(node.path("environments"));
                if (pipelines.stream()
                                .anyMatch(p -> !p.matches("pipeline(?:\\.[a-z][a-z0-9_-]*){2,}"))
                        || environments.stream()
                                .anyMatch(e -> !e.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")))
                    throw invalid();
                String hash =
                        json.sha256(
                                json.parse(
                                        json.write(
                                                Map.of(
                                                        "principal",
                                                        principal,
                                                        "pipelineIds",
                                                        new TreeSet<>(pipelines),
                                                        "environments",
                                                        new TreeSet<>(environments)))));
                var grant = new Grant(principal, pipelines, environments, hash);
                byte[] digest = digest(token);
                if (!digests.add(HexFormat.of().formatHex(digest))
                        || byPrincipal.put(principal, grant) != null) throw invalid();
                parsed.add(new Credential(digest, grant));
            }
        } catch (RuntimeException ex) {
            // 配置失败只关闭平台准入，不输出可能携带凭据的解析异常。
            parsed.clear();
            byPrincipal.clear();
        }
        credentials = List.copyOf(parsed);
        grants = Map.copyOf(byPrincipal);
    }

    public Grant authenticate(String bearer) {
        if (credentials.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Production caller policies are missing or invalid");
        if (bearer == null || bearer.isEmpty() || bearer.length() > 4096)
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Invalid production credential");
        byte[] provided = digest(bearer);
        Grant matched = null;
        for (var credential : credentials)
            if (MessageDigest.isEqual(provided, credential.digest())) matched = credential.grant();
        if (matched == null)
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Invalid production credential");
        return matched;
    }

    public void authorize(Grant grant, String pipelineId, String environment) {
        if (grant == null
                || !grant.equals(grants.get(grant.principal()))
                || !grant.pipelineIds().contains(pipelineId)
                || !grant.environments().contains(environment))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Production caller is outside the permitted pipeline/environment scope");
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject()) throw invalid();
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > 256) throw invalid();
        var result = new HashSet<String>();
        for (JsonNode item : node) {
            if (!item.isTextual() || !result.add(item.asText()) || item.asText().length() > 240)
                throw invalid();
        }
        return Set.copyOf(result);
    }

    private static String text(JsonNode node, String key, int limit) {
        if (!node.path(key).isTextual()) throw invalid();
        String value = node.path(key).asText();
        if (value.isBlank()
                || value.length() > limit
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid production caller policy");
    }
}
