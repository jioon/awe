package ai.awe.kernel;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;

class RelationalContractsTest {
    private final EntityRef subject = new EntityRef("entity.test.device", "1.0.0");
    private final ObjectType schema =
            new ObjectType(
                    new EntityRef("dataset.test.rows", "1.0.0"),
                    ObjectType.Family.DATASET,
                    "Explicit record",
                    List.of(
                            new ObjectType.Property(
                                    "event_id", ObjectType.DataType.STRING, true, false),
                            new ObjectType.Property(
                                    "device_id", ObjectType.DataType.STRING, false, false),
                            new ObjectType.Property(
                                    "amount", ObjectType.DataType.DECIMAL, false, false),
                            new ObjectType.Property(
                                    "event_time", ObjectType.DataType.TIMESTAMP, true, false)),
                    List.of("event_id"));

    private RelationalIR.Node aggregate(RelationalIR.AggregateKind kind, String field) {
        return new RelationalIR.Aggregate(
                new RelationalIR.Scan("events", 0, 0),
                List.of(),
                List.of(new RelationalIR.Measure("value", kind, new RelationalIR.Field(field))));
    }

    private MetricContract contract(
            RelationalIR.Node plan,
            ObjectType.DataType type,
            boolean nullable,
            MetricContract.EmptyPolicy empty) {
        return new MetricContract(
                subject,
                "events.device_id",
                MetricContract.IdentityNullPolicy.IGNORE,
                "devices",
                TemporalContract.TimeRole.EVENT_TIME,
                ZoneId.of("Asia/Shanghai"),
                List.of(
                        new MetricContract.Source(
                                "events", new EntityRef("function.test.read", "1.0.0"))),
                plan,
                List.of(new MetricContract.Output("value", type, "devices", nullable)),
                List.of(),
                empty,
                MetricContract.ZeroDivisionPolicy.NULL,
                Optional.empty());
    }

    @Test
    void scalarGrainIsAnExplicitAggregateTupleNotAFictitiousSourceColumn() {
        assertEquals(List.of(), new Grain(List.of(), Grain.GrainKind.AGGREGATE).keys());
        assertThrows(
                IllegalArgumentException.class, () -> new Grain(List.of(), Grain.GrainKind.EVENT));
        var metric =
                contract(
                        aggregate(RelationalIR.AggregateKind.COUNT_DISTINCT, "events.device_id"),
                        ObjectType.DataType.INTEGER,
                        false,
                        MetricContract.EmptyPolicy.ZERO);
        assertDoesNotThrow(() -> SemanticPlanTypes.validate(metric, Map.of("events", schema)));
    }

    @Test
    void staticallyUnknownFieldsAndWrongMeasureTypesCannotPublish() {
        var missing =
                contract(
                        aggregate(RelationalIR.AggregateKind.COUNT_DISTINCT, "events.unknown"),
                        ObjectType.DataType.INTEGER,
                        false,
                        MetricContract.EmptyPolicy.ZERO);
        assertThrows(
                IllegalArgumentException.class,
                () -> SemanticPlanTypes.validate(missing, Map.of("events", schema)));
        var sumText =
                contract(
                        aggregate(RelationalIR.AggregateKind.SUM, "events.device_id"),
                        ObjectType.DataType.DECIMAL,
                        true,
                        MetricContract.EmptyPolicy.NO_RECORD);
        assertThrows(
                IllegalArgumentException.class,
                () -> SemanticPlanTypes.validate(sumText, Map.of("events", schema)));
    }

    @Test
    void nullAndZeroPoliciesCannotBeErasedByAnOutputDeclaration() {
        var sum = aggregate(RelationalIR.AggregateKind.SUM, "events.amount");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SemanticPlanTypes.validate(
                                contract(
                                        sum,
                                        ObjectType.DataType.DECIMAL,
                                        false,
                                        MetricContract.EmptyPolicy.NO_RECORD),
                                Map.of("events", schema)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SemanticPlanTypes.validate(
                                contract(
                                        sum,
                                        ObjectType.DataType.DECIMAL,
                                        false,
                                        MetricContract.EmptyPolicy.ZERO),
                                Map.of("events", schema)));
        assertDoesNotThrow(
                () ->
                        SemanticPlanTypes.validate(
                                contract(
                                        sum,
                                        ObjectType.DataType.DECIMAL,
                                        true,
                                        MetricContract.EmptyPolicy.ZERO),
                                Map.of("events", schema)));
        var nonNullSchema =
                new ObjectType(
                        schema.ref(),
                        schema.family(),
                        schema.description(),
                        schema.properties().stream()
                                .map(
                                        p ->
                                                p.name().equals("amount")
                                                        ? new ObjectType.Property(
                                                                p.name(),
                                                                p.type(),
                                                                true,
                                                                p.sensitive())
                                                        : p)
                                .toList(),
                        schema.identityKeys());
        assertDoesNotThrow(
                () ->
                        SemanticPlanTypes.validate(
                                contract(
                                        sum,
                                        ObjectType.DataType.DECIMAL,
                                        false,
                                        MetricContract.EmptyPolicy.ZERO),
                                Map.of("events", nonNullSchema)));
        var avg = aggregate(RelationalIR.AggregateKind.AVG, "events.amount");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SemanticPlanTypes.validate(
                                contract(
                                        avg,
                                        ObjectType.DataType.DECIMAL,
                                        false,
                                        MetricContract.EmptyPolicy.ZERO),
                                Map.of("events", schema)));
    }

    @Test
    void literalsAndIdentifiersHaveNoSqlEscapeHatchOrSilentTemporalRounding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RelationalIR.Field("events.id);DROP TABLE x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RelationalIR.Literal(ObjectType.DataType.DECIMAL, "NaN"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RelationalIR.Literal(
                                ObjectType.DataType.TIMESTAMP, "2026-08-18T00:00:00.000000001Z"));
        assertEquals("", new RelationalIR.Literal(ObjectType.DataType.STRING, "").value());
        assertEquals("1", new RelationalIR.Literal(ObjectType.DataType.DECIMAL, "1.00").value());
    }

    @Test
    void plansAreBoundedAndUseExactlyTheirDeclaredSourceAliases() {
        RelationalIR.Node deep = new RelationalIR.Scan("events", 0, 0);
        for (int i = 0; i < 20; i++)
            deep =
                    new RelationalIR.Filter(
                            deep,
                            new RelationalIR.IsNull(
                                    new RelationalIR.Field("events.device_id"), true));
        var plan = deep;
        assertThrows(IllegalArgumentException.class, () -> RelationalIR.validate(plan));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        contract(
                                new RelationalIR.Scan("other", 0, 0),
                                ObjectType.DataType.INTEGER,
                                false,
                                MetricContract.EmptyPolicy.ZERO));
    }

    @Test
    void setColumnsMustRetainExplicitNamesAndTypes() {
        var left =
                new RelationalIR.Project(
                        new RelationalIR.Scan("events", 0, 0),
                        List.of(
                                new RelationalIR.Column(
                                        "id", new RelationalIR.Field("events.device_id"))));
        var right =
                new RelationalIR.Project(
                        new RelationalIR.Scan("events", -7, 0),
                        List.of(
                                new RelationalIR.Column(
                                        "id", new RelationalIR.Field("events.amount"))));
        var set =
                new RelationalIR.SetOperation(left, right, RelationalIR.SetKind.INTERSECT_DISTINCT);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SemanticPlanTypes(
                                        Map.of("events", schema),
                                        MetricContract.ZeroDivisionPolicy.NULL)
                                .analyze(set));
    }

    @Test
    void staticAndLogicalDatasetStatesAreExplicit() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DatasetContract(
                                schema.ref(),
                                DatasetContract.Mode.LOGICAL_TARGET,
                                Optional.empty(),
                                DatasetContract.ReadMode.EVENT_WINDOW,
                                TemporalContract.TimeRole.EVENT_TIME));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DatasetContract(
                                schema.ref(),
                                DatasetContract.Mode.OBSERVED_SOURCE,
                                Optional.empty(),
                                DatasetContract.ReadMode.STATIC_SNAPSHOT,
                                TemporalContract.TimeRole.EVENT_TIME));
        assertDoesNotThrow(
                () ->
                        new DatasetContract(
                                schema.ref(),
                                DatasetContract.Mode.LOGICAL_TARGET,
                                Optional.of("target.test"),
                                DatasetContract.ReadMode.STATIC_SNAPSHOT,
                                TemporalContract.TimeRole.OBSERVED_AT));
    }
}
