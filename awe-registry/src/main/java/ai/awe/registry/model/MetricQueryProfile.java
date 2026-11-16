package ai.awe.registry.model;

import ai.awe.kernel.contract.MetricContract;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 查询能力绑定已发布 Metric 的正式返回契约，而不是任意结果 Schema 字符串。 */
public final class MetricQueryProfile {
    public static final String OPERATION = "metric.query.v1";
    public static final String POSTGRES_EXECUTOR = "postgres.metric.query.v1";
    public static final String BI_EXECUTOR = "bi.metric.query.v1";

    private MetricQueryProfile() {}

    public static boolean matches(FunctionDefinition function) {
        boolean operation = OPERATION.equals(function.operation()),
                executor =
                        Set.of(POSTGRES_EXECUTOR, BI_EXECUTOR)
                                .contains(function.executorBindingRef());
        if (operation != executor)
            throw new IllegalArgumentException("Metric query operation/executor must match");
        return operation;
    }

    public static void validate(
            FunctionDefinition function, SemanticModel.Metric metric, JsonNode spec) {
        if (!matches(function) || spec.path("schemaVersion").asInt() != 3)
            throw new IllegalArgumentException(
                    "Metric query requires an executable schemaVersion 3 Metric");
        validate(function, metric, new TypedModels().metricContract(spec));
    }

    public static void validate(
            FunctionDefinition function, SemanticModel.Metric metric, MetricContract contract) {
        if (!matches(function))
            throw new IllegalArgumentException("Function is not a registered metric query profile");
        if (!function.ownerType().equals(metric.ref())
                || !DatasetExtractProfile.parseResultRef(function.resultSchemaRef())
                        .equals(metric.ref()))
            throw new IllegalArgumentException(
                    "Metric query owner/result must pin the same Metric contract");
        if (function.parameters().size() != 2
                || !function.parameters().stream()
                        .allMatch(p -> p.required() && p.type() == ObjectType.DataType.TIMESTAMP)
                || !function.parameters().stream()
                        .map(FunctionDefinition.Parameter::name)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of("from", "to")))
            throw new IllegalArgumentException(
                    "Metric query uses explicit required TIMESTAMP from/to parameters");
        if (!function.requiredAssertions().isEmpty())
            throw new IllegalArgumentException(
                    "Static Evidence cannot replace metric runtime validation");
        if (BI_EXECUTOR.equals(function.executorBindingRef()) && contract.direct().isEmpty())
            throw new IllegalArgumentException(
                    "BI query requires a pinned DIRECT implementation contract");
    }
}
