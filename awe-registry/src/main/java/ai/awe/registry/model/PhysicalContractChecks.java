package ai.awe.registry.model;

import ai.awe.kernel.contract.AggregationContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.AweDefinition;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 比较模型与权威元数据；Schema 和声明粒度一致，仍不能证明真实数据唯一性或质量。 */
public final class PhysicalContractChecks {
    public List<String> errors(
            AweDefinition model, JsonNode spec, JsonNode binding, JsonNode metadata) {
        var errors = new ArrayList<String>();
        var columns = columns(metadata);
        if (model instanceof ObjectType object) {
            for (var property : object.properties()) {
                JsonNode column = columns.get(property.name());
                if (column == null)
                    errors.add("Missing object property column: " + property.name());
                else {
                    if (!compatibleType(property.type(), column.path("type").asText()))
                        errors.add("Object property type differs from source: " + property.name());
                    if (property.required() != !column.path("nullable").asBoolean(true))
                        errors.add(
                                "Object property nullability differs from source: "
                                        + property.name());
                }
            }
            if (!new TreeSet<>(object.identityKeys()).equals(names(metadata.path("grainKeys"))))
                errors.add("Object identity keys differ from declared source grain");
            for (String identity : object.identityKeys()) {
                var column = columns.get(identity);
                if (column == null || column.path("nullable").asBoolean(true))
                    errors.add("Object identity must map to non-null source columns: " + identity);
            }
        } else if (model instanceof SemanticModel semantic) {
            if (!new TreeSet<>(semantic.grain().keys()).equals(names(metadata.path("grainKeys"))))
                errors.add("Declared source grain does not match model grain");
            checkGrainColumns(semantic, columns, errors);
            checkTime(semantic, binding, metadata, columns, errors);
            if (semantic instanceof SemanticModel.Feature feature)
                checkFeature(feature, binding, metadata, columns, errors);
            if (semantic instanceof SemanticModel.Metric metric) checkAggregation(metric, errors);
            if ("bi".equals(binding.path("sourceSystem").asText())) {
                var expected = spec.path("model").deepCopy();
                if (expected instanceof ObjectNode object) object.remove("ref");
                if (!metadata.path("definition").equals(expected))
                    errors.add("Authoritative BI semantic definition missing or different");
            }
        } else errors.add("Direct physical bindings are not supported for this declaration family");
        return List.copyOf(errors);
    }

    /** 派生 Metric/Feature 即使没有直接物理绑定，也必须满足精确版本 Dataset 的消费契约。 */
    public List<String> errorsAgainstDataset(
            AweDefinition consumer,
            SemanticModel.Dataset sourceModel,
            JsonNode sourceBinding,
            JsonNode metadata) {
        var errors = new ArrayList<String>();
        if (!(consumer instanceof SemanticModel semantic))
            return List.of("Only semantic consumers can use a source Dataset");
        var columns = columns(metadata);
        EntityRef subject;
        if (consumer instanceof SemanticModel.Metric metric) {
            subject = metric.subjectType();
            if (!sourceModel.dimensions().containsAll(metric.allowedDimensions()))
                errors.add("Metric dimensions are outside the pinned source Dataset contract");
            checkAggregation(metric, errors);
        } else if (consumer instanceof SemanticModel.Feature feature)
            subject = feature.subjectType();
        else return List.of("Only Metric and Feature declarations derive from a source Dataset");
        if (!subject.equals(sourceModel.subjectType()))
            errors.add("Consumer subject type differs from pinned source Dataset");
        if (!sourceModel.time().roles().containsAll(semantic.time().roles()))
            errors.add("Consumer time roles are not supplied by pinned source Dataset");
        if (!semantic.time().businessZone().equals(sourceModel.time().businessZone()))
            errors.add("Consumer business timezone differs from pinned source Dataset");
        checkGrainColumns(semantic, columns, errors);
        checkTime(semantic, sourceBinding, metadata, columns, errors);
        if (semantic instanceof SemanticModel.Feature feature)
            checkFeature(feature, sourceBinding, metadata, columns, errors);
        return List.copyOf(errors);
    }

    private void checkAggregation(SemanticModel.Metric metric, List<String> errors) {
        if (metric.aggregation().kind() == AggregationContract.Kind.CUSTOM)
            errors.add("CUSTOM aggregation has no supported publication checker");
    }

    private void checkGrainColumns(
            SemanticModel semantic, Map<String, JsonNode> columns, List<String> errors) {
        for (String key : semantic.grain().keys())
            if (!columns.containsKey(key)) errors.add("Missing grain column: " + key);
    }

    /** 时间角色必须映射到来源中实际存在且类型正确的列，不能只比较角色名称。 */
    private void checkTime(
            SemanticModel semantic,
            JsonNode binding,
            JsonNode metadata,
            Map<String, JsonNode> columns,
            List<String> errors) {
        var declaredTimes = names(metadata.path("timeFields"));
        for (var role : semantic.time().roles()) {
            String field = binding.path("timeFields").path(role.name()).asText();
            var column = columns.get(field);
            if (field.isBlank()
                    || !declaredTimes.contains(field)
                    || column == null
                    || !temporal(column.path("type").asText()))
                errors.add("Missing or non-temporal source field for " + role);
        }
    }

    private void checkFeature(
            SemanticModel.Feature feature,
            JsonNode binding,
            JsonNode metadata,
            Map<String, JsonNode> columns,
            List<String> errors) {
        // 事件发生时间与当时可获知时间分开校验，避免回补数据泄漏到历史特征。
        String observation = feature.observationTimeField(), available = feature.availableAtField();
        if (observation.equals(available))
            errors.add("Feature observation and availability fields must be distinct");
        for (String field : List.of(observation, available)) {
            var column = columns.get(field);
            if (!names(metadata.path("timeFields")).contains(field)
                    || column == null
                    || !timestamp(column.path("type").asText()))
                errors.add("Feature historical timestamp field missing or invalid: " + field);
        }
        if (!observation.equals(binding.path("timeFields").path("EVENT_TIME").asText()))
            errors.add("Feature observation field must match the source EVENT_TIME mapping");
        String availability = binding.path("timeFields").path("AVAILABLE_AT").asText();
        if (availability.isBlank())
            availability = binding.path("timeFields").path("SYSTEM_TIME").asText();
        if (!available.equals(availability))
            errors.add("Feature availability field must match the source availability mapping");
    }

    private Map<String, JsonNode> columns(JsonNode metadata) {
        var columns = new HashMap<String, JsonNode>();
        metadata.path("columns").forEach(c -> columns.put(c.path("name").asText(), c));
        return columns;
    }

    private Set<String> names(JsonNode values) {
        var names = new TreeSet<String>();
        values.forEach(n -> names.add(n.asText()));
        return names;
    }

    private boolean temporal(String type) {
        return "date".equals(normalize(type)) || timestamp(type);
    }

    private boolean timestamp(String type) {
        return normalize(type)
                .matches(
                        "(timestamp(?:\\(\\d+\\))?(?: with(?:out)? time zone)?|timestamptz|datetime(?:\\(\\d+\\))?)");
    }

    private String normalize(String type) {
        return type.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private boolean compatibleType(ObjectType.DataType type, String physical) {
        String value = normalize(physical);
        return switch (type) {
            case STRING ->
                    value.matches(
                            "(string|text|varchar(?:\\(\\d+\\))?|char(?:\\(\\d+\\))?|character varying(?:\\(\\d+\\))?)");
            case BOOLEAN -> Set.of("boolean", "bool").contains(value);
            case INTEGER ->
                    Set.of(
                                    "tinyint",
                                    "smallint",
                                    "integer",
                                    "int",
                                    "bigint",
                                    "int2",
                                    "int4",
                                    "int8")
                            .contains(value);
            case DECIMAL -> value.matches("(decimal|numeric)(?:\\(\\d+(?:,\\s*\\d+)?\\))?");
            case DATE -> value.equals("date");
            case TIMESTAMP -> timestamp(value);
            case ENTITY_REF -> value.matches("(string|text|varchar(?:\\(\\d+\\))?)");
            case JSON -> Set.of("json", "jsonb").contains(value);
        };
    }
}
