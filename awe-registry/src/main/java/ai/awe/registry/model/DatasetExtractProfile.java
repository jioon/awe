package ai.awe.registry.model;

import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/** 受控 Dataset 取数的封闭执行签名；普通 Function 声明不因此自动获得执行能力。 */
public final class DatasetExtractProfile {
    public static final String OPERATION = "dataset.extract.v1";
    public static final String EXECUTOR = "postgres.dataset.extract.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Set<ObjectType.DataType> TYPES =
            Set.of(
                    ObjectType.DataType.STRING, ObjectType.DataType.BOOLEAN,
                    ObjectType.DataType.INTEGER, ObjectType.DataType.DECIMAL,
                    ObjectType.DataType.DATE, ObjectType.DataType.TIMESTAMP);

    private DatasetExtractProfile() {}

    /** 任一保留标识出现时必须完整匹配，不能把错误绑定静默降级成普通声明。 */
    public static boolean matches(FunctionDefinition function) {
        boolean operation = OPERATION.equals(function.operation());
        boolean executor = EXECUTOR.equals(function.executorBindingRef());
        if (operation != executor)
            throw new IllegalArgumentException("Dataset extract operation/executor must match");
        return operation;
    }

    public static EntityRef parseResultRef(String value) {
        if (value == null || value.indexOf('@') < 1 || value.indexOf('@') != value.lastIndexOf('@'))
            throw new IllegalArgumentException(
                    "Extract resultSchemaRef requires canonical id@version");
        int at = value.indexOf('@');
        String version = value.substring(at + 1);
        ModelCompatibility.Version.parse(version);
        return new EntityRef(value.substring(0, at), version);
    }

    /** 发布和运行共用同一规则：显式行 Schema、同名物理列和精确 Dataset 版本。 */
    public static void validate(
            FunctionDefinition function,
            SemanticModel.Dataset dataset,
            ObjectType rowSchema,
            JsonNode datasetSpec) {
        if (!matches(function))
            throw new IllegalArgumentException("Function is not an extract profile");
        if (!function.ownerType().equals(dataset.ref()))
            throw new IllegalArgumentException("Extract ownerType must pin this exact Dataset");
        if (!parseResultRef(function.resultSchemaRef()).equals(rowSchema.ref())
                || rowSchema.family() != ObjectType.Family.DATASET)
            throw new IllegalArgumentException(
                    "Extract resultSchemaRef must pin an ObjectType DATASET row schema");
        if (!rowSchema.identityKeys().equals(dataset.grain().keys()))
            throw new IllegalArgumentException(
                    "Row schema identity keys must match Dataset grain in order");
        if (rowSchema.properties().size() > 64)
            throw new IllegalArgumentException(
                    "Extract row schema exceeds supported column budget");
        for (var property : rowSchema.properties()) {
            if (!IDENTIFIER.matcher(property.name()).matches() || !TYPES.contains(property.type()))
                throw new IllegalArgumentException("Unsupported extract row property");
        }
        if (function.parameters().size() != 2
                || !function.parameters().stream()
                        .allMatch(p -> p.required() && p.type() == ObjectType.DataType.TIMESTAMP)
                || !function.parameters().stream()
                        .map(FunctionDefinition.Parameter::name)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of("from", "to")))
            throw new IllegalArgumentException(
                    "Extract parameters must be required TIMESTAMP from/to");
        if (!function.requiredAssertions().isEmpty())
            throw new IllegalArgumentException("Extract requiredAssertions are not implemented");
        if (!dataset.time().roles().contains(TemporalContract.TimeRole.EVENT_TIME))
            throw new IllegalArgumentException("Extract Dataset must declare EVENT_TIME");
        JsonNode bindings = datasetSpec == null ? null : datasetSpec.path("bindings");
        if (bindings == null || !bindings.isArray() || bindings.size() != 1)
            throw new IllegalArgumentException(
                    "Extract Dataset requires exactly one source binding");
        if (!"postgres".equals(bindings.get(0).path("sourceSystem").asText()))
            throw new IllegalArgumentException(
                    "Extract profile requires an explicit PostgreSQL source binding");
        JsonNode event = bindings.get(0).path("timeFields").path("EVENT_TIME");
        if (!event.isTextual() || !IDENTIFIER.matcher(event.asText()).matches())
            throw new IllegalArgumentException(
                    "Extract requires an explicit EVENT_TIME column mapping");
        if (rowSchema.properties().stream()
                .noneMatch(
                        p ->
                                p.name().equals(event.asText())
                                        && p.type() == ObjectType.DataType.TIMESTAMP))
            throw new IllegalArgumentException(
                    "Extract row schema must declare the EVENT_TIME column as TIMESTAMP");
    }

    /** 同名列不得在返回 Schema 中绕过主体的类型或敏感分类；本 Profile 不支持隐式转换或脱敏。 */
    public static void validateSubject(ObjectType rowSchema, ObjectType subject) {
        for (var property : rowSchema.properties()) {
            var source =
                    subject.properties().stream()
                            .filter(p -> p.name().equals(property.name()))
                            .findFirst();
            if (source.isEmpty()) continue;
            if (source.get().type() != property.type())
                throw new IllegalArgumentException(
                        "Extract row property type differs from subject classification");
            if (source.get().sensitive() && !property.sensitive())
                throw new IllegalArgumentException(
                        "Extract row schema cannot downgrade sensitive subject properties");
        }
    }
}
