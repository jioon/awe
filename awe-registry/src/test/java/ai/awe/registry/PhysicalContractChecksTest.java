package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.AggregationContract;
import ai.awe.kernel.contract.Grain;
import ai.awe.kernel.contract.RelationContract;
import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.LinkType;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.model.PhysicalContractChecks;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Counterexamples exercise constraints of consumer models, not merely their pinned Dataset's
 * schema.
 */
class PhysicalContractChecksTest {
    private final JsonDocuments json = new JsonDocuments();
    private final PhysicalContractChecks checks = new PhysicalContractChecks();
    private final EntityRef subject = ref("entity.video.user"),
            dataset = ref("dataset.video.events"),
            dimension = ref("dimension.video.country");

    private EntityRef ref(String value) {
        return new EntityRef(value, "1.0.0");
    }

    private TemporalContract time() {
        return new TemporalContract(
                Set.of(
                        TemporalContract.TimeRole.EVENT_TIME,
                        TemporalContract.TimeRole.AVAILABLE_AT),
                ZoneId.of("UTC"),
                true);
    }

    private SemanticModel.Dataset source() {
        return new SemanticModel.Dataset(
                dataset,
                new Grain(List.of("event_id"), Grain.GrainKind.EVENT),
                time(),
                subject,
                Set.of(dimension));
    }

    private JsonNode metadata() {
        return json.parse(
                """
          {"externalRef":"hive.events","sourceVersion":"v1",
           "columns":[{"name":"event_id","type":"string","nullable":false},
             {"name":"user_id","type":"string","nullable":false},
             {"name":"country","type":"string","nullable":true},
             {"name":"event_time","type":"timestamp","nullable":false},
             {"name":"available_at","type":"timestamp with time zone","nullable":false}],
           "grainKeys":["event_id"],"timeFields":["event_time","available_at"]}
          """);
    }

    private JsonNode binding() {
        return json.parse(
                """
          {"sourceSystem":"hive","externalRef":"hive.events","fingerprint":"ignored-in-pure-check",
           "timeFields":{"EVENT_TIME":"event_time","AVAILABLE_AT":"available_at"}}
          """);
    }

    private SemanticModel.Metric metric(
            String key,
            Set<EntityRef> dimensions,
            EntityRef owner,
            TemporalContract temporal,
            AggregationContract.Kind kind) {
        return new SemanticModel.Metric(
                ref("metric.video.active"),
                new Grain(List.of(key), Grain.GrainKind.AGGREGATE),
                temporal,
                owner,
                dataset,
                new AggregationContract(kind, Set.of(), Set.of("day")),
                dimensions);
    }

    private SemanticModel.Feature feature(String observation, String available) {
        return new SemanticModel.Feature(
                ref("feature.video.active"),
                new Grain(List.of("user_id", "event_time"), Grain.GrainKind.FEATURE),
                time(),
                subject,
                dataset,
                observation,
                available);
    }

    @Test
    void validDatasetChecksBothGrainAndRoleToPhysicalFieldMapping() {
        assertTrue(checks.errors(source(), json.parse("{}"), binding(), metadata()).isEmpty());
        ObjectNode wrong = (ObjectNode) binding().deepCopy();
        ((ObjectNode) wrong.path("timeFields")).put("AVAILABLE_AT", "country");
        assertTrue(
                checks.errors(source(), json.parse("{}"), wrong, metadata()).stream()
                        .anyMatch(e -> e.contains("AVAILABLE_AT")));
        ObjectNode changed = (ObjectNode) metadata().deepCopy();
        changed.putArray("grainKeys").add("user_id");
        assertFalse(checks.errors(source(), json.parse("{}"), binding(), changed).isEmpty());
    }

    @Test
    void aggregatedMetricMayDifferFromSourceGrainButAllOwnColumnsMustExist() {
        var valid =
                metric(
                        "country",
                        Set.of(dimension),
                        subject,
                        time(),
                        AggregationContract.Kind.COUNT_DISTINCT);
        assertTrue(checks.errorsAgainstDataset(valid, source(), binding(), metadata()).isEmpty());
        var missing =
                metric(
                        "imaginary_column",
                        Set.of(dimension),
                        subject,
                        time(),
                        AggregationContract.Kind.COUNT_DISTINCT);
        assertTrue(
                checks.errorsAgainstDataset(missing, source(), binding(), metadata()).stream()
                        .anyMatch(e -> e.contains("imaginary_column")));
    }

    @Test
    void indirectConsumerCannotChangeSubjectDimensionsOrTimezone() {
        var unboundDimension =
                metric(
                        "country",
                        Set.of(ref("dimension.video.age")),
                        subject,
                        time(),
                        AggregationContract.Kind.SUM);
        assertTrue(
                checks
                        .errorsAgainstDataset(unboundDimension, source(), binding(), metadata())
                        .stream()
                        .anyMatch(e -> e.contains("dimensions")));
        var otherOwner =
                metric(
                        "country",
                        Set.of(),
                        ref("entity.video.device"),
                        time(),
                        AggregationContract.Kind.SUM);
        assertTrue(
                checks.errorsAgainstDataset(otherOwner, source(), binding(), metadata()).stream()
                        .anyMatch(e -> e.contains("subject")));
        var wrongTime =
                new TemporalContract(
                        Set.of(TemporalContract.TimeRole.EVENT_TIME),
                        ZoneId.of("Asia/Shanghai"),
                        true);
        var foreignTimezone =
                metric("country", Set.of(), subject, wrongTime, AggregationContract.Kind.SUM);
        assertTrue(
                checks
                        .errorsAgainstDataset(foreignTimezone, source(), binding(), metadata())
                        .stream()
                        .anyMatch(e -> e.contains("timezone")));
        var unsupplied =
                new TemporalContract(
                        Set.of(TemporalContract.TimeRole.BUSINESS_DATE), ZoneId.of("UTC"), true);
        assertTrue(
                checks
                        .errorsAgainstDataset(
                                metric(
                                        "country",
                                        Set.of(),
                                        subject,
                                        unsupplied,
                                        AggregationContract.Kind.SUM),
                                source(),
                                binding(),
                                metadata())
                        .stream()
                        .anyMatch(e -> e.contains("roles")));
    }

    @Test
    void customAggregationHasNoSilentPassingPath() {
        assertTrue(
                checks
                        .errorsAgainstDataset(
                                metric(
                                        "country",
                                        Set.of(),
                                        subject,
                                        time(),
                                        AggregationContract.Kind.CUSTOM),
                                source(),
                                binding(),
                                metadata())
                        .stream()
                        .anyMatch(e -> e.contains("CUSTOM")));
    }

    @Test
    void featureRequiresTwoRealTimestampFieldsWithExplicitHistoryMappings() {
        assertTrue(
                checks.errorsAgainstDataset(
                                feature("event_time", "available_at"),
                                source(),
                                binding(),
                                metadata())
                        .isEmpty());
        assertTrue(
                checks
                        .errorsAgainstDataset(
                                feature("missing_time", "available_at"),
                                source(),
                                binding(),
                                metadata())
                        .stream()
                        .anyMatch(e -> e.contains("missing_time")));
        assertThrows(IllegalArgumentException.class, () -> feature("event_time", "event_time"));
        assertTrue(
                checks
                        .errorsAgainstDataset(
                                feature("available_at", "event_time"),
                                source(),
                                binding(),
                                metadata())
                        .stream()
                        .anyMatch(e -> e.contains("mapping")));
        ObjectNode badType = (ObjectNode) metadata().deepCopy();
        badType.path("columns")
                .forEach(
                        c -> {
                            if (c.path("name").asText().equals("available_at"))
                                ((ObjectNode) c).put("type", "date");
                        });
        assertTrue(
                checks
                        .errorsAgainstDataset(
                                feature("event_time", "available_at"), source(), binding(), badType)
                        .stream()
                        .anyMatch(e -> e.contains("timestamp")));
    }

    @Test
    void objectBindingsVerifyTypesNullabilityAndIdentityGrain() {
        var object =
                new ObjectType(
                        subject,
                        ObjectType.Family.BUSINESS_ENTITY,
                        "User identity",
                        List.of(
                                new ObjectType.Property(
                                        "user_id", ObjectType.DataType.STRING, true, false)),
                        List.of("user_id"));
        ObjectNode observed = (ObjectNode) metadata().deepCopy();
        observed.putArray("grainKeys").add("user_id");
        assertTrue(checks.errors(object, json.parse("{}"), binding(), observed).isEmpty());
        assertTrue(
                checks.errors(object, json.parse("{}"), binding(), metadata()).stream()
                        .anyMatch(e -> e.contains("identity")));
        observed.path("columns")
                .forEach(
                        c -> {
                            if (c.path("name").asText().equals("user_id")) {
                                ((ObjectNode) c).put("type", "integer");
                                ((ObjectNode) c).put("nullable", true);
                            }
                        });
        var errors = checks.errors(object, json.parse("{}"), binding(), observed);
        assertTrue(errors.stream().anyMatch(e -> e.contains("type")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("nullability")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("non-null")));
    }

    @Test
    void biDefinitionRequiresExactAuthoritativeConsumerDeclaration() {
        ObjectNode bi = (ObjectNode) binding().deepCopy();
        bi.put("sourceSystem", "bi");
        JsonNode spec =
                json.parse(
                        "{\"model\":{\"ref\":{\"id\":\"dataset.video.events\",\"modelVersion\":\"1.0.0\"},\"declaredSemantics\":\"reviewed\"}}");
        ObjectNode observed = (ObjectNode) metadata().deepCopy();
        assertTrue(
                checks.errors(source(), spec, bi, observed).stream()
                        .anyMatch(e -> e.contains("BI")));
        observed.set("definition", json.parse("{\"declaredSemantics\":\"reviewed\"}"));
        assertTrue(checks.errors(source(), spec, bi, observed).isEmpty());
        observed.set("definition", json.parse("{\"declaredSemantics\":\"different\"}"));
        assertTrue(
                checks.errors(source(), spec, bi, observed).stream()
                        .anyMatch(e -> e.contains("BI")));
    }

    @Test
    void unrelatedDeclarationsCannotAcquirePhysicalVerificationFromFingerprintAlone() {
        var link =
                new LinkType(
                        ref("link.video.owner"),
                        subject,
                        dataset,
                        LinkType.Meaning.DEPENDS_ON,
                        RelationContract.Cardinality.MANY_TO_ONE,
                        RelationContract.TemporalPolicy.AS_OF,
                        "identity.user",
                        false);
        assertTrue(
                checks.errors(link, json.parse("{}"), binding(), metadata()).stream()
                        .anyMatch(e -> e.contains("not supported")));
    }
}
