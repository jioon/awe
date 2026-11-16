package ai.awe.kernel.contract;

import ai.awe.kernel.model.ObjectType;
import java.util.*;

/** 发布阶段的确定性字段与代数检查；运行时仍需独立验证实际来源、Join 主键与数据状态。 */
public final class SemanticPlanTypes {
    public record ColumnType(ObjectType.DataType type, boolean nullable) {}

    private final Map<String, ObjectType> sources;
    private final MetricContract.ZeroDivisionPolicy zeroPolicy;
    private final RelationalIR.Aggregate zeroAggregate;

    public SemanticPlanTypes(
            Map<String, ObjectType> sources, MetricContract.ZeroDivisionPolicy zeroPolicy) {
        this(sources, zeroPolicy, null);
    }

    private SemanticPlanTypes(
            Map<String, ObjectType> sources,
            MetricContract.ZeroDivisionPolicy zeroPolicy,
            RelationalIR.Aggregate zeroAggregate) {
        this.sources = Map.copyOf(sources);
        this.zeroPolicy = Objects.requireNonNull(zeroPolicy);
        this.zeroAggregate = zeroAggregate;
    }

    public static void validate(MetricContract contract, Map<String, ObjectType> sources) {
        var checker =
                new SemanticPlanTypes(
                        sources,
                        contract.zeroDivisionPolicy(),
                        contract.emptyPolicy() == MetricContract.EmptyPolicy.ZERO
                                ? zeroAggregate(contract.plan())
                                : null);
        var result = checker.analyze(contract.plan());
        if (!result.keySet()
                .equals(
                        new LinkedHashSet<>(
                                contract.outputs().stream()
                                        .map(MetricContract.Output::name)
                                        .toList())))
            throw new IllegalArgumentException("Metric output names differ from the typed plan");
        for (var output : contract.outputs()) {
            var actual = result.get(output.name());
            if (actual.type() != output.type())
                throw new IllegalArgumentException(
                        "Metric output type differs from plan: " + output.name());
            if (actual.nullable() && !output.nullable())
                throw new IllegalArgumentException(
                        "Metric cannot erase a possibly NULL output: " + output.name());
        }
        String identity = contract.identityField();
        if (!identity.contains("."))
            identity = contract.sources().getFirst().alias() + "." + identity;
        var inputFields = new LinkedHashMap<String, ColumnType>();
        for (var source : sources.entrySet())
            source.getValue()
                    .properties()
                    .forEach(
                            p ->
                                    inputFields.put(
                                            source.getKey() + "." + p.name(),
                                            new ColumnType(p.type(), !p.required())));
        checker.field(inputFields, identity);
        if (contract.emptyPolicy() == MetricContract.EmptyPolicy.ZERO
                && (!contract.grain().isEmpty() || !zeroEligible(contract.plan())))
            throw new IllegalArgumentException(
                    "ZERO empty policy requires an ungrouped COUNT/SUM plan with explicit outputs");
    }

    public Map<String, ColumnType> analyze(RelationalIR.Node node) {
        RelationalIR.validate(node);
        return node(node);
    }

    private Map<String, ColumnType> node(RelationalIR.Node node) {
        return switch (node) {
            case RelationalIR.Scan scan -> {
                var schema = sources.get(scan.source());
                if (schema == null) throw new IllegalArgumentException("Unknown plan source alias");
                var output = new LinkedHashMap<String, ColumnType>();
                schema.properties()
                        .forEach(
                                p ->
                                        output.put(
                                                scan.source() + "." + p.name(),
                                                new ColumnType(p.type(), !p.required())));
                yield output;
            }
            case RelationalIR.Filter filter -> {
                var input = node(filter.input());
                predicate(filter.predicate(), input);
                yield input;
            }
            case RelationalIR.Project project -> {
                var input = node(project.input());
                var output = new LinkedHashMap<String, ColumnType>();
                project.columns()
                        .forEach(c -> output.put(c.name(), expression(c.expression(), input)));
                yield output;
            }
            case RelationalIR.Aggregate aggregate -> {
                var input = node(aggregate.input());
                var output = new LinkedHashMap<String, ColumnType>();
                aggregate.groupBy().forEach(name -> output.put(name, field(input, name)));
                for (var measure : aggregate.measures()) {
                    var value = expression(measure.expression(), input);
                    ColumnType type =
                            switch (measure.kind()) {
                                case COUNT_ROWS, COUNT, COUNT_DISTINCT ->
                                        new ColumnType(ObjectType.DataType.INTEGER, false);
                                case SUM, AVG -> {
                                    requireNumeric(value.type());
                                    boolean nullable =
                                            measure.kind() == RelationalIR.AggregateKind.AVG
                                                    || aggregate != zeroAggregate
                                                    || value.nullable();
                                    yield new ColumnType(
                                            measure.kind() == RelationalIR.AggregateKind.AVG
                                                    ? ObjectType.DataType.DECIMAL
                                                    : value.type(),
                                            nullable);
                                }
                                case MIN, MAX -> {
                                    if (!(numeric(value.type())
                                            || value.type() == ObjectType.DataType.DATE
                                            || value.type() == ObjectType.DataType.TIMESTAMP
                                            || value.type() == ObjectType.DataType.STRING))
                                        throw new IllegalArgumentException(
                                                "Unsupported MIN/MAX operand type");
                                    yield new ColumnType(value.type(), true);
                                }
                            };
                    output.put(measure.name(), type);
                }
                yield output;
            }
            case RelationalIR.Join join -> {
                var left = node(join.left());
                var right = node(join.right());
                for (var key : join.keys())
                    compatible(
                            field(left, key.leftField()).type(),
                            field(right, key.rightField()).type());
                var output = new LinkedHashMap<>(left);
                for (var e : right.entrySet()) {
                    if (output.containsKey(e.getKey()))
                        throw new IllegalArgumentException(
                                "Join source fields collide; use distinct source aliases");
                    output.put(
                            e.getKey(),
                            join.joinKind() == RelationalIR.JoinKind.LEFT
                                    ? new ColumnType(e.getValue().type(), true)
                                    : e.getValue());
                }
                yield output;
            }
            case RelationalIR.SetOperation operation -> {
                var left = node(operation.left());
                var right = node(operation.right());
                if (!left.keySet().equals(right.keySet()))
                    throw new IllegalArgumentException(
                            "Set inputs require identical named columns");
                var output = new LinkedHashMap<String, ColumnType>();
                for (var e : left.entrySet()) {
                    var other = right.get(e.getKey());
                    if (e.getValue().type() != other.type())
                        throw new IllegalArgumentException(
                                "Set inputs require identical column types");
                    output.put(
                            e.getKey(),
                            new ColumnType(
                                    other.type(), e.getValue().nullable() || other.nullable()));
                }
                yield output;
            }
        };
    }

    private ColumnType expression(RelationalIR.Expr expression, Map<String, ColumnType> input) {
        return switch (expression) {
            case RelationalIR.Field field -> field(input, field.name());
            case RelationalIR.Literal literal -> new ColumnType(literal.type(), false);
            case RelationalIR.BusinessDate date -> {
                var value = expression(date.input(), input);
                if (value.type() != ObjectType.DataType.TIMESTAMP)
                    throw new IllegalArgumentException(
                            "BUSINESS_DATE requires a TIMESTAMP operand");
                yield new ColumnType(ObjectType.DataType.DATE, value.nullable());
            }
            case RelationalIR.Arithmetic arithmetic -> {
                var left = expression(arithmetic.left(), input);
                var right = expression(arithmetic.right(), input);
                requireNumeric(left.type());
                requireNumeric(right.type());
                boolean divide = arithmetic.operation() == RelationalIR.ArithmeticOp.DIVIDE;
                boolean zeroPossible =
                        divide && zeroPolicy == MetricContract.ZeroDivisionPolicy.NULL;
                if (arithmetic.right() instanceof RelationalIR.Literal literal
                        && numeric(literal.type())
                        && new java.math.BigDecimal(literal.value()).signum() != 0)
                    zeroPossible = false;
                yield new ColumnType(
                        divide
                                        || left.type() == ObjectType.DataType.DECIMAL
                                        || right.type() == ObjectType.DataType.DECIMAL
                                ? ObjectType.DataType.DECIMAL
                                : ObjectType.DataType.INTEGER,
                        left.nullable() || right.nullable() || zeroPossible);
            }
        };
    }

    private void predicate(RelationalIR.Predicate predicate, Map<String, ColumnType> input) {
        switch (predicate) {
            case RelationalIR.Compare compare -> {
                var left = expression(compare.left(), input);
                var right = expression(compare.right(), input);
                compatible(left.type(), right.type());
                if (left.type() == ObjectType.DataType.BOOLEAN
                        && !Set.of(RelationalIR.CompareOp.EQ, RelationalIR.CompareOp.NE)
                                .contains(compare.operation()))
                    throw new IllegalArgumentException("Boolean comparison supports only equality");
            }
            case RelationalIR.All all -> all.terms().forEach(term -> predicate(term, input));
            case RelationalIR.Any any -> any.terms().forEach(term -> predicate(term, input));
            case RelationalIR.Not not -> predicate(not.input(), input);
            case RelationalIR.IsNull isNull -> expression(isNull.input(), input);
            case RelationalIR.In in -> {
                var type = expression(in.input(), input);
                in.values().forEach(value -> compatible(type.type(), value.type()));
            }
        }
    }

    private ColumnType field(Map<String, ColumnType> input, String name) {
        var found = input.get(name);
        if (found == null) throw new IllegalArgumentException("Unknown semantic field: " + name);
        return found;
    }

    private static boolean zeroEligible(RelationalIR.Node node) {
        if (node instanceof RelationalIR.Project p)
            return p.columns().stream().allMatch(c -> c.expression() instanceof RelationalIR.Field)
                    && zeroEligible(p.input());
        return node instanceof RelationalIR.Aggregate a
                && a.groupBy().isEmpty()
                && a.measures().stream()
                        .allMatch(
                                m ->
                                        Set.of(
                                                        RelationalIR.AggregateKind.COUNT_ROWS,
                                                        RelationalIR.AggregateKind.COUNT,
                                                        RelationalIR.AggregateKind.COUNT_DISTINCT,
                                                        RelationalIR.AggregateKind.SUM)
                                                .contains(m.kind()));
    }

    private static RelationalIR.Aggregate zeroAggregate(RelationalIR.Node node) {
        if (node instanceof RelationalIR.Project p
                && p.columns().stream().allMatch(c -> c.expression() instanceof RelationalIR.Field))
            return zeroAggregate(p.input());
        return node instanceof RelationalIR.Aggregate a && a.groupBy().isEmpty() ? a : null;
    }

    private static boolean numeric(ObjectType.DataType type) {
        return type == ObjectType.DataType.INTEGER || type == ObjectType.DataType.DECIMAL;
    }

    private static void requireNumeric(ObjectType.DataType type) {
        if (!numeric(type)) throw new IllegalArgumentException("Numeric operand required");
    }

    private static void compatible(ObjectType.DataType left, ObjectType.DataType right) {
        if (left != right && !(numeric(left) && numeric(right)))
            throw new IllegalArgumentException("Incompatible semantic operand types");
    }
}
