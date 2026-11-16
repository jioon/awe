package ai.awe.registry.consumption.query;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.*;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import ai.awe.registry.support.JsonDocuments;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RelationalSqlCompilerTest {
    private static final Instant FROM = Instant.parse("2026-08-18T00:00:00Z"),
            TO = Instant.parse("2026-08-19T00:00:00Z");
    private static final EntityRef SUBJECT = ref("entity.demo.user");
    private final JsonDocuments json = new JsonDocuments();

    private static EntityRef ref(String id) {
        return new EntityRef(id, "1.0.0");
    }

    private ObjectType.Property property(String name, ObjectType.DataType type, boolean required) {
        return new ObjectType.Property(name, type, required, false);
    }

    private BoundQuerySource eventSource() {
        var type =
                new ObjectType(
                        ref("dataset.demo.event_row"),
                        ObjectType.Family.DATASET,
                        "Event rows",
                        List.of(
                                property("event_id", ObjectType.DataType.STRING, true),
                                        property("user_id", ObjectType.DataType.STRING, false),
                                property("event_time", ObjectType.DataType.TIMESTAMP, true),
                                        property("amount", ObjectType.DataType.DECIMAL, true),
                                property("kind", ObjectType.DataType.STRING, true),
                                        property("tenant_id", ObjectType.DataType.STRING, true)),
                        List.of("event_id"));
        var dataset =
                new SemanticModel.Dataset(
                        ref("dataset.demo.events"),
                        new Grain(List.of("event_id"), Grain.GrainKind.EVENT),
                        new TemporalContract(
                                Set.of(TemporalContract.TimeRole.EVENT_TIME),
                                ZoneId.of("UTC"),
                                false),
                        SUBJECT,
                        Set.of());
        return new BoundQuerySource(
                "e",
                dataset,
                type,
                json.object(
                        "{\"sourceSystem\":\"postgres\",\"externalRef\":\"events\",\"fingerprint\":\""
                                + "a".repeat(64)
                                + "\",\"timeFields\":{\"EVENT_TIME\":\"event_time\"}}"),
                UUID.randomUUID(),
                "b".repeat(64));
    }

    private BoundQuerySource staticSource() {
        var type =
                new ObjectType(
                        ref("dataset.demo.user_row"),
                        ObjectType.Family.DATASET,
                        "Static user rows",
                        List.of(
                                property("user_id", ObjectType.DataType.STRING, true),
                                property("city", ObjectType.DataType.STRING, false)),
                        List.of("user_id"));
        var dataset =
                new SemanticModel.Dataset(
                        ref("dataset.demo.users"),
                        new Grain(List.of("user_id"), Grain.GrainKind.ENTITY_SNAPSHOT),
                        new TemporalContract(
                                Set.of(TemporalContract.TimeRole.OBSERVED_AT),
                                ZoneId.of("UTC"),
                                false),
                        SUBJECT,
                        Set.of());
        var facet =
                new DatasetContract(
                        type.ref(),
                        DatasetContract.Mode.OBSERVED_SOURCE,
                        Optional.empty(),
                        DatasetContract.ReadMode.STATIC_SNAPSHOT,
                        TemporalContract.TimeRole.OBSERVED_AT);
        return new BoundQuerySource(
                "u",
                dataset,
                type,
                json.object(
                        "{\"sourceSystem\":\"postgres\",\"externalRef\":\"users\",\"fingerprint\":\""
                                + "a".repeat(64)
                                + "\",\"timeFields\":{}}"),
                UUID.randomUUID(),
                "b".repeat(64),
                Optional.of(facet));
    }

    private PostgresWarehouse.Catalog catalog(BoundQuerySource source) {
        var columns =
                source.rowSchema().properties().stream()
                        .map(
                                p ->
                                        new PostgresWarehouse.Column(
                                                p.name(),
                                                p.type().name(),
                                                switch (p.type()) {
                                                    case STRING -> "text";
                                                    case TIMESTAMP -> "timestamptz";
                                                    case DECIMAL -> "numeric";
                                                    default -> throw new IllegalArgumentException();
                                                },
                                                !p.required(),
                                                true,
                                                "C"))
                        .toList();
        return new PostgresWarehouse.Catalog(
                "public." + (source.alias().equals("e") ? "events" : "users"),
                columns,
                source.dataset().grain().keys(),
                "actual-schema-hash-from-executor");
    }

    private MetricContract metric(
            RelationalIR.Node plan, List<MetricContract.Output> outputs, List<String> grain) {
        var aliases = RelationalIR.sources(plan).stream().sorted().toList();
        return new MetricContract(
                SUBJECT,
                "e.user_id",
                MetricContract.IdentityNullPolicy.REJECT,
                "users",
                TemporalContract.TimeRole.EVENT_TIME,
                ZoneId.of("UTC"),
                aliases.stream()
                        .map(a -> new MetricContract.Source(a, ref("function.demo." + a)))
                        .toList(),
                plan,
                outputs,
                grain,
                MetricContract.EmptyPolicy.NO_RECORD,
                MetricContract.ZeroDivisionPolicy.NULL,
                Optional.empty());
    }

    private RelationalSqlCompiler compiler(
            MetricContract metric,
            Map<EntityRef, LinkType> links,
            Map<String, RelationalIR.Predicate> scopes) {
        var e = eventSource();
        var u = staticSource();
        var sources = new HashMap<String, BoundQuerySource>();
        var catalogs = new HashMap<String, PostgresWarehouse.Catalog>();
        for (String alias : RelationalIR.sources(metric.plan())) {
            var source = alias.equals("e") ? e : u;
            sources.put(alias, source);
            catalogs.put(alias, catalog(source));
        }
        return new RelationalSqlCompiler(metric, sources, catalogs, links, scopes, FROM, TO);
    }

    private MetricContract.Output output(String name, ObjectType.DataType type, boolean nullable) {
        return new MetricContract.Output(name, type, "users", nullable);
    }

    @Test
    void valuesArePreparedAndParameterOrderFollowsNestedSqlRatherThanAstOrder() {
        String literal = "launch'; DROP TABLE users; --";
        var input =
                new RelationalIR.Filter(
                        new RelationalIR.Scan("e", 0, 0),
                        new RelationalIR.Compare(
                                RelationalIR.CompareOp.EQ,
                                new RelationalIR.Field("e.kind"),
                                new RelationalIR.Literal(ObjectType.DataType.STRING, literal)));
        var plan =
                new RelationalIR.Project(
                        input,
                        List.of(
                                new RelationalIR.Column(
                                        "event_id", new RelationalIR.Field("e.event_id")),
                                new RelationalIR.Column(
                                        "tag",
                                        new RelationalIR.Literal(
                                                ObjectType.DataType.INTEGER, "7"))));
        var compiled =
                compiler(
                                metric(
                                        plan,
                                        List.of(
                                                output(
                                                        "event_id",
                                                        ObjectType.DataType.STRING,
                                                        false),
                                                output("tag", ObjectType.DataType.INTEGER, false)),
                                        List.of("event_id")),
                                Map.of(),
                                Map.of())
                        .compile();
        assertFalse(compiled.sql().contains(literal));
        assertTrue(compiled.sql().contains("?::text"));
        assertEquals(new BigDecimal("7"), compiled.parameters().getFirst());
        assertEquals(FROM.atOffset(ZoneOffset.UTC), compiled.parameters().get(1));
        assertEquals(TO.atOffset(ZoneOffset.UTC), compiled.parameters().get(2));
        assertEquals(literal, compiled.parameters().get(3));
    }

    @Test
    void sourceScopesAreInjectedBeforeEveryMetricOperationAndCannotNameAnotherSource() {
        var plan =
                new RelationalIR.Aggregate(
                        new RelationalIR.Scan("e", 0, 0),
                        List.of(),
                        List.of(
                                new RelationalIR.Measure(
                                        "value",
                                        RelationalIR.AggregateKind.COUNT_DISTINCT,
                                        new RelationalIR.Field("e.user_id"))));
        var metric =
                metric(
                        plan,
                        List.of(output("value", ObjectType.DataType.INTEGER, false)),
                        List.of());
        var policy =
                new RelationalIR.Compare(
                        RelationalIR.CompareOp.EQ,
                        new RelationalIR.Field("e.tenant_id"),
                        new RelationalIR.Literal(ObjectType.DataType.STRING, "tenant-a"));
        var compiler = compiler(metric, Map.of(), Map.of("e", policy));
        var compiled = compiler.compile();
        assertTrue(compiled.parameters().contains("tenant-a"));
        assertTrue(compiled.sourceFields().get("e").contains("tenant_id"));
        assertTrue(compiler.scanChecks().getFirst().parameters().contains("tenant-a"));
        assertTrue(compiler.scanChecks().getFirst().nullIdentitySql().contains("IS NULL"));
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> compiler(metric, Map.of(), Map.of("u", policy)).compile());
    }

    @Test
    void typedAggregateAndRatioHaveExplicitZeroDivisionAndExactNumericContracts() {
        var aggregate =
                new RelationalIR.Aggregate(
                        new RelationalIR.Scan("e", 0, 0),
                        List.of(),
                        List.of(
                                new RelationalIR.Measure(
                                        "count",
                                        RelationalIR.AggregateKind.COUNT_DISTINCT,
                                        new RelationalIR.Field("e.user_id")),
                                new RelationalIR.Measure(
                                        "amount",
                                        RelationalIR.AggregateKind.SUM,
                                        new RelationalIR.Field("e.amount"))));
        var plan =
                new RelationalIR.Project(
                        aggregate,
                        List.of(
                                new RelationalIR.Column(
                                        "ratio",
                                        new RelationalIR.Arithmetic(
                                                RelationalIR.ArithmeticOp.DIVIDE,
                                                new RelationalIR.Field("amount"),
                                                new RelationalIR.Field("count")))));
        var metric =
                metric(
                        plan,
                        List.of(output("ratio", ObjectType.DataType.DECIMAL, true)),
                        List.of());
        var compiled = compiler(metric, Map.of(), Map.of()).compile();
        assertTrue(compiled.sql().contains("COUNT(DISTINCT"));
        assertTrue(compiled.sql().contains("SUM("));
        assertTrue(compiled.sql().contains("NULLIF("));
        var narrowing =
                metric(
                        plan,
                        List.of(output("ratio", ObjectType.DataType.INTEGER, true)),
                        List.of());
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> compiler(narrowing, Map.of(), Map.of()).compile());
    }

    @Test
    void staticDimensionJoinRequiresRegisteredEqualityAndAnActualCompleteRightKey() {
        var e = eventSource();
        var u = staticSource();
        var linkRef = ref("link.demo.users");
        var join =
                new RelationalIR.Join(
                        new RelationalIR.Scan("e", 0, 0),
                        new RelationalIR.Scan("u", 0, 0),
                        RelationalIR.JoinKind.LEFT,
                        linkRef,
                        List.of(new RelationalIR.JoinKey("e.user_id", "u.user_id")));
        var projection =
                new RelationalIR.Project(
                        join,
                        List.of(
                                new RelationalIR.Column(
                                        "event_id", new RelationalIR.Field("e.event_id")),
                                new RelationalIR.Column("city", new RelationalIR.Field("u.city"))));
        var metric =
                metric(
                        projection,
                        List.of(
                                output("event_id", ObjectType.DataType.STRING, false),
                                output("city", ObjectType.DataType.STRING, true)),
                        List.of("event_id"));
        var link =
                new LinkType(
                        linkRef,
                        e.dataset().ref(),
                        u.dataset().ref(),
                        LinkType.Meaning.HAS_DIMENSION,
                        RelationContract.Cardinality.MANY_TO_ONE,
                        RelationContract.TemporalPolicy.TIME_INDEPENDENT,
                        "columns.equal.v1",
                        true);
        var compiled = compiler(metric, Map.of(linkRef, link), Map.of()).compile();
        assertTrue(compiled.sql().contains("LEFT JOIN"));
        assertEquals(2, compiled.parameters().size());
        var unknownMapping =
                new LinkType(
                        linkRef,
                        e.dataset().ref(),
                        u.dataset().ref(),
                        LinkType.Meaning.HAS_DIMENSION,
                        RelationContract.Cardinality.MANY_TO_ONE,
                        RelationContract.TemporalPolicy.TIME_INDEPENDENT,
                        "wiki.mapping",
                        true);
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> compiler(metric, Map.of(linkRef, unknownMapping), Map.of()).compile());
        var badJoin =
                new RelationalIR.Join(
                        new RelationalIR.Scan("e", 0, 0),
                        new RelationalIR.Scan("u", 0, 0),
                        RelationalIR.JoinKind.LEFT,
                        linkRef,
                        List.of(new RelationalIR.JoinKey("e.kind", "u.city")));
        var bad =
                metric(
                        new RelationalIR.Project(
                                badJoin,
                                List.of(
                                        new RelationalIR.Column(
                                                "event_id", new RelationalIR.Field("e.event_id")))),
                        List.of(output("event_id", ObjectType.DataType.STRING, false)),
                        List.of("event_id"));
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> compiler(bad, Map.of(linkRef, link), Map.of()).compile());
        var asOf =
                new LinkType(
                        linkRef,
                        e.dataset().ref(),
                        u.dataset().ref(),
                        LinkType.Meaning.HAS_DIMENSION,
                        RelationContract.Cardinality.MANY_TO_ONE,
                        RelationContract.TemporalPolicy.AS_OF,
                        "columns.equal.v1",
                        true);
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> compiler(metric, Map.of(linkRef, asOf), Map.of()).compile());
    }

    @Test
    void setOperandsNeedNamedTypeAlignmentAndUseDistinctRelationalOperations() {
        var left =
                new RelationalIR.Project(
                        new RelationalIR.Scan("e", -7, 0),
                        List.of(
                                new RelationalIR.Column(
                                        "user_id", new RelationalIR.Field("e.user_id"))));
        var right =
                new RelationalIR.Project(
                        new RelationalIR.Scan("e", -1, 0),
                        List.of(
                                new RelationalIR.Column(
                                        "user_id", new RelationalIR.Field("e.user_id"))));
        var set = new RelationalIR.SetOperation(left, right, RelationalIR.SetKind.EXCEPT_DISTINCT);
        var metric =
                metric(
                        set,
                        List.of(output("user_id", ObjectType.DataType.STRING, true)),
                        List.of("user_id"));
        assertTrue(compiler(metric, Map.of(), Map.of()).compile().sql().contains(" EXCEPT "));
        var mismatch =
                new RelationalIR.SetOperation(
                        left,
                        new RelationalIR.Project(
                                new RelationalIR.Scan("e", 0, 0),
                                List.of(
                                        new RelationalIR.Column(
                                                "different", new RelationalIR.Field("e.user_id")))),
                        RelationalIR.SetKind.INTERSECT_DISTINCT);
        assertThrows(
                PostgresWarehouse.Failure.class,
                () ->
                        compiler(
                                        metric(
                                                mismatch,
                                                List.of(
                                                        output(
                                                                "user_id",
                                                                ObjectType.DataType.STRING,
                                                                true)),
                                                List.of("user_id")),
                                        Map.of(),
                                        Map.of())
                                .compile());
    }

    @Test
    void calendarWindowsRespectBusinessTimezoneDstAndExposeActualAuthorizationExtents() {
        var base =
                metric(
                        new RelationalIR.Aggregate(
                                new RelationalIR.Scan("e", -1, -1),
                                List.of(),
                                List.of(
                                        new RelationalIR.Measure(
                                                "value",
                                                RelationalIR.AggregateKind.COUNT_ROWS,
                                                new RelationalIR.Literal(
                                                        ObjectType.DataType.INTEGER, "1")))),
                        List.of(output("value", ObjectType.DataType.INTEGER, false)),
                        List.of());
        var metric =
                new MetricContract(
                        base.statisticalSubject(),
                        base.identityField(),
                        base.identityNullPolicy(),
                        base.unit(),
                        base.timeRole(),
                        ZoneId.of("America/New_York"),
                        base.sources(),
                        base.plan(),
                        base.outputs(),
                        base.grain(),
                        base.emptyPolicy(),
                        base.zeroDivisionPolicy(),
                        base.direct());
        var windows =
                RelationalSqlCompiler.windows(
                        metric,
                        Instant.parse("2026-03-08T05:00:00Z"),
                        Instant.parse("2026-03-09T04:00:00Z"));
        assertEquals(Instant.parse("2026-03-07T05:00:00Z"), windows.getFirst().from());
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), windows.getFirst().to());
    }
}
