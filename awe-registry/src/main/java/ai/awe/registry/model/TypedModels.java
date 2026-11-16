package ai.awe.registry.model;

import ai.awe.kernel.contract.DatasetContract;
import ai.awe.kernel.contract.Grain;
import ai.awe.kernel.contract.MetricContract;
import ai.awe.kernel.contract.PipelineContract;
import ai.awe.kernel.contract.RelationalIR;
import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ActionDefinition;
import ai.awe.kernel.model.AweDefinition;
import ai.awe.kernel.model.ContractDefinition;
import ai.awe.kernel.model.EvidenceAssertion;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.LinkType;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** 将建模文档严格还原为消费端共享的内核契约；禁止使用自由 JSON 绕过类型不变量。 */
public final class TypedModels {
    private static final Map<String, Class<? extends AweDefinition>> TYPES =
            Map.ofEntries(
                    Map.entry("ObjectType", ObjectType.class),
                    Map.entry("LinkType", LinkType.class),
                    Map.entry("Dataset", SemanticModel.Dataset.class),
                    Map.entry("Metric", SemanticModel.Metric.class),
                    Map.entry("Feature", SemanticModel.Feature.class),
                    Map.entry("FunctionDefinition", FunctionDefinition.class),
                    Map.entry("ActionDefinition", ActionDefinition.class),
                    Map.entry("ContractDefinition", ContractDefinition.class),
                    Map.entry("EvidenceAssertion", EvidenceAssertion.class));
    private static final JsonNodeFactory N = JsonNodeFactory.instance;
    private final JsonDocuments json = new JsonDocuments();

    public Set<String> types() {
        return TYPES.keySet();
    }

    /** 显式版本迁移、完整必需字段和封闭类型检查；不推断旧模型缺失的身份或语义。 */
    public AweDefinition decode(String type, JsonNode spec) {
        if (spec == null || !spec.isObject())
            throw new IllegalArgumentException("spec must be an object");
        var version = spec.path("schemaVersion");
        if (!version.isIntegralNumber()
                || !version.canConvertToInt()
                || (version.intValue() != 2 && version.intValue() != 3))
            throw new IllegalArgumentException(
                    "Migrate legacy document explicitly to schemaVersion 2 or 3 before submission");
        Class<? extends AweDefinition> target = type == null ? null : TYPES.get(type);
        if (target == null) throw new IllegalArgumentException("Unsupported typed model: " + type);
        fields(
                spec,
                "spec",
                Set.of(
                        "schemaVersion",
                        "name",
                        "definition",
                        "ownerTeam",
                        "model",
                        "bindings",
                        "dependsOn",
                        "metricContract",
                        "pipelineContract",
                        "datasetContract"));
        text(spec.path("name"), "name");
        text(spec.path("definition"), "definition");
        String owner = text(spec.path("ownerTeam"), "ownerTeam");
        AweDefinition definition = (AweDefinition) read(spec.path("model"), target, "model");
        if (version.intValue() == 2) {
            if (spec.has("metricContract")
                    || spec.has("pipelineContract")
                    || spec.has("datasetContract"))
                throw new IllegalArgumentException(
                        "Typed executable facets require explicit schemaVersion 3 migration");
            if (definition instanceof SemanticModel semantic && semantic.grain().keys().isEmpty())
                throw new IllegalArgumentException(
                        "Schema v2 preserves its nonempty grain contract");
        } else validateFacets(definition, spec);
        if (definition instanceof ContractDefinition c && !c.ownerTeam().equals(owner))
            throw new IllegalArgumentException("Contract ownerTeam must match envelope ownerTeam");
        if (definition instanceof EvidenceAssertion e
                && !e.modelReleaseHash().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException(
                    "Evidence modelReleaseHash must be an exact SHA-256 release digest");
        if (spec.has("dependsOn"))
            read(spec.get("dependsOn"), new Parameterized(Set.class, EntityRef.class), "dependsOn");
        if (spec.has("bindings")) {
            JsonNode bindings = spec.get("bindings");
            if (!bindings.isArray())
                throw new IllegalArgumentException("bindings must be an array");
            Set<String> seen = new HashSet<>();
            for (JsonNode b : bindings) {
                fields(
                        b,
                        "binding",
                        Set.of("sourceSystem", "externalRef", "fingerprint", "timeFields"));
                String system = text(b.path("sourceSystem"), "binding.sourceSystem");
                String external = text(b.path("externalRef"), "binding.externalRef");
                String fingerprint = text(b.path("fingerprint"), "binding.fingerprint");
                if (!fingerprint.matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException(
                            "Binding fingerprint must be an exact SHA-256 source digest");
                Set<TemporalContract.TimeRole> mappedRoles = new HashSet<>();
                if (b.has("timeFields")) {
                    JsonNode mapping = b.get("timeFields");
                    if (!mapping.isObject())
                        throw new IllegalArgumentException("binding.timeFields must be an object");
                    mapping.fields()
                            .forEachRemaining(
                                    entry -> {
                                        TemporalContract.TimeRole role;
                                        try {
                                            role =
                                                    TemporalContract.TimeRole.valueOf(
                                                            entry.getKey());
                                        } catch (IllegalArgumentException e) {
                                            throw new IllegalArgumentException(
                                                    "Unsupported time role in binding.timeFields");
                                        }
                                        text(
                                                entry.getValue(),
                                                "binding.timeFields." + entry.getKey());
                                        mappedRoles.add(role);
                                    });
                }
                boolean staticSnapshot =
                        definition instanceof SemanticModel.Dataset
                                && version.intValue() == 3
                                && datasetContract(spec).readMode()
                                        == DatasetContract.ReadMode.STATIC_SNAPSHOT;
                if (staticSnapshot && !mappedRoles.isEmpty())
                    throw new IllegalArgumentException(
                            "Static snapshot binding cannot invent a physical observation-time column");
                if (definition instanceof SemanticModel semantic
                        && !staticSnapshot
                        && !mappedRoles.equals(semantic.time().roles()))
                    throw new IllegalArgumentException(
                            "binding.timeFields must map exactly the declared semantic time roles");
                if (!seen.add(system + "\u0000" + external))
                    throw new IllegalArgumentException("Duplicate source binding");
            }
        }
        return definition;
    }

    /** 提取带精确版本的模型引用；定义自身的 ref 不是依赖，其他位置的自引用仍交给发布边界拒绝。 */
    public Set<EntityRef> references(AweDefinition definition) {
        var result = new LinkedHashSet<EntityRef>();
        for (RecordComponent component : definition.getClass().getRecordComponents())
            if (!component.getName().equals("ref"))
                collect(componentValue(component, definition), result);
        return Collections.unmodifiableSet(result);
    }

    /** Facet 与基础定义共享同一引用清单和发布 Hash，不形成第二份可独立修改的权威。 */
    public Set<EntityRef> references(String type, JsonNode spec) {
        var definition = decode(type, spec);
        var refs = new LinkedHashSet<>(references(definition));
        if (spec.path("schemaVersion").asInt() == 3) {
            if (definition instanceof SemanticModel.Metric) collect(metricContract(spec), refs);
            if (definition instanceof SemanticModel.Dataset) collect(datasetContract(spec), refs);
            if (definition instanceof ObjectType object
                    && object.family() == ObjectType.Family.PIPELINE)
                collect(pipelineContract(spec), refs);
        }
        return Collections.unmodifiableSet(refs);
    }

    public MetricContract metricContract(JsonNode spec) {
        return facet(spec, "metricContract", MetricContract.class);
    }

    public PipelineContract pipelineContract(JsonNode spec) {
        return facet(spec, "pipelineContract", PipelineContract.class);
    }

    public DatasetContract datasetContract(JsonNode spec) {
        return facet(spec, "datasetContract", DatasetContract.class);
    }

    public JsonNode encodeValue(Object value) {
        return encode(value);
    }

    private <T> T facet(JsonNode spec, String name, Class<T> type) {
        if (spec.path("schemaVersion").asInt() != 3 || !spec.has(name))
            throw new IllegalArgumentException("A schemaVersion 3 " + name + " is required");
        boundedFacet(spec.get(name));
        return type.cast(read(spec.get(name), type, name));
    }

    private void validateFacets(AweDefinition definition, JsonNode spec) {
        String expected;
        if (definition instanceof SemanticModel.Metric metric) {
            expected = "metricContract";
            var contract = metricContract(spec);
            if (!contract.statisticalSubject().equals(metric.subjectType())
                    || contract.timeRole() == null
                    || !metric.time().roles().contains(contract.timeRole())
                    || !contract.businessZone().equals(metric.time().businessZone()))
                throw new IllegalArgumentException(
                        "Metric facet subject/time must equal the formal Metric definition");
            if (metric.grain().kind() != Grain.GrainKind.AGGREGATE
                    || !metric.grain().keys().equals(contract.grain()))
                throw new IllegalArgumentException(
                        "Metric aggregate grain must equal the declared output grain");
        } else if (definition instanceof SemanticModel.Dataset dataset) {
            expected = "datasetContract";
            var contract = datasetContract(spec);
            if (!dataset.time().roles().contains(contract.rangeRole()))
                throw new IllegalArgumentException("Dataset must declare its exact read time role");
            if (contract.readMode() == DatasetContract.ReadMode.STATIC_SNAPSHOT
                    && !dataset.time()
                            .roles()
                            .equals(Set.of(TemporalContract.TimeRole.OBSERVED_AT)))
                throw new IllegalArgumentException(
                        "Static datasets declare transaction OBSERVED_AT only");
            if (contract.mode() == DatasetContract.Mode.LOGICAL_TARGET
                    && !spec.path("bindings").isEmpty())
                throw new IllegalArgumentException(
                        "Logical unmaterialized target cannot claim an observed source binding");
        } else if (definition instanceof ObjectType object
                && object.family() == ObjectType.Family.PIPELINE) {
            expected = "pipelineContract";
            pipelineContract(spec);
        } else
            throw new IllegalArgumentException(
                    "No schemaVersion 3 facet registered for this metamodel family");
        for (String name : List.of("metricContract", "pipelineContract", "datasetContract"))
            if (!name.equals(expected) && spec.has(name))
                throw new IllegalArgumentException(
                        "Facet is incompatible with model family: " + name);
    }

    private void boundedFacet(JsonNode root) {
        record Item(JsonNode node, int depth) {}
        var pending = new ArrayDeque<Item>();
        pending.add(new Item(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            var item = pending.removeFirst();
            if (++nodes > 8192 || item.depth() > 64)
                throw new IllegalArgumentException("Typed facet complexity budget exceeded");
            if (item.node().isContainerNode())
                for (JsonNode child : item.node())
                    pending.addLast(new Item(child, item.depth() + 1));
        }
    }

    /** 先通过真实类型校验，再规范集合及绑定顺序，使等价输入生成稳定的发布内容。 */
    public JsonNode canonicalSpec(String type, JsonNode spec) {
        AweDefinition definition = decode(type, spec);
        ObjectNode result = N.objectNode();
        result.put("schemaVersion", spec.path("schemaVersion").intValue());
        for (String key : List.of("name", "definition", "ownerTeam"))
            result.set(key, spec.get(key));
        result.set("model", encode(definition));
        if (spec.has("metricContract")) result.set("metricContract", encode(metricContract(spec)));
        if (spec.has("pipelineContract"))
            result.set("pipelineContract", encode(pipelineContract(spec)));
        if (spec.has("datasetContract"))
            result.set("datasetContract", encode(datasetContract(spec)));
        if (spec.has("dependsOn"))
            result.set(
                    "dependsOn",
                    encode(
                            read(
                                    spec.get("dependsOn"),
                                    new Parameterized(Set.class, EntityRef.class),
                                    "dependsOn")));
        if (spec.has("bindings")) {
            List<JsonNode> bindings = new ArrayList<>();
            spec.get("bindings").forEach(bindings::add);
            bindings.sort(Comparator.comparing(json::canonical));
            ArrayNode array = N.arrayNode();
            bindings.forEach(array::add);
            result.set("bindings", array);
        }
        return result;
    }

    private void collect(Object value, Set<EntityRef> refs) {
        if (value instanceof EntityRef ref) refs.add(ref);
        else if (value instanceof Optional<?> optional)
            optional.ifPresent(child -> collect(child, refs));
        else if (value instanceof Collection<?> list) list.forEach(x -> collect(x, refs));
        else if (value != null && value.getClass().isRecord())
            for (RecordComponent c : value.getClass().getRecordComponents())
                collect(componentValue(c, value), refs);
    }

    private Object read(JsonNode node, Type type, String path) {
        if (type instanceof ParameterizedType optional && optional.getRawType() == Optional.class) {
            if (node == null || node.isMissingNode())
                throw new IllegalArgumentException("Missing required field: " + path);
            return node.isNull()
                    ? Optional.empty()
                    : Optional.of(read(node, optional.getActualTypeArguments()[0], path));
        }
        if (node == null || node.isNull() || node.isMissingNode())
            throw new IllegalArgumentException("Missing required field: " + path);
        if (type instanceof ParameterizedType parameterized) {
            if (!node.isArray()) throw new IllegalArgumentException(path + " must be an array");
            var values = new ArrayList<Object>();
            for (JsonNode child : node)
                values.add(read(child, parameterized.getActualTypeArguments()[0], path + "[]"));
            if (parameterized.getRawType() == Set.class) {
                var unique = new LinkedHashSet<>(values);
                if (unique.size() != values.size())
                    throw new IllegalArgumentException("Duplicate value in " + path);
                return unique;
            }
            if (parameterized.getRawType() == List.class) return values;
            throw new IllegalArgumentException("Unsupported collection: " + path);
        }
        Class<?> target = (Class<?>) type;
        if (target == int.class) {
            if (!node.isIntegralNumber() || !node.canConvertToInt())
                throw new IllegalArgumentException(path + " requires an integer");
            return node.intValue();
        }
        if (target == RelationalIR.Node.class
                || target == RelationalIR.Expr.class
                || target == RelationalIR.Predicate.class) {
            String kind = text(node.path("kind"), path + ".kind");
            Class<?> concrete = taggedType(target, kind);
            var value = ((ObjectNode) node).deepCopy();
            value.remove("kind");
            return read(value, concrete, path);
        }
        if (target == RelationalIR.Literal.class) {
            fields(node, path, Set.of("type", "value", "kind"));
            if (node.has("kind") && !"LITERAL".equals(node.path("kind").asText()))
                throw new IllegalArgumentException("Invalid typed literal operator tag");
            if (!node.path("value").isTextual())
                throw new IllegalArgumentException("Typed literal value must be a lexical string");
            return new RelationalIR.Literal(
                    (ObjectType.DataType)
                            read(node.path("type"), ObjectType.DataType.class, path + ".type"),
                    node.path("value").textValue());
        }
        if (target == String.class) return text(node, path);
        if (target == boolean.class) {
            if (!node.isBoolean()) throw new IllegalArgumentException(path + " must be boolean");
            return node.booleanValue();
        }
        if (target == ZoneId.class) {
            try {
                return ZoneId.of(text(node, path));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid time zone: " + path);
            }
        }
        if (target == Instant.class) {
            try {
                return Instant.parse(text(node, path));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid ISO instant: " + path);
            }
        }
        if (target.isEnum()) {
            String value = text(node, path);
            for (Object option : target.getEnumConstants())
                if (((Enum<?>) option).name().equals(value)) return option;
            throw new IllegalArgumentException("Unsupported enum value: " + path);
        }
        // 以内核 record 的真实字段和构造器为准，拒绝未知字段、缺省值和字符串到布尔值等隐式转换。
        if (target.isRecord()) {
            RecordComponent[] components = target.getRecordComponents();
            Set<String> names = new HashSet<>();
            for (var c : components) names.add(c.getName());
            fields(node, path, names);
            Class<?>[] parameters = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var c = components[i];
                parameters[i] = c.getType();
                values[i] =
                        read(node.path(c.getName()), c.getGenericType(), path + "." + c.getName());
            }
            try {
                return target.getDeclaredConstructor(parameters).newInstance(values);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                String detail =
                        cause instanceof IllegalArgumentException
                                ? cause.getMessage()
                                : "invalid required value";
                throw new IllegalArgumentException("Invalid " + path + ": " + detail);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported metamodel record", e);
            }
        }
        throw new IllegalArgumentException("Unsupported model field: " + path);
    }

    private JsonNode encode(Object value) {
        if (value instanceof Optional<?> optional)
            return optional.isPresent() ? encode(optional.get()) : N.nullNode();
        if (value instanceof Integer number) return N.numberNode(number);
        if (value instanceof String s) return N.textNode(s);
        if (value instanceof Boolean b) return N.booleanNode(b);
        if (value instanceof Enum<?> e) return N.textNode(e.name());
        if (value instanceof ZoneId z) return N.textNode(z.getId());
        if (value instanceof Instant i) return N.textNode(i.toString());
        if (value instanceof Collection<?> collection) {
            List<JsonNode> values = new ArrayList<>();
            collection.forEach(x -> values.add(encode(x)));
            // Set 的遍历顺序不参与语义；List 的业务顺序必须原样保留。
            if (value instanceof Set<?>) values.sort(Comparator.comparing(json::canonical));
            ArrayNode result = N.arrayNode();
            values.forEach(result::add);
            return result;
        }
        if (value != null && value.getClass().isRecord()) {
            ObjectNode result = N.objectNode();
            if (value instanceof RelationalIR.Node
                    || value instanceof RelationalIR.Expr
                    || value instanceof RelationalIR.Predicate)
                result.put("kind", taggedName(value));
            for (RecordComponent c : value.getClass().getRecordComponents())
                result.set(c.getName(), encode(componentValue(c, value)));
            return result;
        }
        throw new IllegalStateException("Unsupported typed serialization");
    }

    private Class<?> taggedType(Class<?> family, String kind) {
        var choices =
                family == RelationalIR.Node.class
                        ? Map.<String, Class<?>>of(
                                "SCAN",
                                RelationalIR.Scan.class,
                                "FILTER",
                                RelationalIR.Filter.class,
                                "PROJECT",
                                RelationalIR.Project.class,
                                "AGGREGATE",
                                RelationalIR.Aggregate.class,
                                "JOIN",
                                RelationalIR.Join.class,
                                "SET",
                                RelationalIR.SetOperation.class)
                        : family == RelationalIR.Expr.class
                                ? Map.<String, Class<?>>of(
                                        "FIELD",
                                        RelationalIR.Field.class,
                                        "LITERAL",
                                        RelationalIR.Literal.class,
                                        "BUSINESS_DATE",
                                        RelationalIR.BusinessDate.class,
                                        "ARITHMETIC",
                                        RelationalIR.Arithmetic.class)
                                : Map.<String, Class<?>>of(
                                        "COMPARE",
                                        RelationalIR.Compare.class,
                                        "ALL",
                                        RelationalIR.All.class,
                                        "ANY",
                                        RelationalIR.Any.class,
                                        "NOT",
                                        RelationalIR.Not.class,
                                        "IS_NULL",
                                        RelationalIR.IsNull.class,
                                        "IN",
                                        RelationalIR.In.class);
        Class<?> result = choices.get(kind);
        if (result == null)
            throw new IllegalArgumentException("Unsupported typed semantic operator: " + kind);
        return result;
    }

    private String taggedName(Object value) {
        return switch (value) {
            case RelationalIR.Scan ignored -> "SCAN";
            case RelationalIR.Filter ignored -> "FILTER";
            case RelationalIR.Project ignored -> "PROJECT";
            case RelationalIR.Aggregate ignored -> "AGGREGATE";
            case RelationalIR.Join ignored -> "JOIN";
            case RelationalIR.SetOperation ignored -> "SET";
            case RelationalIR.Field ignored -> "FIELD";
            case RelationalIR.Literal ignored -> "LITERAL";
            case RelationalIR.BusinessDate ignored -> "BUSINESS_DATE";
            case RelationalIR.Arithmetic ignored -> "ARITHMETIC";
            case RelationalIR.Compare ignored -> "COMPARE";
            case RelationalIR.All ignored -> "ALL";
            case RelationalIR.Any ignored -> "ANY";
            case RelationalIR.Not ignored -> "NOT";
            case RelationalIR.IsNull ignored -> "IS_NULL";
            case RelationalIR.In ignored -> "IN";
            default -> throw new IllegalArgumentException("Unregistered semantic operator");
        };
    }

    private Object componentValue(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read metamodel record", e);
        }
    }

    private void fields(JsonNode node, String path, Set<String> allowed) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException(path + " must be an object");
        node.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!allowed.contains(key))
                                throw new IllegalArgumentException(
                                        "Unknown field: " + path + "." + key);
                        });
    }

    private String text(JsonNode node, String path) {
        if (node == null || !node.isTextual() || node.textValue().isBlank())
            throw new IllegalArgumentException("Nonempty string required: " + path);
        return node.textValue();
    }

    private record Parameterized(Type raw, Type element) implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return new Type[] {element};
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }
}
