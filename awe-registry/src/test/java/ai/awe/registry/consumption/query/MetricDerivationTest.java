package ai.awe.registry.consumption.query;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetricDerivationTest {
    private static final EntityRef SUBJECT = new EntityRef("entity.demo.device", "1.0.0");
    private final JsonDocuments json = new JsonDocuments();

    private ObjectNode request() {
        return json.object(
                """
                {"protocolVersion":3,"intent":"QUERY_METRIC",
                 "metric":{"releaseId":"00000000-0000-0000-0000-000000000001","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "requirements":{"statisticalSubject":{"entityId":"entity.demo.device","modelVersion":"1.0.0"},
                  "unit":"devices","businessZone":"UTC","timeRole":"EVENT_TIME",
                  "measures":["devices"],"dimensions":["city"],"filters":[],
                  "from":"2026-08-18T00:00:00Z","to":"2026-08-19T00:00:00Z",
                  "view":"OBSERVED_SNAPSHOT","verification":"OBSERVE"},
                 "consumer":{"purpose":"analysis"},"constraints":{"maxRows":100,"maxBytes":65536},"idempotencyKey":"semantic.request"}
                """);
    }

    private MetricContract contract(RelationalIR.Node plan) {
        return new MetricContract(
                SUBJECT,
                "e.user_id",
                MetricContract.IdentityNullPolicy.REJECT,
                "devices",
                TemporalContract.TimeRole.EVENT_TIME,
                ZoneId.of("UTC"),
                List.of(
                        new MetricContract.Source(
                                "e", new EntityRef("function.demo.events", "1.0.0"))),
                plan,
                List.of(
                        new MetricContract.Output(
                                "city", ObjectType.DataType.STRING, "city", false),
                        new MetricContract.Output("app", ObjectType.DataType.STRING, "app", false),
                        new MetricContract.Output(
                                "devices", ObjectType.DataType.INTEGER, "devices", false)),
                List.of("city", "app"),
                MetricContract.EmptyPolicy.NO_RECORD,
                MetricContract.ZeroDivisionPolicy.REJECT,
                Optional.of(new MetricContract.Direct("bi.demo.devices", "1", "b".repeat(64))));
    }

    private RelationalIR.Aggregate aggregate() {
        var input =
                new RelationalIR.Project(
                        new RelationalIR.Filter(
                                new RelationalIR.Scan("e", -6, 0),
                                new RelationalIR.Compare(
                                        RelationalIR.CompareOp.EQ,
                                        new RelationalIR.Field("e.kind"),
                                        new RelationalIR.Literal(
                                                ObjectType.DataType.STRING, "launch"))),
                        List.of(
                                new RelationalIR.Column("city", new RelationalIR.Field("e.city")),
                                new RelationalIR.Column("app", new RelationalIR.Field("e.app")),
                                new RelationalIR.Column(
                                        "user_id", new RelationalIR.Field("e.user_id"))));
        return new RelationalIR.Aggregate(
                input,
                List.of("city", "app"),
                List.of(
                        new RelationalIR.Measure(
                                "devices",
                                RelationalIR.AggregateKind.COUNT_DISTINCT,
                                new RelationalIR.Field("user_id"))));
    }

    @Test
    void regroupRecomputesDistinctOverTheOriginalBusinessPopulation() {
        var originalAggregate = aggregate();
        var original = contract(originalAggregate);
        var result = MetricDerivation.derive(original, SemanticMetricRequest.decode(request()));
        var projection = assertInstanceOf(RelationalIR.Project.class, result.plan());
        var grouped = assertInstanceOf(RelationalIR.Aggregate.class, projection.input());
        assertEquals(List.of("city"), grouped.groupBy());
        assertSame(originalAggregate.input(), grouped.input());
        assertEquals(
                RelationalIR.AggregateKind.COUNT_DISTINCT, grouped.measures().getFirst().kind());
        assertEquals(new RelationalIR.Field("user_id"), grouped.measures().getFirst().expression());
        assertEquals(
                List.of("city", "devices"),
                result.outputs().stream().map(MetricContract.Output::name).toList());
        assertTrue(result.direct().isEmpty());
        assertEquals(original.sources(), result.sources());
        assertEquals(original.identityField(), result.identityField());
    }

    @Test
    void filtersOnPublishedDimensionsPrecedeAggregationAndPreserveOriginalFilters() {
        var req = request();
        ((ObjectNode) req.path("requirements"))
                .putArray("filters")
                .addObject()
                .put("field", "app")
                .put("operation", "EQ")
                .put("type", "STRING")
                .put("value", "self_owned");
        var originalAggregate = aggregate();
        var result =
                MetricDerivation.derive(
                        contract(originalAggregate), SemanticMetricRequest.decode(req));
        var grouped = (RelationalIR.Aggregate) ((RelationalIR.Project) result.plan()).input();
        var filter = assertInstanceOf(RelationalIR.Filter.class, grouped.input());
        assertSame(originalAggregate.input(), filter.input());
        assertEquals(
                new RelationalIR.Compare(
                        RelationalIR.CompareOp.EQ,
                        new RelationalIR.Field("app"),
                        new RelationalIR.Literal(ObjectType.DataType.STRING, "self_owned")),
                filter.predicate());
    }

    @Test
    void renamingProjectionTracesDimensionsAndMeasuresToTheUnderlyingAggregation() {
        var aggregate =
                new RelationalIR.Aggregate(
                        new RelationalIR.Scan("e", 0, 0),
                        List.of("e.city", "e.app"),
                        List.of(
                                new RelationalIR.Measure(
                                        "raw_devices",
                                        RelationalIR.AggregateKind.COUNT_DISTINCT,
                                        new RelationalIR.Field("e.user_id"))));
        var output =
                new RelationalIR.Project(
                        aggregate,
                        List.of(
                                new RelationalIR.Column("city", new RelationalIR.Field("e.city")),
                                new RelationalIR.Column("app", new RelationalIR.Field("e.app")),
                                new RelationalIR.Column(
                                        "devices", new RelationalIR.Field("raw_devices"))));
        var result =
                MetricDerivation.derive(contract(output), SemanticMetricRequest.decode(request()));
        var grouped = (RelationalIR.Aggregate) ((RelationalIR.Project) result.plan()).input();
        assertEquals(List.of("e.city"), grouped.groupBy());
        assertEquals("raw_devices", grouped.measures().getFirst().name());
        assertEquals(
                new RelationalIR.Field("raw_devices"),
                ((RelationalIR.Project) result.plan()).columns().getLast().expression());
    }

    @Test
    void exactRequestRetainsDirectButChangedComplexPlansAreUnsupported() {
        var complex =
                new RelationalIR.Project(
                        aggregate(),
                        List.of(
                                new RelationalIR.Column("city", new RelationalIR.Field("city")),
                                new RelationalIR.Column("app", new RelationalIR.Field("app")),
                                new RelationalIR.Column(
                                        "devices",
                                        new RelationalIR.Arithmetic(
                                                RelationalIR.ArithmeticOp.ADD,
                                                new RelationalIR.Field("devices"),
                                                new RelationalIR.Literal(
                                                        ObjectType.DataType.INTEGER, "1")))));
        var original = contract(complex);
        var exact = request();
        ((ObjectNode) exact.path("requirements")).putArray("dimensions").add("city").add("app");
        assertSame(
                original, MetricDerivation.derive(original, SemanticMetricRequest.decode(exact)));
        var failure =
                assertThrows(
                        MetricDerivation.UnsupportedDerivation.class,
                        () ->
                                MetricDerivation.derive(
                                        original, SemanticMetricRequest.decode(request())));
        assertEquals("UNSUPPORTED_PLAN_SHAPE", failure.reason());
    }

    @Test
    void semanticMismatchOrMeasureFilteringCannotRewriteTheMetric() {
        var original = contract(aggregate());
        for (var entry :
                Map.of(
                                "unit",
                                "people",
                                "businessZone",
                                "Asia/Shanghai",
                                "timeRole",
                                "AVAILABLE_AT")
                        .entrySet()) {
            var req = request();
            ((ObjectNode) req.path("requirements")).put(entry.getKey(), entry.getValue());
            assertThrows(
                    MetricDerivation.UnsupportedDerivation.class,
                    () -> MetricDerivation.derive(original, SemanticMetricRequest.decode(req)));
        }
        var filter = request();
        ((ObjectNode) filter.path("requirements"))
                .putArray("filters")
                .addObject()
                .put("field", "devices")
                .put("operation", "GT")
                .put("type", "INTEGER")
                .put("value", "0");
        assertEquals(
                "FILTER_NOT_DIMENSION",
                assertThrows(
                                MetricDerivation.UnsupportedDerivation.class,
                                () ->
                                        MetricDerivation.derive(
                                                original, SemanticMetricRequest.decode(filter)))
                        .reason());
        var wrongType = request();
        ((ObjectNode) wrongType.path("requirements"))
                .putArray("filters")
                .addObject()
                .put("field", "city")
                .put("operation", "EQ")
                .put("type", "INTEGER")
                .put("value", "1");
        assertEquals(
                "FILTER_TYPE_MISMATCH",
                assertThrows(
                                MetricDerivation.UnsupportedDerivation.class,
                                () ->
                                        MetricDerivation.derive(
                                                original, SemanticMetricRequest.decode(wrongType)))
                        .reason());
    }
}
