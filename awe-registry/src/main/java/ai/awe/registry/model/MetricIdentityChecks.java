package ai.awe.registry.model;

import ai.awe.kernel.contract.AggregationContract;
import ai.awe.kernel.contract.MetricContract;
import ai.awe.kernel.contract.RelationalIR;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import java.util.*;

/** 统计身份必须落到权威主体键；同为 STRING 的事件号不能替代设备/用户身份。 */
public final class MetricIdentityChecks {
    private MetricIdentityChecks() {}

    public static void validate(
            SemanticModel.Metric metric,
            MetricContract contract,
            ObjectType subject,
            Map<String, SemanticModel.Dataset> datasets,
            Map<String, ObjectType> schemas) {
        String identity = contract.identityField();
        if (!identity.contains("."))
            identity = contract.sources().getFirst().alias() + "." + identity;
        int split = identity.indexOf('.');
        String alias = identity.substring(0, split), field = identity.substring(split + 1);
        var dataset = datasets.get(alias);
        if (!subject.ref().equals(contract.statisticalSubject())
                || dataset == null
                || !dataset.subjectType().equals(subject.ref())
                || subject.identityKeys().size() != 1
                || !subject.identityKeys().contains(field))
            throw new IllegalArgumentException(
                    "Metric statistical identity must use the declared source's authoritative single-field subject key; composite or mapped identities require an explicit supported identity model");
        var identityProperty =
                subject.properties().stream()
                        .filter(p -> p.name().equals(field))
                        .findFirst()
                        .orElseThrow();
        var sourceProperty =
                schemas.get(alias).properties().stream()
                        .filter(p -> p.name().equals(field))
                        .findFirst()
                        .orElseThrow();
        if (identityProperty.type() != sourceProperty.type())
            throw new IllegalArgumentException(
                    "Metric statistical identity type differs from its authoritative subject key");
        var outputs = origins(contract.plan(), subject, datasets, schemas);
        if (metric.aggregation().kind() != AggregationContract.Kind.COUNT_DISTINCT) return;
        if (contract.outputs().stream()
                .allMatch(output -> contract.grain().contains(output.name())))
            throw new IllegalArgumentException(
                    "COUNT_DISTINCT requires an authoritative identity population output outside its grain");
        for (String key : contract.grain()) {
            Origin origin = outputs.get(key);
            if (origin != null && origin.counted())
                throw new IllegalArgumentException(
                        "Metric grain cannot replace authoritative identity with a population aggregate");
        }
        for (var output : contract.outputs()) {
            if (contract.grain().contains(output.name())) continue;
            Origin origin = outputs.get(output.name());
            if (origin == null || !origin.counted() || !origin.field().equals("SUBJECT_IDENTITY"))
                throw new IllegalArgumentException(
                        "COUNT_DISTINCT population output must count the authoritative statistical identity, not an event ID or another same-typed field");
        }
    }

    private record Origin(String field, boolean counted) {}

    /** 投影改名、分组和集合操作保留身份来源；计算表达式不能自称为权威身份。 */
    private static Map<String, Origin> origins(
            RelationalIR.Node node,
            ObjectType subject,
            Map<String, SemanticModel.Dataset> datasets,
            Map<String, ObjectType> schemas) {
        return switch (node) {
            case RelationalIR.Scan scan -> {
                var result = new LinkedHashMap<String, Origin>();
                var dataset = datasets.get(scan.source());
                for (var property : schemas.get(scan.source()).properties()) {
                    String name = scan.source() + "." + property.name();
                    boolean identity =
                            dataset.subjectType().equals(subject.ref())
                                    && subject.identityKeys().contains(property.name());
                    result.put(name, new Origin(identity ? "SUBJECT_IDENTITY" : name, false));
                }
                yield result;
            }
            case RelationalIR.Filter filter -> origins(filter.input(), subject, datasets, schemas);
            case RelationalIR.Project project -> {
                var input = origins(project.input(), subject, datasets, schemas);
                var result = new LinkedHashMap<String, Origin>();
                for (var column : project.columns()) {
                    if (column.expression() instanceof RelationalIR.Field field
                            && input.get(field.name()) != null)
                        result.put(column.name(), input.get(field.name()));
                }
                yield result;
            }
            case RelationalIR.Aggregate aggregate -> {
                var input = origins(aggregate.input(), subject, datasets, schemas);
                var result = new LinkedHashMap<String, Origin>();
                for (String key : aggregate.groupBy())
                    if (input.get(key) != null) result.put(key, input.get(key));
                for (var measure : aggregate.measures()) {
                    if (measure.kind() == RelationalIR.AggregateKind.COUNT_DISTINCT
                            && measure.expression() instanceof RelationalIR.Field field) {
                        var value = input.get(field.name());
                        if (value != null && !value.counted())
                            result.put(measure.name(), new Origin(value.field(), true));
                    }
                }
                yield result;
            }
            case RelationalIR.Join join -> {
                var result = new LinkedHashMap<>(origins(join.left(), subject, datasets, schemas));
                result.putAll(origins(join.right(), subject, datasets, schemas));
                yield result;
            }
            case RelationalIR.SetOperation set -> {
                var left = origins(set.left(), subject, datasets, schemas);
                var right = origins(set.right(), subject, datasets, schemas);
                // 集合两侧的同名列仍须指向相同统计对象，不能混用设备号与事件号。
                var names = new HashSet<>(left.keySet());
                names.addAll(right.keySet());
                for (String name : names) {
                    Origin l = left.get(name), r = right.get(name);
                    if ((l != null && l.field().equals("SUBJECT_IDENTITY")
                                    || r != null && r.field().equals("SUBJECT_IDENTITY"))
                            && !Objects.equals(l, r))
                        throw new IllegalArgumentException(
                                "Set operands cannot mix authoritative statistical identity with another same-typed field");
                }
                // UNION 两侧都须证明同一身份；差集/交集只输出左侧，右侧用于集合筛选。
                if (set.operation() == RelationalIR.SetKind.UNION_DISTINCT)
                    left.entrySet()
                            .removeIf(entry -> !entry.getValue().equals(right.get(entry.getKey())));
                yield left;
            }
        };
    }
}
