package ai.awe.registry.consumption.query;

import ai.awe.kernel.capability.DataTask;
import ai.awe.kernel.contract.RelationalIR;
import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** v3 表达业务数据要求；实现选择及合法计算计划由服务端完成，调用方不能提交 IR 或 SQL。 */
public record SemanticMetricRequest(
        DataTask.ProposedFunction metric,
        Optional<DataTask.ProposedFunction> candidateFunction,
        EntityRef statisticalSubject,
        String unit,
        ZoneId businessZone,
        TemporalContract.TimeRole timeRole,
        List<String> measures,
        List<String> dimensions,
        List<Filter> filters,
        Instant from,
        Instant to,
        MetricQueryRequest.View view,
        MetricQueryRequest.Verification verification,
        String purpose,
        int maxRows,
        int maxBytes,
        String idempotencyKey,
        Optional<DataTask.ExternalTaskRef> externalTaskRef) {
    public record Filter(
            String field,
            RelationalIR.CompareOp operation,
            ObjectType.DataType type,
            String value) {
        public Filter {
            RelationalIR.identifier(field);
            Objects.requireNonNull(operation);
            var literal = new RelationalIR.Literal(type, value);
            value = literal.value();
            if (type == ObjectType.DataType.BOOLEAN
                    && operation != RelationalIR.CompareOp.EQ
                    && operation != RelationalIR.CompareOp.NE)
                throw new IllegalArgumentException("Boolean filters support only EQ and NE");
        }

        public RelationalIR.Literal literal() {
            return new RelationalIR.Literal(type, value);
        }
    }

    public SemanticMetricRequest {
        Objects.requireNonNull(metric);
        Objects.requireNonNull(candidateFunction);
        Objects.requireNonNull(statisticalSubject);
        bounded(unit, "unit", 500);
        Objects.requireNonNull(businessZone);
        Objects.requireNonNull(timeRole);
        measures = names(measures, "measures", 32, false);
        dimensions = names(dimensions, "dimensions", 32, true);
        if (!Collections.disjoint(measures, dimensions))
            throw new IllegalArgumentException("Measures and dimensions cannot overlap");
        filters = List.copyOf(filters);
        if (filters.size() > 32) throw new IllegalArgumentException("Too many semantic filters");
        if (new HashSet<>(filters).size() != filters.size())
            throw new IllegalArgumentException("Duplicate semantic filter");
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(view);
        Objects.requireNonNull(verification);
        Objects.requireNonNull(externalTaskRef);
        // 复用既有消费协议的时间、用途、预算和幂等约束，避免形成宽松的第二条入口。
        new MetricQueryRequest(
                metric,
                from,
                to,
                view,
                verification,
                purpose,
                maxRows,
                maxBytes,
                idempotencyKey,
                externalTaskRef);
    }

    public static SemanticMetricRequest decode(JsonNode body) {
        // 各传输层共用同一语义预算；不能借 MCP、观测或评估入口扩大可接受请求。
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw new IllegalArgumentException("Semantic data request exceeds 64 KiB");
        fields(
                body,
                Set.of(
                        "protocolVersion",
                        "intent",
                        "metric",
                        "candidateFunction",
                        "requirements",
                        "consumer",
                        "constraints",
                        "idempotencyKey",
                        "externalTaskRef"));
        if (!body.path("protocolVersion").isIntegralNumber()
                || !body.path("protocolVersion").canConvertToInt()
                || body.path("protocolVersion").intValue() != 3
                || !"QUERY_METRIC".equals(text(body, "intent", 30)))
            throw new IllegalArgumentException("Expected protocolVersion 3 QUERY_METRIC");
        JsonNode requirements = body.path("requirements"),
                subject = requirements.path("statisticalSubject"),
                consumer = body.path("consumer"),
                constraints = body.path("constraints");
        fields(
                requirements,
                Set.of(
                        "statisticalSubject",
                        "unit",
                        "businessZone",
                        "timeRole",
                        "measures",
                        "dimensions",
                        "filters",
                        "from",
                        "to",
                        "view",
                        "verification"));
        fields(subject, Set.of("entityId", "modelVersion"));
        fields(consumer, Set.of("purpose"));
        fields(constraints, Set.of("maxRows", "maxBytes"));
        JsonNode filters = requirements.path("filters");
        if (!filters.isArray() || filters.size() > 32)
            throw new IllegalArgumentException(
                    "Semantic filters must be an array of at most 32 entries");
        var typedFilters = new ArrayList<Filter>();
        for (var filter : filters) {
            fields(filter, Set.of("field", "operation", "type", "value"));
            typedFilters.add(
                    new Filter(
                            text(filter, "field", 63),
                            RelationalIR.CompareOp.valueOf(text(filter, "operation", 10)),
                            ObjectType.DataType.valueOf(text(filter, "type", 30)),
                            textAllowEmpty(filter, "value", 4096)));
        }
        Optional<DataTask.ExternalTaskRef> external = Optional.empty();
        if (body.has("externalTaskRef")) {
            var ref = body.get("externalTaskRef");
            fields(ref, Set.of("issuer", "id"));
            external =
                    Optional.of(
                            new DataTask.ExternalTaskRef(
                                    text(ref, "issuer", 120), text(ref, "id", 240)));
        }
        try {
            return new SemanticMetricRequest(
                    pointer(body.path("metric")),
                    body.has("candidateFunction")
                            ? Optional.of(pointer(body.get("candidateFunction")))
                            : Optional.empty(),
                    new EntityRef(
                            text(subject, "entityId", 500), text(subject, "modelVersion", 120)),
                    text(requirements, "unit", 500),
                    ZoneId.of(text(requirements, "businessZone", 120)),
                    TemporalContract.TimeRole.valueOf(text(requirements, "timeRole", 40)),
                    arrayNames(requirements.path("measures"), "measures", false),
                    arrayNames(requirements.path("dimensions"), "dimensions", true),
                    typedFilters,
                    Instant.parse(text(requirements, "from", 100)),
                    Instant.parse(text(requirements, "to", 100)),
                    MetricQueryRequest.View.valueOf(text(requirements, "view", 40)),
                    MetricQueryRequest.Verification.valueOf(text(requirements, "verification", 40)),
                    text(consumer, "purpose", 500),
                    integer(constraints, "maxRows"),
                    integer(constraints, "maxBytes"),
                    text(body, "idempotencyKey", 120),
                    external);
        } catch (java.time.DateTimeException invalidTime) {
            throw new IllegalArgumentException(
                    "Semantic request requires explicit ISO-8601 time bounds and a valid business zone");
        }
    }

    public MetricQueryRequest withFunction(DataTask.ProposedFunction function) {
        return new MetricQueryRequest(
                function,
                from,
                to,
                view,
                verification,
                purpose,
                maxRows,
                maxBytes,
                idempotencyKey,
                externalTaskRef);
    }

    public ObjectNode normalized(JsonDocuments json) {
        var requirements = new LinkedHashMap<String, Object>();
        requirements.put(
                "statisticalSubject",
                Map.of(
                        "entityId",
                        statisticalSubject.id(),
                        "modelVersion",
                        statisticalSubject.modelVersion()));
        requirements.put("unit", unit);
        requirements.put("businessZone", businessZone.getId());
        requirements.put("timeRole", timeRole.name());
        requirements.put("measures", measures);
        requirements.put("dimensions", dimensions);
        requirements.put(
                "filters",
                filters.stream()
                        .map(
                                f ->
                                        Map.of(
                                                "field",
                                                f.field(),
                                                "operation",
                                                f.operation().name(),
                                                "type",
                                                f.type().name(),
                                                "value",
                                                f.value()))
                        .toList());
        requirements.put("from", from.toString());
        requirements.put("to", to.toString());
        requirements.put("view", view.name());
        requirements.put("verification", verification.name());
        var result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", 3);
        result.put("intent", "QUERY_METRIC");
        result.put("metric", pointer(metric));
        candidateFunction.ifPresent(f -> result.put("candidateFunction", pointer(f)));
        result.put("requirements", requirements);
        result.put("consumer", Map.of("purpose", purpose));
        result.put("constraints", Map.of("maxRows", maxRows, "maxBytes", maxBytes));
        result.put("idempotencyKey", idempotencyKey);
        externalTaskRef.ifPresent(
                r -> result.put("externalTaskRef", Map.of("issuer", r.issuer(), "id", r.id())));
        return json.object(json.write(result));
    }

    private static Map<String, String> pointer(DataTask.ProposedFunction ref) {
        return Map.of("releaseId", ref.releaseId().toString(), "contentHash", ref.contentHash());
    }

    private static DataTask.ProposedFunction pointer(JsonNode value) {
        fields(value, Set.of("releaseId", "contentHash"));
        return new DataTask.ProposedFunction(
                UUID.fromString(text(value, "releaseId", 36)), text(value, "contentHash", 64));
    }

    private static List<String> arrayNames(JsonNode values, String role, boolean empty) {
        if (!values.isArray() || values.size() > 32)
            throw new IllegalArgumentException(role + " must be an array of at most 32 names");
        var result = new ArrayList<String>();
        for (var value : values) {
            if (!value.isTextual())
                throw new IllegalArgumentException(role + " must contain names");
            result.add(value.asText());
        }
        return names(result, role, 32, empty);
    }

    private static List<String> names(List<String> values, String role, int max, boolean empty) {
        values = List.copyOf(values);
        if ((!empty && values.isEmpty())
                || values.size() > max
                || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("Invalid or duplicate " + role);
        values.forEach(RelationalIR::identifier);
        return values;
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject())
            throw new IllegalArgumentException("Semantic request component must be an object");
        node.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!allowed.contains(k))
                                throw new IllegalArgumentException(
                                        "Unsupported semantic request field: " + k);
                        });
    }

    private static String text(JsonNode node, String key, int limit) {
        String value = textAllowEmpty(node, key, limit);
        bounded(value, key, limit);
        return value;
    }

    private static String textAllowEmpty(JsonNode node, String key, int limit) {
        if (!node.path(key).isTextual() || node.path(key).asText().length() > limit)
            throw new IllegalArgumentException("Invalid text field: " + key);
        return node.path(key).asText();
    }

    private static void bounded(String value, String role, int limit) {
        if (value == null
                || value.isBlank()
                || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + role);
    }

    private static int integer(JsonNode node, String key) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt())
            throw new IllegalArgumentException("Expected bounded integer: " + key);
        return node.path(key).intValue();
    }
}
