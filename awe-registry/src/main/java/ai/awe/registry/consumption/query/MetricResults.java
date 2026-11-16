package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.MetricContract;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 结果按正式输出粒度规范排序；不把分组顺序不同误判为数值不一致，也不容忍重复粒度。 */
public final class MetricResults {
    private MetricResults() {}

    public static List<Map<String, Object>> normalize(
            MetricContract contract,
            List<Map<String, Object>> input,
            int maxRows,
            int maxBytes,
            JsonDocuments json) {
        JsonNode rows = json.parse(json.write(input));
        MetricEvidenceVerifier.validateRows(rows, contract, maxRows, maxBytes);
        List<JsonNode> canonical = canonical(contract, rows, json);
        var result = new ArrayList<Map<String, Object>>();
        for (JsonNode row : canonical) {
            var fields = new LinkedHashMap<String, Object>();
            for (var output : contract.outputs()) {
                JsonNode value = row.get(output.name());
                Object converted =
                        value.isNull()
                                ? null
                                : switch (output.type()) {
                                    case INTEGER -> value.bigIntegerValue();
                                    case DECIMAL -> value.decimalValue();
                                    case BOOLEAN -> value.booleanValue();
                                    default -> value.textValue();
                                };
                fields.put(output.name(), converted);
            }
            result.add(Collections.unmodifiableMap(fields));
        }
        return List.copyOf(result);
    }

    public static List<JsonNode> canonical(
            MetricContract contract, JsonNode input, JsonDocuments json) {
        if (contract.grain().isEmpty() && input.size() > 1)
            throw new IllegalArgumentException("Scalar metric returned multiple rows");
        var seen = new HashSet<String>();
        var rows = new ArrayList<JsonNode>();
        for (JsonNode row : input) {
            String key = key(contract, row, json);
            if (!seen.add(key))
                throw new IllegalArgumentException(
                        "Metric result violates its published output grain");
            rows.add(row);
        }
        rows.sort(Comparator.comparing(row -> key(contract, row, json)));
        return List.copyOf(rows);
    }

    private static String key(MetricContract contract, JsonNode row, JsonDocuments json) {
        var values = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (String field : contract.grain()) values.add(row.path(field));
        return json.canonical(values);
    }

    public static String state(MetricContract contract, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "NO_RECORD";
        if (contract.grain().isEmpty() && rows.size() == 1) {
            var values = rows.getFirst().values();
            if (values.stream().allMatch(Objects::isNull)) return "NULL";
            if (values.stream()
                    .allMatch(
                            v ->
                                    v instanceof Number
                                            && new java.math.BigDecimal(v.toString()).signum()
                                                    == 0)) return "ZERO";
        }
        return "RECORDS";
    }
}
