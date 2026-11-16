package ai.awe.registry.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;

/** 物理目标版本向量独立于 Pipeline 基线；完整集合比较，不能以部分目标成功代替原子发布。 */
public final class ProductionTargetVersions {
    private ProductionTargetVersions() {}

    public static List<String> keys(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > 8) throw invalid();
        var keys = new TreeSet<String>();
        for (JsonNode value : values) {
            if (!value.isTextual()
                    || !value.asText().matches("[a-z][a-z0-9_-]*:.{1,500}")
                    || value.asText().chars().anyMatch(Character::isISOControl)
                    || !keys.add(value.asText())) throw invalid();
        }
        return List.copyOf(keys);
    }

    public static JsonNode baselines(JsonNode vector, Collection<String> expected) {
        var records = records(vector, expected);
        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        for (var record : records.values()) {
            ProductionJson.fields(record, "targetKey", "revision", "consumptionId", "intentId");
            ProductionJson.text(record, "revision", 240);
            String intent = intent(record, true);
            JsonNode owner = record.path("consumptionId");
            if (!owner.isTextual()) throw invalid();
            if (intent.equals("0")) {
                if (!owner.asText().isEmpty()) throw invalid();
            } else ProductionJson.uuid(record, "consumptionId");
            normalized.add(record.deepCopy());
        }
        return normalized;
    }

    public static Map<String, String> intents(JsonNode vector, Collection<String> expected) {
        var result = new TreeMap<String, String>();
        records(vector, expected)
                .forEach(
                        (key, record) -> {
                            ProductionJson.fields(record, "targetKey", "intentId");
                            result.put(key, intent(record, false));
                        });
        return Collections.unmodifiableMap(result);
    }

    /** 历史部署的每个目标转换必须匹配许可；当前活动所有者也必须仍是同一次消费。 */
    public static boolean active(JsonNode deployment, JsonNode authorization) {
        if (authorization.path("protocolVersion").asInt() != 2) throw invalid();
        var expected = keys(authorization.path("physicalTargets"));
        JsonNode before = baselines(authorization.path("targetBaselines"), expected);
        var writes = intents(authorization.path("targetWriteIntents"), expected);
        var previous = records(before, expected);
        var transitions = records(deployment.path("targetRevisions"), expected);
        ArrayNode after = JsonNodeFactory.instance.arrayNode();
        String consumption = ProductionJson.uuid(authorization, "consumptionId").toString();
        for (var entry : transitions.entrySet()) {
            String key = entry.getKey();
            JsonNode record = entry.getValue(), baseline = previous.get(key);
            ProductionJson.fields(
                    record,
                    "targetKey",
                    "previousRevision",
                    "revision",
                    "consumptionId",
                    "intentId");
            String revision = ProductionJson.text(record, "revision", 240);
            if (!ProductionJson.text(record, "previousRevision", 240)
                            .equals(baseline.path("revision").asText())
                    || revision.equals(baseline.path("revision").asText())
                    || !consumption.equals(ProductionJson.uuid(record, "consumptionId").toString())
                    || !writes.get(key).equals(intent(record, false))
                    || Long.parseLong(writes.get(key)) <= Long.parseLong(intent(baseline, true)))
                throw invalid();
            after.addObject()
                    .put("targetKey", key)
                    .put("revision", revision)
                    .put("consumptionId", consumption)
                    .put("intentId", writes.get(key));
        }
        return after.equals(baselines(deployment.path("currentTargets"), expected));
    }

    private static SortedMap<String, JsonNode> records(
            JsonNode vector, Collection<String> expected) {
        if (!vector.isArray()
                || vector.isEmpty()
                || vector.size() > 8
                || expected.isEmpty()
                || expected.size() > 8) throw invalid();
        var result = new TreeMap<String, JsonNode>();
        for (JsonNode record : vector) {
            String key = ProductionJson.text(record, "targetKey", 600);
            if (result.put(key, record) != null) throw invalid();
        }
        if (!result.keySet().equals(new TreeSet<>(expected))) throw invalid();
        return result;
    }

    private static String intent(JsonNode node, boolean zero) {
        String value = ProductionJson.text(node, "intentId", 19);
        if (!value.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) throw invalid();
        try {
            Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Physical target version/ownership coverage is incomplete, stale or mismatched");
    }
}
