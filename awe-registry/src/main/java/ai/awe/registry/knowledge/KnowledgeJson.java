package ai.awe.registry.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** Small strict protocol helpers; caller claims are not converted to server facts. */
public final class KnowledgeJson {
    private KnowledgeJson() {}

    public static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Expected an object");
        var names = Set.of(allowed);
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!names.contains(name))
                                throw new IllegalArgumentException("Unsupported field: " + name);
                        });
    }

    public static String text(JsonNode node, String field, int max) {
        var value = node.path(field);
        if (!value.isTextual()
                || value.asText().isBlank()
                || value.asText().length() > max
                || value.asText().codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + field);
        return value.asText();
    }

    public static String optional(JsonNode node, String field, int max) {
        return node.has(field) ? text(node, field, max) : "";
    }

    public static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field, 36));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid UUID: " + field);
        }
    }

    public static String hash(JsonNode node, String field) {
        String value = text(node, field, 64);
        if (!value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid SHA-256: " + field);
        return value;
    }

    public static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field, 100));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid explicit timestamp: " + field);
        }
    }

    public static List<JsonNode> array(JsonNode node, String field, int limit) {
        var array = node.path(field);
        if (!array.isArray() || array.size() > limit)
            throw new IllegalArgumentException("Invalid array: " + field);
        var result = new ArrayList<JsonNode>();
        array.forEach(result::add);
        return result;
    }

    public static String enumeration(JsonNode node, String field, Set<String> values) {
        String result = text(node, field, 50);
        if (!values.contains(result)) throw new IllegalArgumentException("Unsupported " + field);
        return result;
    }
}
