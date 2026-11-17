package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.MetricContract;
import ai.awe.kernel.contract.RelationalIR;
import java.util.*;

/** 只从已发布口径做可证明的分组与维度筛选；重新计算原聚合，不对已去重计数求和。 */
public final class MetricDerivation {
    private MetricDerivation() {}

    public static final class UnsupportedDerivation extends IllegalArgumentException {
        private final String reason;

        public UnsupportedDerivation(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    public static MetricContract derive(MetricContract original, SemanticMetricRequest request) {
        require(
                original.statisticalSubject().equals(request.statisticalSubject()),
                "SUBJECT_MISMATCH",
                "The requested statistical subject differs from the published metric");
        require(
                original.unit().equals(request.unit()),
                "UNIT_MISMATCH",
                "The requested unit differs from the published metric");
        require(
                original.businessZone().equals(request.businessZone()),
                "BUSINESS_ZONE_MISMATCH",
                "The requested business zone differs from the published metric");
        require(
                original.timeRole() == request.timeRole(),
                "TIME_ROLE_MISMATCH",
                "The requested time role differs from the published metric");
        var outputs = new LinkedHashMap<String, MetricContract.Output>();
        original.outputs().forEach(o -> outputs.put(o.name(), o));
        var measures = new LinkedHashSet<>(outputs.keySet());
        measures.removeAll(original.grain());
        require(
                measures.containsAll(request.measures()),
                "UNKNOWN_MEASURE",
                "A requested measure is not a published measure output");
        require(
                original.grain().containsAll(request.dimensions()),
                "UNKNOWN_DIMENSION",
                "A requested dimension is not part of the published grain");
        for (var filter : request.filters()) {
            require(
                    original.grain().contains(filter.field()),
                    "FILTER_NOT_DIMENSION",
                    "Filters may constrain only published dimension outputs");
            require(
                    outputs.get(filter.field()).type() == filter.type(),
                    "FILTER_TYPE_MISMATCH",
                    "The typed filter differs from its published dimension type");
        }
        // 完全相同的要求可复用原计划和 DIRECT 绑定；复杂口径也不会被误当作可重组聚合。
        if (request.filters().isEmpty()
                && new HashSet<>(request.dimensions()).equals(new HashSet<>(original.grain()))
                && new HashSet<>(request.measures()).equals(measures)) return original;

        RelationalIR.Aggregate aggregate;
        var bindings = new LinkedHashMap<String, String>();
        if (original.plan() instanceof RelationalIR.Aggregate a) {
            aggregate = a;
            a.groupBy().forEach(name -> bindings.put(name, name));
            a.measures().forEach(m -> bindings.put(m.name(), m.name()));
        } else if (original.plan() instanceof RelationalIR.Project project
                && project.input() instanceof RelationalIR.Aggregate a) {
            aggregate = a;
            for (var column : project.columns()) {
                require(
                        column.expression() instanceof RelationalIR.Field,
                        "UNSUPPORTED_PLAN_SHAPE",
                        "Derived requests require direct field output mappings");
                bindings.put(column.name(), ((RelationalIR.Field) column.expression()).name());
            }
        } else {
            throw new UnsupportedDerivation(
                    "UNSUPPORTED_PLAN_SHAPE",
                    "This metric requires an explicitly supported derivation rather than guessed rollup");
        }
        var originalMeasures = new LinkedHashMap<String, RelationalIR.Measure>();
        aggregate.measures().forEach(m -> originalMeasures.put(m.name(), m));
        var allGroupMappings = new ArrayList<String>();
        for (String dimension : original.grain()) {
            String source = bindings.get(dimension);
            require(
                    source != null && aggregate.groupBy().contains(source),
                    "AMBIGUOUS_DIMENSION_BINDING",
                    "A published dimension has no unambiguous aggregate group mapping");
            allGroupMappings.add(source);
        }
        require(
                new HashSet<>(allGroupMappings).size() == allGroupMappings.size()
                        && new HashSet<>(allGroupMappings)
                                .equals(new HashSet<>(aggregate.groupBy())),
                "AMBIGUOUS_DIMENSION_BINDING",
                "The published grain must expose exactly the aggregate group keys");
        for (String measure : measures)
            require(
                    originalMeasures.containsKey(bindings.get(measure)),
                    "UNSUPPORTED_PLAN_SHAPE",
                    "A published measure is not a direct aggregate output");

        RelationalIR.Node input = aggregate.input();
        if (!request.filters().isEmpty()) {
            var predicates = new ArrayList<RelationalIR.Predicate>();
            for (var filter : request.filters())
                predicates.add(
                        new RelationalIR.Compare(
                                filter.operation(),
                                new RelationalIR.Field(bindings.get(filter.field())),
                                filter.literal()));
            // 保留完整原始业务输入和筛选；新增筛选作用于聚合前的合法维度，而非结果截断。
            input =
                    new RelationalIR.Filter(
                            input,
                            predicates.size() == 1
                                    ? predicates.getFirst()
                                    : new RelationalIR.All(predicates));
        }
        var selectedMeasures = new LinkedHashMap<String, RelationalIR.Measure>();
        request.measures()
                .forEach(
                        name -> {
                            var measure = originalMeasures.get(bindings.get(name));
                            selectedMeasures.put(measure.name(), measure);
                        });
        var grouped =
                new RelationalIR.Aggregate(
                        input,
                        request.dimensions().stream().map(bindings::get).toList(),
                        List.copyOf(selectedMeasures.values()));
        var columns = new ArrayList<RelationalIR.Column>();
        var requestedOutputs = new ArrayList<MetricContract.Output>();
        for (String dimension : request.dimensions()) {
            columns.add(
                    new RelationalIR.Column(
                            dimension, new RelationalIR.Field(bindings.get(dimension))));
            requestedOutputs.add(outputs.get(dimension));
        }
        for (String measure : request.measures()) {
            columns.add(
                    new RelationalIR.Column(
                            measure, new RelationalIR.Field(bindings.get(measure))));
            requestedOutputs.add(outputs.get(measure));
        }
        var plan = new RelationalIR.Project(grouped, columns);
        return new MetricContract(
                original.statisticalSubject(),
                original.identityField(),
                original.identityNullPolicy(),
                original.unit(),
                original.timeRole(),
                original.businessZone(),
                original.sources(),
                plan,
                requestedOutputs,
                request.dimensions(),
                original.emptyPolicy(),
                original.zeroDivisionPolicy(),
                Optional.empty());
    }

    private static void require(boolean condition, String reason, String message) {
        if (!condition) throw new UnsupportedDerivation(reason, message);
    }
}
