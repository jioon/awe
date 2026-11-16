package ai.awe.registry.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** 封闭准入协议：未知字段、伪造通过标志和不完整标识不能被忽略。 */
final class ProductionJson {
    private ProductionJson() {}

    static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Production request component must be an object");
        Set<String> keys = Set.of(allowed);
        node.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!keys.contains(k))
                                throw new IllegalArgumentException(
                                        "Unknown production field: " + k);
                        });
    }

    static String text(JsonNode node, String key, int maximum) {
        if (!node.path(key).isTextual())
            throw new IllegalArgumentException("Missing production field: " + key);
        String value = node.path(key).asText();
        if (value.isBlank()
                || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid production field: " + key);
        return value;
    }

    static UUID uuid(JsonNode node, String key) {
        try {
            return UUID.fromString(text(node, key, 36));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid production UUID: " + key);
        }
    }

    static String hash(JsonNode node, String key) {
        String value = text(node, key, 64);
        if (!value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Exact SHA-256 required: " + key);
        return value;
    }

    static String key(JsonNode node, String name) {
        String value = text(node, name, 120);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid production idempotency key");
        return value;
    }

    static long integer(JsonNode node, String name, long maximum) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0
                || value.longValue() > maximum)
            throw new IllegalArgumentException("Invalid production integer: " + name);
        return value.longValue();
    }

    static Instant instant(JsonNode node, String name) {
        try {
            return Instant.parse(text(node, name, 80));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Explicit ISO-8601 instant required: " + name);
        }
    }
}
