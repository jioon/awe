package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.*;
import ai.awe.registry.consumption.ConsumerAccess;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 从正式计划追踪实际读取字段和敏感值传播；改别名或套算术不能把明细伪装成安全聚合。 */
public final class MetricAccessAnalysis {
    private record Origin(String source, String field) {}

    private record Value(Set<Origin> origins, Set<Origin> exposedSensitive) {
        private static Value constant() {
            return new Value(Set.of(), Set.of());
        }
    }

    private final Map<String, BoundQuerySource> sources;
    private final Set<Origin> needed = new HashSet<>();

    private MetricAccessAnalysis(Map<String, BoundQuerySource> sources) {
        this.sources = Map.copyOf(sources);
    }

    public static void authorize(
            MetricContract contract,
            Map<String, BoundQuerySource> sources,
            ConsumerAccess access,
            ConsumerAccess.Grant grant) {
        var analysis = new MetricAccessAnalysis(sources);
        var output = analysis.node(contract.plan());
        if (output.values().stream().anyMatch(v -> !v.exposedSensitive().isEmpty()))
            throw denied(
                    "Metric cannot expose sensitive source values through projection, grouping or extrema");
        String identity = contract.identityField();
        if (!identity.contains("."))
            identity = contract.sources().getFirst().alias() + "." + identity;
        analysis.needed.add(analysis.origin(identity));
        for (var source : sources.values())
            if (!source.staticSnapshot()) {
                String time =
                        source.binding()
                                .path("timeFields")
                                .path(source.rangeRole().name())
                                .asText();
                if (!time.isBlank()) analysis.needed.add(new Origin(source.alias(), time));
            }
        for (var origin : analysis.needed) {
            var source = sources.get(origin.source());
            if (source == null
                    || !access.inputFields(grant, source.dataset().ref().id())
                            .contains(origin.field()))
                throw denied("Metric input field is outside the configured data scope");
        }
    }

    private Map<String, Value> node(RelationalIR.Node node) {
        return switch (node) {
            case RelationalIR.Scan scan -> {
                var source = sources.get(scan.source());
                if (source == null) throw new IllegalArgumentException("Unknown source alias");
                var values = new LinkedHashMap<String, Value>();
                for (var p : source.rowSchema().properties()) {
                    var origin = new Origin(scan.source(), p.name());
                    values.put(
                            scan.source() + "." + p.name(),
                            new Value(Set.of(origin), p.sensitive() ? Set.of(origin) : Set.of()));
                }
                yield values;
            }
            case RelationalIR.Filter filter -> {
                var input = node(filter.input());
                predicate(filter.predicate(), input);
                yield input;
            }
            case RelationalIR.Project project -> {
                var input = node(project.input());
                var result = new LinkedHashMap<String, Value>();
                project.columns()
                        .forEach(c -> result.put(c.name(), expression(c.expression(), input)));
                yield result;
            }
            case RelationalIR.Aggregate aggregate -> {
                var input = node(aggregate.input());
                var result = new LinkedHashMap<String, Value>();
                aggregate.groupBy().forEach(key -> result.put(key, field(key, input)));
                for (var measure : aggregate.measures()) {
                    Value value = expression(measure.expression(), input);
                    boolean summarized =
                            switch (measure.kind()) {
                                case COUNT_ROWS, COUNT, COUNT_DISTINCT, SUM, AVG -> true;
                                default -> false;
                            };
                    result.put(
                            measure.name(),
                            new Value(
                                    value.origins(),
                                    summarized ? Set.of() : value.exposedSensitive()));
                }
                yield result;
            }
            case RelationalIR.Join join -> {
                var left = node(join.left());
                var right = node(join.right());
                for (var key : join.keys()) {
                    field(key.leftField(), left);
                    field(key.rightField(), right);
                }
                var result = new LinkedHashMap<>(left);
                for (var e : right.entrySet())
                    if (result.put(e.getKey(), e.getValue()) != null)
                        throw new IllegalArgumentException("Ambiguous join output");
                yield result;
            }
            case RelationalIR.SetOperation set -> {
                var left = node(set.left());
                var right = node(set.right());
                if (!left.keySet().equals(right.keySet()))
                    throw new IllegalArgumentException("Set output schemas differ");
                var result = new LinkedHashMap<String, Value>();
                left.forEach((key, value) -> result.put(key, union(value, right.get(key))));
                yield result;
            }
        };
    }

    private Value expression(RelationalIR.Expr expression, Map<String, Value> input) {
        return switch (expression) {
            case RelationalIR.Field field -> field(field.name(), input);
            case RelationalIR.Literal ignored -> Value.constant();
            case RelationalIR.BusinessDate date -> expression(date.input(), input);
            case RelationalIR.Arithmetic arithmetic ->
                    union(
                            expression(arithmetic.left(), input),
                            expression(arithmetic.right(), input));
        };
    }

    private void predicate(RelationalIR.Predicate predicate, Map<String, Value> input) {
        switch (predicate) {
            case RelationalIR.Compare compare -> {
                expression(compare.left(), input);
                expression(compare.right(), input);
            }
            case RelationalIR.All all -> all.terms().forEach(p -> predicate(p, input));
            case RelationalIR.Any any -> any.terms().forEach(p -> predicate(p, input));
            case RelationalIR.Not not -> predicate(not.input(), input);
            case RelationalIR.IsNull nullCheck -> expression(nullCheck.input(), input);
            case RelationalIR.In in -> expression(in.input(), input);
        }
    }

    private Value field(String name, Map<String, Value> fields) {
        Value value = fields.get(name);
        if (value == null && !name.contains(".")) {
            var matches =
                    fields.entrySet().stream()
                            .filter(e -> e.getKey().endsWith("." + name))
                            .toList();
            if (matches.size() == 1) value = matches.getFirst().getValue();
        }
        if (value == null)
            throw new IllegalArgumentException("Unknown or ambiguous semantic field");
        needed.addAll(value.origins());
        return value;
    }

    private Origin origin(String name) {
        int dot = name.indexOf('.');
        if (dot < 1 || !sources.containsKey(name.substring(0, dot)))
            throw new IllegalArgumentException("Identity must identify an actual source field");
        String alias = name.substring(0, dot), field = name.substring(dot + 1);
        if (sources.get(alias).rowSchema().properties().stream()
                .noneMatch(p -> p.name().equals(field)))
            throw new IllegalArgumentException(
                    "Statistical identity is outside the declared row schema");
        return new Origin(alias, field);
    }

    private static Value union(Value a, Value b) {
        var origins = new HashSet<>(a.origins());
        origins.addAll(b.origins());
        var sensitive = new HashSet<>(a.exposedSensitive());
        sensitive.addAll(b.exposedSensitive());
        return new Value(Set.copyOf(origins), Set.copyOf(sensitive));
    }

    private static ResponseStatusException denied(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
