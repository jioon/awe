package ai.awe.kernel.contract;

import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** 有界数据计算语义；这里只表达关系代数，不包含 SQL、脚本或 Agent 工具控制流。 */
public final class RelationalIR {
    public static final int MAX_NODES = 64;
    public static final int MAX_DEPTH = 16;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private RelationalIR() {}

    public sealed interface Node permits Scan, Filter, Project, Aggregate, Join, SetOperation {}

    public sealed interface Expr permits Field, Literal, BusinessDate, Arithmetic {}

    public sealed interface Predicate permits Compare, All, Any, Not, IsNull, In {}

    public enum AggregateKind {
        COUNT_ROWS,
        COUNT,
        COUNT_DISTINCT,
        SUM,
        AVG,
        MIN,
        MAX
    }

    public enum ArithmeticOp {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE
    }

    public enum CompareOp {
        EQ,
        NE,
        LT,
        LE,
        GT,
        GE
    }

    public enum JoinKind {
        INNER,
        LEFT
    }

    public enum SetKind {
        UNION_DISTINCT,
        INTERSECT_DISTINCT,
        EXCEPT_DISTINCT
    }

    /** 偏移按业务时区的日历日作用于请求边界，不能用固定秒数替代 DST 日。 */
    public record Scan(String source, int fromDayOffset, int toDayOffset) implements Node {
        public Scan {
            identifier(source);
            if (Math.abs((long) fromDayOffset) > 3660 || Math.abs((long) toDayOffset) > 3660)
                throw new IllegalArgumentException(
                        "Scan window exceeds supported calendar offsets");
        }
    }

    public record Filter(Node input, Predicate predicate) implements Node {
        public Filter {
            Objects.requireNonNull(input);
            Objects.requireNonNull(predicate);
        }
    }

    public record Project(Node input, List<Column> columns) implements Node {
        public Project {
            Objects.requireNonNull(input);
            columns = List.copyOf(columns);
            bounded(columns, 64, "projection");
            unique(columns.stream().map(Column::name).toList(), "projection names");
        }
    }

    public record Column(String name, Expr expression) {
        public Column {
            identifier(name);
            Objects.requireNonNull(expression);
        }
    }

    public record Aggregate(Node input, List<String> groupBy, List<Measure> measures)
            implements Node {
        public Aggregate {
            Objects.requireNonNull(input);
            groupBy = List.copyOf(groupBy);
            measures = List.copyOf(measures);
            if (groupBy.size() > 32) throw new IllegalArgumentException("Too many group keys");
            groupBy.forEach(RelationalIR::fieldName);
            unique(groupBy, "group keys");
            bounded(measures, 32, "measures");
            var names = new ArrayList<>(groupBy);
            measures.forEach(m -> names.add(m.name()));
            unique(names, "aggregate output names");
        }
    }

    public record Measure(String name, AggregateKind kind, Expr expression) {
        public Measure {
            identifier(name);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(expression);
            if (kind == AggregateKind.COUNT_ROWS
                    && !(expression instanceof Literal l
                            && l.type() == ObjectType.DataType.INTEGER
                            && l.value().equals("1")))
                throw new IllegalArgumentException(
                        "COUNT_ROWS requires the explicit INTEGER literal 1");
        }
    }

    public record Join(Node left, Node right, JoinKind joinKind, EntityRef link, List<JoinKey> keys)
            implements Node {
        public Join {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
            Objects.requireNonNull(joinKind);
            Objects.requireNonNull(link);
            keys = List.copyOf(keys);
            bounded(keys, 16, "join keys");
            unique(keys.stream().map(JoinKey::leftField).toList(), "left join keys");
            unique(keys.stream().map(JoinKey::rightField).toList(), "right join keys");
        }
    }

    public record JoinKey(String leftField, String rightField) {
        public JoinKey {
            fieldName(leftField);
            fieldName(rightField);
        }
    }

    public record SetOperation(Node left, Node right, SetKind operation) implements Node {
        public SetOperation {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
            Objects.requireNonNull(operation);
        }
    }

    public record Field(String name) implements Expr {
        public Field {
            fieldName(name);
        }
    }

    public record Literal(ObjectType.DataType type, String value) implements Expr {
        public Literal {
            Objects.requireNonNull(type);
            Objects.requireNonNull(value);
            if (value.length() > 4096)
                throw new IllegalArgumentException("Literal exceeds cell budget");
            try {
                value =
                        switch (type) {
                            case STRING -> value;
                            case BOOLEAN -> {
                                if (!Set.of("true", "false").contains(value))
                                    throw new IllegalArgumentException();
                                yield value;
                            }
                            case INTEGER -> {
                                if (!value.matches("-?(0|[1-9][0-9]{0,255})"))
                                    throw new IllegalArgumentException();
                                yield new BigInteger(value).toString();
                            }
                            case DECIMAL -> {
                                BigDecimal number = new BigDecimal(value);
                                if (number.precision() > 256
                                        || Math.abs((long) number.scale()) > 256)
                                    throw new IllegalArgumentException();
                                yield number.stripTrailingZeros().toString();
                            }
                            case DATE -> {
                                LocalDate date = LocalDate.parse(value);
                                if (date.getYear() < 1 || date.getYear() > 9999)
                                    throw new IllegalArgumentException();
                                yield date.toString();
                            }
                            case TIMESTAMP -> {
                                Instant instant = Instant.parse(value);
                                if (instant.getNano() % 1000 != 0
                                        || instant.atOffset(java.time.ZoneOffset.UTC).getYear() < 1
                                        || instant.atOffset(java.time.ZoneOffset.UTC).getYear()
                                                > 9999) throw new IllegalArgumentException();
                                yield instant.toString();
                            }
                            default -> throw new IllegalArgumentException();
                        };
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid typed literal");
            }
        }
    }

    public record BusinessDate(Expr input) implements Expr {
        public BusinessDate {
            Objects.requireNonNull(input);
        }
    }

    public record Arithmetic(ArithmeticOp operation, Expr left, Expr right) implements Expr {
        public Arithmetic {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
        }
    }

    public record Compare(CompareOp operation, Expr left, Expr right) implements Predicate {
        public Compare {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
        }
    }

    public record All(List<Predicate> terms) implements Predicate {
        public All {
            terms = List.copyOf(terms);
            bounded(terms, 32, "AND terms");
        }
    }

    public record Any(List<Predicate> terms) implements Predicate {
        public Any {
            terms = List.copyOf(terms);
            bounded(terms, 32, "OR terms");
        }
    }

    public record Not(Predicate input) implements Predicate {
        public Not {
            Objects.requireNonNull(input);
        }
    }

    public record IsNull(Expr input, boolean negated) implements Predicate {
        public IsNull {
            Objects.requireNonNull(input);
        }
    }

    public record In(Expr input, List<Literal> values, boolean negated) implements Predicate {
        public In {
            Objects.requireNonNull(input);
            values = List.copyOf(values);
            bounded(values, 100, "IN values");
            if (values.stream().map(Literal::type).distinct().count() != 1)
                throw new IllegalArgumentException("IN values require one declared type");
        }
    }

    public static void validate(Node root) {
        walk(root, 0, new int[2]);
    }

    public static Set<String> sources(Node root) {
        validate(root);
        var result = new LinkedHashSet<String>();
        collect(root, result, null);
        return Set.copyOf(result);
    }

    public static Set<EntityRef> links(Node root) {
        validate(root);
        var result = new LinkedHashSet<EntityRef>();
        collect(root, null, result);
        return Set.copyOf(result);
    }

    private static void collect(Node n, Set<String> sources, Set<EntityRef> links) {
        switch (n) {
            case Scan s -> {
                if (sources != null) sources.add(s.source());
            }
            case Filter f -> collect(f.input(), sources, links);
            case Project p -> collect(p.input(), sources, links);
            case Aggregate a -> collect(a.input(), sources, links);
            case Join j -> {
                if (links != null) links.add(j.link());
                collect(j.left(), sources, links);
                collect(j.right(), sources, links);
            }
            case SetOperation s -> {
                collect(s.left(), sources, links);
                collect(s.right(), sources, links);
            }
        }
    }

    private static void walk(Object value, int depth, int[] budget) {
        if (depth > MAX_DEPTH || ++budget[1] > 512)
            throw new IllegalArgumentException("Semantic IR complexity budget exceeded");
        if (value instanceof Node && ++budget[0] > MAX_NODES)
            throw new IllegalArgumentException("Semantic IR node budget exceeded");
        if (value instanceof Collection<?> c) {
            for (Object child : c) walk(child, depth, budget);
            return;
        }
        if (value == null) throw new IllegalArgumentException("Null semantic IR node");
        if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    Object child = component.getAccessor().invoke(value);
                    if (child instanceof Node
                            || child instanceof Expr
                            || child instanceof Predicate
                            || child instanceof Collection<?>) walk(child, depth + 1, budget);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Invalid semantic IR type", ex);
                }
            }
        }
    }

    public static void identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches())
            throw new IllegalArgumentException("Unsupported semantic identifier");
    }

    public static void fieldName(String value) {
        if (value == null) throw new IllegalArgumentException("Field name required");
        String[] parts = value.split("\\.", -1);
        if (parts.length > 2)
            throw new IllegalArgumentException("Field must be a name or source.name");
        for (String part : parts) identifier(part);
    }

    private static void bounded(Collection<?> values, int max, String role) {
        if (values.isEmpty() || values.size() > max)
            throw new IllegalArgumentException("Unsupported number of " + role);
    }

    private static void unique(List<String> names, String role) {
        if (new HashSet<>(names).size() != names.size())
            throw new IllegalArgumentException("Duplicate " + role);
    }
}
