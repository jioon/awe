package ai.awe.registry.production;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 逻辑目标的物理承载由部署方预注册；工程平台不能临时注入连接或任意表。 */
@Component
public final class ProductionTargets {
    private final Map<String, JsonNode> bindings;
    private final String hash;
    private final String environment;

    public ProductionTargets(Environment env, JsonDocuments json) {
        String configuredEnvironment = env.getProperty("AWE_PRODUCTION_ENVIRONMENT", "");
        environment =
                configuredEnvironment.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
                        ? configuredEnvironment
                        : "";
        var parsed = new TreeMap<String, JsonNode>();
        Set<String> ambiguous = ambiguousPostgresSources(env, json);
        try {
            String raw = env.getProperty("AWE_PRODUCTION_TARGET_BINDINGS", "");
            if (!raw.isBlank()) {
                if (raw.length() > 262144) throw new IllegalArgumentException();
                JsonNode doc = json.object(raw);
                if (doc.size() > 256) throw new IllegalArgumentException();
                var entries = doc.fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}"))
                        throw new IllegalArgumentException();
                    JsonNode value = entry.getValue();
                    fields(value, Set.of("sourceSystem", "externalRef", "timeFields"));
                    String system = text(value, "sourceSystem"),
                            external = text(value, "externalRef");
                    if (!Set.of("postgres", "hive", "bi").contains(system)
                            || external.length() > 500
                            || external.chars().anyMatch(Character::isISOControl)
                            || !value.path("timeFields").isObject())
                        throw new IllegalArgumentException();
                    var roles = value.path("timeFields").fields();
                    while (roles.hasNext()) {
                        var role = roles.next();
                        ai.awe.kernel.contract.TemporalContract.TimeRole.valueOf(role.getKey());
                        if (!role.getValue().isTextual()
                                || !role.getValue().asText().matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
                            throw new IllegalArgumentException();
                    }
                    if (!system.equals("postgres") || !ambiguous.contains("postgres:" + external))
                        parsed.put(entry.getKey(), value.deepCopy());
                }
            }
        } catch (RuntimeException ex) {
            parsed.clear();
        }
        bindings = Collections.unmodifiableMap(parsed);
        hash =
                json.sha256(
                        json.parse(
                                json.write(
                                        Map.of("environment", environment, "bindings", bindings))));
    }

    public String configurationHash() {
        return hash;
    }

    public String environment() {
        return environment;
    }

    public Optional<JsonNode> find(String bindingRef) {
        return environment.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(bindings.get(bindingRef)).map(JsonNode::deepCopy);
    }

    public JsonNode require(String bindingRef) {
        return find(bindingRef)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "NOT_CONFIGURED: registered production target binding is missing or invalid"));
    }

    /** PostgreSQL 同一连接下一个 relation 只能有一个生产资产标识，防止别名绕过共享目标保护。 */
    private static Set<String> ambiguousPostgresSources(Environment env, JsonDocuments json) {
        var byRelation = new HashMap<String, Set<String>>();
        try {
            String raw = env.getProperty("AWE_WAREHOUSE_ASSET_BINDINGS", "");
            if (raw.isBlank()) return Set.of();
            JsonNode mapping = json.object(raw);
            for (var entry : mapping.properties())
                if (entry.getKey().startsWith("postgres:") && entry.getValue().isTextual())
                    byRelation
                            .computeIfAbsent(entry.getValue().asText(), key -> new HashSet<>())
                            .add(entry.getKey());
        } catch (IllegalArgumentException invalid) {
            return Set.of();
        }
        var ambiguous = new HashSet<String>();
        for (var entries : byRelation.values()) if (entries.size() > 1) ambiguous.addAll(entries);
        return Set.copyOf(ambiguous);
    }

    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank())
            throw new IllegalArgumentException();
        return node.path(key).asText();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject()) throw new IllegalArgumentException();
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException();
    }
}
