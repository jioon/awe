package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.model.ModelCompatibility;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExecutableFacetCodecTest {
    private final TypedModels typed = new TypedModels();
    private final JsonDocuments json = new JsonDocuments();

    private MetricContract contract() {
        return new MetricContract(
                new EntityRef("entity.video.user", "1.0.0"),
                "events.user_id",
                MetricContract.IdentityNullPolicy.IGNORE,
                "devices",
                TemporalContract.TimeRole.EVENT_TIME,
                ZoneId.of("Asia/Shanghai"),
                List.of(
                        new MetricContract.Source(
                                "events", new EntityRef("function.video.read", "1.0.0"))),
                new RelationalIR.Aggregate(
                        new RelationalIR.Filter(
                                new RelationalIR.Scan("events", 0, 0),
                                new RelationalIR.In(
                                        new RelationalIR.Field("events.channel"),
                                        List.of(
                                                new RelationalIR.Literal(
                                                        ObjectType.DataType.STRING, "")),
                                        true)),
                        List.of(),
                        List.of(
                                new RelationalIR.Measure(
                                        "value",
                                        RelationalIR.AggregateKind.COUNT_DISTINCT,
                                        new RelationalIR.Field("events.user_id")))),
                List.of(
                        new MetricContract.Output(
                                "value", ObjectType.DataType.INTEGER, "devices", false)),
                List.of(),
                MetricContract.EmptyPolicy.ZERO,
                MetricContract.ZeroDivisionPolicy.NULL,
                Optional.empty());
    }

    private ObjectNode spec() {
        var spec = TypedModelFixtures.spec("Metric");
        spec.put("schemaVersion", 3);
        var model = (ObjectNode) spec.path("model");
        ((ObjectNode) model.path("grain")).put("kind", "AGGREGATE").putArray("keys");
        spec.set("metricContract", typed.encodeValue(contract()));
        return spec;
    }

    @Test
    void completeMetricFacetRoundTripsWithTaggedOperatorsAndNullDirectBinding() {
        var spec = spec();
        var canonical = typed.canonicalSpec("Metric", spec);
        assertEquals(contract(), typed.metricContract(canonical));
        assertEquals(typed.decode("Metric", spec), typed.decode("Metric", canonical));
        assertEquals(
                json.canonical(canonical),
                json.canonical(typed.canonicalSpec("Metric", canonical)));
        assertTrue(
                typed.references("Metric", spec)
                        .contains(new EntityRef("function.video.read", "1.0.0")));
        assertTrue(canonical.path("metricContract").path("direct").isNull());
    }

    @Test
    void oldV2CanonicalDocumentsStayUnchangedAndCannotHideNewFacets() {
        var old = TypedModelFixtures.spec("Metric");
        var before = typed.canonicalSpec("Metric", old);
        assertEquals(2, before.path("schemaVersion").asInt());
        assertFalse(before.has("metricContract"));
        old.set("metricContract", typed.encodeValue(contract()));
        assertThrows(IllegalArgumentException.class, () -> typed.decode("Metric", old));
        var scalarV2 = spec();
        scalarV2.put("schemaVersion", 2);
        scalarV2.remove("metricContract");
        assertThrows(IllegalArgumentException.class, () -> typed.decode("Metric", scalarV2));
    }

    @Test
    void executableMeaningAndSchemaMigrationRequireMajorCompatibilityReview() {
        var before = TypedModelFixtures.spec("Metric");
        var next = typed.canonicalSpec("Metric", spec());
        assertEquals(
                true, new ModelCompatibility().compare("Metric", before, next).get("breaking"));
        var changed = spec();
        ((ObjectNode) changed.path("metricContract")).put("unit", "accounts");
        assertEquals(
                true, new ModelCompatibility().compare("Metric", next, changed).get("breaking"));
        assertEquals(
                false,
                new ModelCompatibility()
                        .compare("Metric", next, typed.canonicalSpec("Metric", next))
                        .get("breaking"));
    }

    @Test
    void unknownOperatorsAndUnexpectedFieldsCannotBecomeOpaqueExecution() {
        var unknown = spec();
        ((ObjectNode) unknown.at("/metricContract/plan")).put("kind", "SHELL");
        assertThrows(IllegalArgumentException.class, () -> typed.decode("Metric", unknown));
        var bypass = spec();
        ((ObjectNode) bypass.path("metricContract")).put("sql", "SELECT * FROM anything");
        assertThrows(IllegalArgumentException.class, () -> typed.decode("Metric", bypass));
        var coercion = spec();
        ((ObjectNode) coercion.at("/metricContract/plan/input/input")).put("fromDayOffset", "0");
        assertThrows(IllegalArgumentException.class, () -> typed.decode("Metric", coercion));
    }

    @Test
    void staticDatasetDoesNotRequireOrInventAnEventTimeColumn() {
        var spec = TypedModelFixtures.spec("Dataset");
        spec.put("schemaVersion", 3);
        ((ObjectNode) spec.at("/model/time")).putArray("roles").add("OBSERVED_AT");
        var facet =
                new DatasetContract(
                        new EntityRef("dataset.video.rows", "1.0.0"),
                        DatasetContract.Mode.OBSERVED_SOURCE,
                        Optional.empty(),
                        DatasetContract.ReadMode.STATIC_SNAPSHOT,
                        TemporalContract.TimeRole.OBSERVED_AT);
        spec.set("datasetContract", typed.encodeValue(facet));
        spec.set(
                "bindings",
                json.parse(
                        "[{\"sourceSystem\":\"postgres\",\"externalRef\":\"cities\",\"fingerprint\":\""
                                + "a".repeat(64)
                                + "\",\"timeFields\":{}}]"));
        assertEquals(facet, typed.datasetContract(typed.canonicalSpec("Dataset", spec)));
        ((ObjectNode) spec.at("/bindings/0/timeFields")).put("OBSERVED_AT", "fake_date");
        assertThrows(IllegalArgumentException.class, () -> typed.decode("Dataset", spec));
    }
}
