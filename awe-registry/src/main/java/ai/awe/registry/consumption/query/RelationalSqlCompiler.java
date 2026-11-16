package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.LinkType;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** 只将已发布的有限关系代数编译到 PostgreSQL；没有 SQL 字符串或动态工具节点。 */
public final class RelationalSqlCompiler {
    public record ScanWindow(String source, Instant from, Instant to) {}

    public record Column(String name, ObjectType.DataType type, boolean nullable) {}

    public record ScanCheck(
            String source, String countSql, List<Object> parameters, String nullIdentitySql) {}

    public record Compiled(
            String sql,
            List<Object> parameters,
            List<Column> columns,
            Map<String, Set<String>> sourceFields,
            List<ScanWindow> windows) {
        public Compiled {
            parameters = List.copyOf(parameters);
            columns = List.copyOf(columns);
            windows = List.copyOf(windows);
            var copy = new TreeMap<String, Set<String>>();
            sourceFields.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            sourceFields = Map.copyOf(copy);
        }
    }

    private record Info(
            ObjectType.DataType type, boolean nullable, String originAlias, String originField) {}

    private record Fragment(
            String sql,
            LinkedHashMap<String, Info> fields,
            List<Object> parameters,
            List<Set<String>> uniqueKeys,
            Set<String> sources) {}

    private record Expr(String sql, Info info, List<Object> parameters) {}

    private final MetricContract metric;
    private final Map<String, BoundQuerySource> sources;
    private final Map<String, PostgresWarehouse.Catalog> catalogs;
    private final Map<EntityRef, LinkType> links;
    private final Map<String, RelationalIR.Predicate> scopes;
    private final Instant from, to;
    private final RelationalIR.Aggregate zeroAggregate;
    private final Map<String, Set<String>> used = new TreeMap<>();
    private final List<ScanCheck> checks = new ArrayList<>();
    private int sequence;

    public RelationalSqlCompiler(
            MetricContract metric,
            Map<String, BoundQuerySource> sources,
            Map<String, PostgresWarehouse.Catalog> catalogs,
            Map<EntityRef, LinkType> links,
            Map<String, RelationalIR.Predicate> scopes,
            Instant from,
            Instant to) {
        this.metric = metric;
        this.sources = Map.copyOf(sources);
        this.catalogs = Map.copyOf(catalogs);
        this.links = Map.copyOf(links);
        this.scopes = Map.copyOf(scopes);
        this.from = from;
        this.to = to;
        this.zeroAggregate =
                metric.emptyPolicy() == MetricContract.EmptyPolicy.ZERO
                        ? zeroAggregate(metric.plan())
                        : null;
    }

    public Compiled compile() {
        RelationalIR.validate(metric.plan());
        if (!sources.keySet().equals(RelationalIR.sources(metric.plan()))
                || !sources.keySet().containsAll(scopes.keySet()))
            throw blocked("Plan and server-resolved source scope differ");
        Fragment root = node(metric.plan());
        var selected = new ArrayList<String>();
        var columns = new ArrayList<Column>();
        String alias = next();
        if (!root.fields()
                .keySet()
                .equals(
                        new LinkedHashSet<>(
                                metric.outputs().stream()
                                        .map(MetricContract.Output::name)
                                        .toList())))
            throw blocked("Plan output names must exactly match the published output contract");
        for (var output : metric.outputs()) {
            Info info = field(root, output.name());
            if (!(info.type() == output.type()
                    || info.type() == ObjectType.DataType.INTEGER
                            && output.type() == ObjectType.DataType.DECIMAL))
                throw blocked("Plan output type differs from the published metric contract");
            // 有些 nullable 表达式可由正式过滤条件排除空值，最终仍逐单元执行非空义务。
            selected.add(reference(root, alias, output.name()) + " AS " + q(output.name()));
            columns.add(new Column(output.name(), output.type(), output.nullable()));
        }
        String sql =
                "SELECT " + String.join(",", selected) + " FROM (" + root.sql() + ") " + q(alias);
        return new Compiled(sql, root.parameters(), columns, used, windows(metric, from, to));
    }

    public List<ScanCheck> scanChecks() {
        return List.copyOf(checks);
    }

    public static List<ScanWindow> windows(MetricContract metric, Instant from, Instant to) {
        var result = new ArrayList<ScanWindow>();
        collectWindows(metric.plan(), metric.businessZone(), from, to, result);
        return List.copyOf(result);
    }

    private static void collectWindows(
            RelationalIR.Node node, ZoneId zone, Instant from, Instant to, List<ScanWindow> out) {
        switch (node) {
            case RelationalIR.Scan s -> {
                Instant a = from.atZone(zone).plusDays(s.fromDayOffset()).toInstant();
                Instant b = to.atZone(zone).plusDays(s.toDayOffset()).toInstant();
                if (!a.isBefore(b) || a.getNano() % 1000 != 0 || b.getNano() % 1000 != 0)
                    throw blocked(
                            "Expanded scan window must be positive and aligned to PostgreSQL microseconds");
                out.add(new ScanWindow(s.source(), a, b));
            }
            case RelationalIR.Filter f -> collectWindows(f.input(), zone, from, to, out);
            case RelationalIR.Project p -> collectWindows(p.input(), zone, from, to, out);
            case RelationalIR.Aggregate a -> collectWindows(a.input(), zone, from, to, out);
            case RelationalIR.Join j -> {
                collectWindows(j.left(), zone, from, to, out);
                collectWindows(j.right(), zone, from, to, out);
            }
            case RelationalIR.SetOperation s -> {
                collectWindows(s.left(), zone, from, to, out);
                collectWindows(s.right(), zone, from, to, out);
            }
        }
    }

    private Fragment node(RelationalIR.Node node) {
        return switch (node) {
            case RelationalIR.Scan scan -> scan(scan);
            case RelationalIR.Filter filter -> filter(filter);
            case RelationalIR.Project project -> project(project);
            case RelationalIR.Aggregate aggregate -> aggregate(aggregate);
            case RelationalIR.Join join -> join(join);
            case RelationalIR.SetOperation set -> set(set);
        };
    }

    private Fragment scan(RelationalIR.Scan scan) {
        BoundQuerySource source = sources.get(scan.source());
        var catalog = catalogs.get(scan.source());
        if (source == null || catalog == null)
            throw blocked("Scan source was not resolved and inspected by the server");
        String event =
                source.staticSnapshot()
                        ? ""
                        : source.binding()
                                .path("timeFields")
                                .path(source.rangeRole().name())
                                .asText();
        if (!source.staticSnapshot()) PostgresWarehouse.quoteIdentifier(event);
        var fields = new LinkedHashMap<String, Info>();
        var selected = new ArrayList<String>();
        for (var property : source.rowSchema().properties()) {
            String logical = scan.source() + "." + property.name();
            selected.add(q(property.name()) + " AS " + c(fields.size()));
            fields.put(
                    logical,
                    new Info(
                            property.type(), !property.required(), scan.source(), property.name()));
        }
        if (!source.staticSnapshot() && !fields.containsKey(scan.source() + "." + event))
            throw blocked("Scan time mapping is outside its explicit row schema");
        if (!source.staticSnapshot()) mark(scan.source(), event);
        Instant a = from.atZone(metric.businessZone()).plusDays(scan.fromDayOffset()).toInstant();
        Instant b = to.atZone(metric.businessZone()).plusDays(scan.toDayOffset()).toInstant();
        if (!a.isBefore(b) || a.getNano() % 1000 != 0 || b.getNano() % 1000 != 0)
            throw blocked("Unsupported scan time boundary");
        String base =
                "SELECT "
                        + String.join(",", selected)
                        + " FROM "
                        + PostgresWarehouse.quoteRelation(catalog.relation());
        var parameters = new ArrayList<Object>();
        if (!source.staticSnapshot()) {
            base += " WHERE " + q(event) + ">=? AND " + q(event) + "<?";
            var physical =
                    catalog.columns().stream()
                            .filter(c -> c.name().equals(event))
                            .findFirst()
                            .orElseThrow(() -> blocked("Source time column is absent"));
            if (physical.nativeType().equals("date")
                    && source.rangeRole() == TemporalContract.TimeRole.BUSINESS_DATE) {
                if (!a.atZone(metric.businessZone()).toLocalTime().equals(LocalTime.MIDNIGHT)
                        || !b.atZone(metric.businessZone())
                                .toLocalTime()
                                .equals(LocalTime.MIDNIGHT))
                    throw blocked(
                            "BUSINESS_DATE scans require exact business-zone midnight boundaries");
                parameters.add(a.atZone(metric.businessZone()).toLocalDate());
                parameters.add(b.atZone(metric.businessZone()).toLocalDate());
            } else if (physical.nativeType().equals("timestamptz")) {
                parameters.add(a.atOffset(ZoneOffset.UTC));
                parameters.add(b.atOffset(ZoneOffset.UTC));
            } else
                throw blocked(
                        "Window scans require explicit DATE BUSINESS_DATE or timezone-aware TIMESTAMP fields");
        } else if (scan.fromDayOffset() != 0 || scan.toDayOffset() != 0)
            throw blocked("Static snapshots cannot claim shifted historical windows");
        var keys =
                source.dataset().grain().keys().stream()
                        .map(k -> scan.source() + "." + k)
                        .collect(java.util.stream.Collectors.toSet());
        Fragment fragment =
                new Fragment(base, fields, parameters, List.of(keys), Set.of(scan.source()));
        RelationalIR.Predicate scope = scopes.get(scan.source());
        if (scope != null) fragment = applyPredicate(fragment, scope);
        String identity = identityName(scan.source());
        String nullIdentitySql = "";
        if (identity != null) {
            if (!fields.containsKey(identity))
                throw blocked("Statistical identity is absent from its declared source schema");
            mark(scan.source(), identity.substring(identity.indexOf('.') + 1));
            String a1 = next();
            nullIdentitySql =
                    "SELECT 1 FROM ("
                            + fragment.sql()
                            + ") "
                            + q(a1)
                            + " WHERE "
                            + reference(fragment, a1, identity)
                            + " IS NULL LIMIT 1";
        }
        String countAlias = next();
        checks.add(
                new ScanCheck(
                        scan.source(),
                        "SELECT COUNT(*) FROM (SELECT 1 FROM ("
                                + fragment.sql()
                                + ") "
                                + q(countAlias)
                                + " LIMIT ?::integer) "
                                + q(next()),
                        List.copyOf(fragment.parameters()),
                        nullIdentitySql));
        if (identity != null) {
            if (metric.identityNullPolicy() == MetricContract.IdentityNullPolicy.IGNORE)
                fragment =
                        applyPredicate(
                                fragment,
                                new RelationalIR.IsNull(new RelationalIR.Field(identity), true));
        }
        return fragment;
    }

    public String identityName(String alias) {
        String identity = metric.identityField();
        if (identity.contains(".")) return identity.startsWith(alias + ".") ? identity : null;
        // 未限定名只允许主统计来源，不能让同名维表字段自动承担统计身份。
        return metric.sources().getFirst().alias().equals(alias) ? alias + "." + identity : null;
    }

    private Fragment filter(RelationalIR.Filter filter) {
        return applyPredicate(node(filter.input()), filter.predicate());
    }

    private Fragment applyPredicate(Fragment input, RelationalIR.Predicate predicate) {
        String alias = next();
        Expr test = predicate(predicate, input, alias);
        var parameters = new ArrayList<>(input.parameters());
        parameters.addAll(test.parameters());
        return new Fragment(
                "SELECT "
                        + all(input, alias)
                        + " FROM ("
                        + input.sql()
                        + ") "
                        + q(alias)
                        + " WHERE "
                        + test.sql(),
                input.fields(),
                parameters,
                input.uniqueKeys(),
                input.sources());
    }

    private Fragment project(RelationalIR.Project project) {
        Fragment input = node(project.input());
        String alias = next();
        var fields = new LinkedHashMap<String, Info>();
        var selected = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        var renames = new HashMap<String, String>();
        for (var column : project.columns()) {
            Expr value = expression(column.expression(), input, alias);
            selected.add(value.sql() + " AS " + c(fields.size()));
            fields.put(column.name(), value.info());
            parameters.addAll(value.parameters());
            if (column.expression() instanceof RelationalIR.Field field)
                renames.put(field.name(), column.name());
        }
        parameters.addAll(input.parameters());
        var keys = new ArrayList<Set<String>>();
        for (var key : input.uniqueKeys())
            if (renames.keySet().containsAll(key))
                keys.add(
                        key.stream()
                                .map(renames::get)
                                .collect(java.util.stream.Collectors.toSet()));
        return new Fragment(
                "SELECT " + String.join(",", selected) + " FROM (" + input.sql() + ") " + q(alias),
                fields,
                parameters,
                keys,
                input.sources());
    }

    private Fragment aggregate(RelationalIR.Aggregate aggregate) {
        Fragment input = node(aggregate.input());
        String alias = next();
        var fields = new LinkedHashMap<String, Info>();
        var selected = new ArrayList<String>();
        var groups = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        for (String group : aggregate.groupBy()) {
            Info info = field(input, group);
            recordOrigin(info);
            String value = reference(input, alias, group);
            groups.add(value);
            selected.add(value + " AS " + c(fields.size()));
            fields.put(group, info);
        }
        for (var measure : aggregate.measures()) {
            Expr value = expression(measure.expression(), input, alias);
            String sql;
            ObjectType.DataType type;
            boolean nullable;
            switch (measure.kind()) {
                case COUNT_ROWS -> {
                    sql = "COUNT(*)";
                    type = ObjectType.DataType.INTEGER;
                    nullable = false;
                }
                case COUNT -> {
                    sql = "COUNT(" + value.sql() + ")";
                    type = ObjectType.DataType.INTEGER;
                    nullable = false;
                    parameters.addAll(value.parameters());
                }
                case COUNT_DISTINCT -> {
                    sql = "COUNT(DISTINCT " + value.sql() + ")";
                    type = ObjectType.DataType.INTEGER;
                    nullable = false;
                    parameters.addAll(value.parameters());
                }
                case SUM, AVG -> {
                    numeric(value.info().type());
                    sql = measure.kind().name() + "((" + value.sql() + ")::numeric)";
                    type =
                            measure.kind() == RelationalIR.AggregateKind.SUM
                                    ? value.info().type()
                                    : ObjectType.DataType.DECIMAL;
                    nullable = true;
                    parameters.addAll(value.parameters());
                    // 只把没有输入行的 SUM 变成契约中的 0；全为 NULL 的非空输入仍然是 NULL。
                    if (measure.kind() == RelationalIR.AggregateKind.SUM
                            && aggregate == zeroAggregate) {
                        sql = "CASE WHEN COUNT(*)=0 THEN 0 ELSE " + sql + " END";
                        nullable = value.info().nullable();
                    }
                }
                case MIN, MAX -> {
                    sql = measure.kind().name() + "(" + value.sql() + ")";
                    type = value.info().type();
                    nullable = true;
                    parameters.addAll(value.parameters());
                }
                default -> throw blocked("Unregistered aggregate operation");
            }
            selected.add(sql + " AS " + c(fields.size()));
            fields.put(measure.name(), new Info(type, nullable, null, null));
        }
        parameters.addAll(input.parameters());
        String sql =
                "SELECT "
                        + String.join(",", selected)
                        + " FROM ("
                        + input.sql()
                        + ") "
                        + q(alias)
                        + (groups.isEmpty() ? "" : " GROUP BY " + String.join(",", groups));
        return new Fragment(
                sql, fields, parameters, List.of(Set.copyOf(aggregate.groupBy())), input.sources());
    }

    private Fragment join(RelationalIR.Join join) {
        Fragment left = node(join.left()), right = node(join.right());
        LinkType link = links.get(join.link());
        if (link == null
                || !link.ref().equals(join.link())
                || !link.potentiallyFanoutSafe()
                || !"columns.equal.v1".equals(link.identityMappingRef())
                || !(link.temporalPolicy() == RelationContract.TemporalPolicy.SAME_SNAPSHOT
                        || link.temporalPolicy()
                                == RelationContract.TemporalPolicy.TIME_INDEPENDENT))
            throw blocked("Join requires an approved, snapshot-compatible fanout-safe LinkType");
        var leftKeys = new HashSet<String>();
        var rightKeys = new HashSet<String>();
        String la = next(), ra = next();
        var conditions = new ArrayList<String>();
        String leftSource = null, rightSource = null;
        for (var key : join.keys()) {
            Info a = field(left, key.leftField()), b = field(right, key.rightField());
            if (!sameType(a.type(), b.type()) || a.originAlias() == null || b.originAlias() == null)
                throw blocked("Join keys require explicit type-compatible source fields");
            if (leftSource != null && !leftSource.equals(a.originAlias())
                    || rightSource != null && !rightSource.equals(b.originAlias()))
                throw blocked("A join LinkType must bind one explicit source pair");
            leftSource = a.originAlias();
            rightSource = b.originAlias();
            recordOrigin(a);
            recordOrigin(b);
            leftKeys.add(key.leftField());
            rightKeys.add(key.rightField());
            conditions.add(
                    reference(left, la, key.leftField())
                            + "="
                            + reference(right, ra, key.rightField()));
        }
        if (leftSource == null
                || rightSource == null
                || !link.fromType().equals(sources.get(leftSource).dataset().ref())
                || !link.toType().equals(sources.get(rightSource).dataset().ref()))
            throw blocked("Join LinkType endpoints must pin the actual Dataset pair");
        if (right.uniqueKeys().stream().noneMatch(rightKeys::containsAll)
                || link.cardinality() == RelationContract.Cardinality.ONE_TO_ONE
                        && left.uniqueKeys().stream().noneMatch(leftKeys::containsAll))
            throw blocked(
                    "Join cardinality is not proved by complete actual source primary-key projections");
        var fields = new LinkedHashMap<String, Info>();
        var selected = new ArrayList<String>();
        for (var entry : left.fields().entrySet()) {
            selected.add(reference(left, la, entry.getKey()) + " AS " + c(fields.size()));
            fields.put(entry.getKey(), entry.getValue());
        }
        for (var entry : right.fields().entrySet()) {
            if (fields.containsKey(entry.getKey()))
                throw blocked("Join field names overlap; declare distinct source aliases");
            Info info = entry.getValue();
            selected.add(reference(right, ra, entry.getKey()) + " AS " + c(fields.size()));
            fields.put(
                    entry.getKey(),
                    join.joinKind() == RelationalIR.JoinKind.LEFT
                            ? new Info(info.type(), true, info.originAlias(), info.originField())
                            : info);
        }
        var parameters = new ArrayList<>(left.parameters());
        parameters.addAll(right.parameters());
        var aliases = new HashSet<>(left.sources());
        aliases.addAll(right.sources());
        return new Fragment(
                "SELECT "
                        + String.join(",", selected)
                        + " FROM ("
                        + left.sql()
                        + ") "
                        + q(la)
                        + (join.joinKind() == RelationalIR.JoinKind.LEFT
                                ? " LEFT JOIN ("
                                : " INNER JOIN (")
                        + right.sql()
                        + ") "
                        + q(ra)
                        + " ON "
                        + String.join(" AND ", conditions),
                fields,
                parameters,
                left.uniqueKeys(),
                aliases);
    }

    private Fragment set(RelationalIR.SetOperation set) {
        Fragment left = node(set.left()), right = node(set.right());
        if (!new ArrayList<>(left.fields().keySet())
                .equals(new ArrayList<>(right.fields().keySet())))
            throw blocked("Set operands require exactly aligned named columns");
        var fields = new LinkedHashMap<String, Info>();
        for (var entry : left.fields().entrySet()) {
            Info other = right.fields().get(entry.getKey());
            if (entry.getValue().type() != other.type())
                throw blocked("Set operand field types differ");
            fields.put(
                    entry.getKey(),
                    new Info(
                            entry.getValue().type(),
                            entry.getValue().nullable() || other.nullable(),
                            null,
                            null));
        }
        var parameters = new ArrayList<>(left.parameters());
        parameters.addAll(right.parameters());
        String operator =
                switch (set.operation()) {
                    case UNION_DISTINCT -> "UNION";
                    case INTERSECT_DISTINCT -> "INTERSECT";
                    case EXCEPT_DISTINCT -> "EXCEPT";
                };
        var aliases = new HashSet<>(left.sources());
        aliases.addAll(right.sources());
        return new Fragment(
                "(" + left.sql() + ") " + operator + " (" + right.sql() + ")",
                fields,
                parameters,
                List.of(Set.copyOf(fields.keySet())),
                aliases);
    }

    private Expr expression(RelationalIR.Expr expression, Fragment input, String alias) {
        return switch (expression) {
            case RelationalIR.Field f -> {
                Info info = field(input, f.name());
                recordOrigin(info);
                yield new Expr(reference(input, alias, f.name()), info, List.of());
            }
            case RelationalIR.Literal literal ->
                    new Expr(
                            "?::" + pgType(literal.type()),
                            new Info(literal.type(), false, null, null),
                            List.of(literalValue(literal)));
            case RelationalIR.BusinessDate date -> {
                Expr value = expression(date.input(), input, alias);
                if (value.info().type() != ObjectType.DataType.TIMESTAMP)
                    throw blocked("BusinessDate requires timezone-aware TIMESTAMP");
                var parameters = new ArrayList<>(value.parameters());
                parameters.add(metric.businessZone().getId());
                yield new Expr(
                        "((" + value.sql() + " AT TIME ZONE ?)::date)",
                        new Info(ObjectType.DataType.DATE, value.info().nullable(), null, null),
                        parameters);
            }
            case RelationalIR.Arithmetic arithmetic -> {
                Expr left = expression(arithmetic.left(), input, alias),
                        right = expression(arithmetic.right(), input, alias);
                numeric(left.info().type());
                numeric(right.info().type());
                String symbol =
                        switch (arithmetic.operation()) {
                            case ADD -> "+";
                            case SUBTRACT -> "-";
                            case MULTIPLY -> "*";
                            case DIVIDE -> "/";
                        };
                String divisor = "(" + right.sql() + ")::numeric";
                if (arithmetic.operation() == RelationalIR.ArithmeticOp.DIVIDE
                        && metric.zeroDivisionPolicy() == MetricContract.ZeroDivisionPolicy.NULL)
                    divisor = "NULLIF(" + divisor + ",0)";
                var parameters = new ArrayList<>(left.parameters());
                parameters.addAll(right.parameters());
                ObjectType.DataType resultType =
                        arithmetic.operation() == RelationalIR.ArithmeticOp.DIVIDE
                                        || left.info().type() == ObjectType.DataType.DECIMAL
                                        || right.info().type() == ObjectType.DataType.DECIMAL
                                ? ObjectType.DataType.DECIMAL
                                : ObjectType.DataType.INTEGER;
                yield new Expr(
                        "((" + left.sql() + ")::numeric" + symbol + divisor + ")",
                        new Info(
                                resultType,
                                left.info().nullable()
                                        || right.info().nullable()
                                        || arithmetic.operation()
                                                == RelationalIR.ArithmeticOp.DIVIDE,
                                null,
                                null),
                        parameters);
            }
        };
    }

    private Expr predicate(RelationalIR.Predicate predicate, Fragment input, String alias) {
        return switch (predicate) {
            case RelationalIR.Compare compare -> {
                Expr a = expression(compare.left(), input, alias),
                        b = expression(compare.right(), input, alias);
                if (!sameType(a.info().type(), b.info().type()))
                    throw blocked("Predicate operand types differ");
                String op =
                        switch (compare.operation()) {
                            case EQ -> "=";
                            case NE -> "<>";
                            case LT -> "<";
                            case LE -> "<=";
                            case GT -> ">";
                            case GE -> ">=";
                        };
                var parameters = new ArrayList<>(a.parameters());
                parameters.addAll(b.parameters());
                yield bool("(" + a.sql() + op + b.sql() + ")", parameters);
            }
            case RelationalIR.All all -> combine(all.terms(), " AND ", input, alias);
            case RelationalIR.Any any -> combine(any.terms(), " OR ", input, alias);
            case RelationalIR.Not not -> {
                Expr value = predicate(not.input(), input, alias);
                yield bool("NOT(" + value.sql() + ")", value.parameters());
            }
            case RelationalIR.IsNull missing -> {
                Expr value = expression(missing.input(), input, alias);
                yield bool(
                        "(" + value.sql() + (missing.negated() ? " IS NOT NULL)" : " IS NULL)"),
                        value.parameters());
            }
            case RelationalIR.In in -> {
                Expr value = expression(in.input(), input, alias);
                var parameters = new ArrayList<>(value.parameters());
                var values = new ArrayList<String>();
                for (var literal : in.values()) {
                    Expr item = expression(literal, input, alias);
                    if (!sameType(value.info().type(), item.info().type()))
                        throw blocked("IN values differ from the declared field type");
                    values.add(item.sql());
                    parameters.addAll(item.parameters());
                }
                yield bool(
                        "("
                                + value.sql()
                                + (in.negated() ? " NOT IN (" : " IN (")
                                + String.join(",", values)
                                + "))",
                        parameters);
            }
        };
    }

    private Expr combine(
            List<RelationalIR.Predicate> terms, String separator, Fragment input, String alias) {
        var sql = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        for (var term : terms) {
            Expr value = predicate(term, input, alias);
            sql.add(value.sql());
            parameters.addAll(value.parameters());
        }
        return bool("(" + String.join(separator, sql) + ")", parameters);
    }

    private Expr bool(String sql, List<Object> parameters) {
        return new Expr(sql, new Info(ObjectType.DataType.BOOLEAN, true, null, null), parameters);
    }

    private String next() {
        return "q" + sequence++;
    }

    private static String q(String name) {
        return PostgresWarehouse.quoteIdentifier(name);
    }

    private static String c(int index) {
        return q("c" + index);
    }

    private Info field(Fragment fragment, String name) {
        Info info = fragment.fields().get(name);
        if (info == null)
            throw blocked("Plan field is not defined in its exact input scope: " + name);
        return info;
    }

    private String reference(Fragment fragment, String alias, String name) {
        int i = 0;
        for (String field : fragment.fields().keySet()) {
            if (field.equals(name)) return q(alias) + "." + c(i);
            i++;
        }
        throw blocked("Plan field is absent from input scope");
    }

    private String all(Fragment fragment, String alias) {
        var names = new ArrayList<String>();
        for (int i = 0; i < fragment.fields().size(); i++)
            names.add(q(alias) + "." + c(i) + " AS " + c(i));
        return String.join(",", names);
    }

    private void recordOrigin(Info info) {
        if (info.originAlias() != null) mark(info.originAlias(), info.originField());
    }

    private void mark(String alias, String field) {
        used.computeIfAbsent(alias, key -> new TreeSet<>()).add(field);
    }

    private static boolean sameType(ObjectType.DataType a, ObjectType.DataType b) {
        return a == b || number(a) && number(b);
    }

    private static boolean number(ObjectType.DataType type) {
        return type == ObjectType.DataType.INTEGER || type == ObjectType.DataType.DECIMAL;
    }

    private static void numeric(ObjectType.DataType type) {
        if (!number(type)) throw blocked("Arithmetic/aggregate requires an explicit numeric field");
    }

    private static String pgType(ObjectType.DataType type) {
        return switch (type) {
            case STRING -> "text";
            case BOOLEAN -> "boolean";
            case INTEGER, DECIMAL -> "numeric";
            case DATE -> "date";
            case TIMESTAMP -> "timestamptz";
            default -> throw blocked("Unsupported literal encoding");
        };
    }

    static Object literalValue(RelationalIR.Literal literal) {
        return switch (literal.type()) {
            case STRING -> {
                if (!java.nio.charset.StandardCharsets.UTF_8
                        .newEncoder()
                        .canEncode(literal.value()))
                    throw blocked("Literal is not valid Unicode for exact source binding");
                yield literal.value();
            }
            case BOOLEAN -> Boolean.parseBoolean(literal.value());
            case INTEGER -> new BigDecimal(new BigInteger(literal.value()));
            case DECIMAL -> new BigDecimal(literal.value());
            case DATE -> LocalDate.parse(literal.value());
            case TIMESTAMP -> Instant.parse(literal.value()).atOffset(ZoneOffset.UTC);
            default -> throw blocked("Unsupported typed literal");
        };
    }

    static PostgresWarehouse.Failure blocked(String message) {
        return new PostgresWarehouse.Failure("BLOCKED", message);
    }

    private static RelationalIR.Aggregate zeroAggregate(RelationalIR.Node node) {
        if (node instanceof RelationalIR.Project p
                && p.columns().stream().allMatch(c -> c.expression() instanceof RelationalIR.Field))
            return zeroAggregate(p.input());
        return node instanceof RelationalIR.Aggregate a && a.groupBy().isEmpty() ? a : null;
    }
}
