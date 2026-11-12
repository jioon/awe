package ai.awe.kernel;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Counterexamples from genuine data-warehouse and future ML feature use cases. */
class MetamodelContractsTest {
    private EntityRef ref(String id) { return new EntityRef(id,"1.0.0"); }
    private final EntityRef user = ref("entity.video.user");
    private final EntityRef events = ref("dataset.video.events");
    private final EntityRef dau = ref("metric.video.dau");
    private final EntityRef appVersion = ref("dimension.video.app_version");

    @Test void objectTypeRequiresDeclaredIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
            new ObjectType(user,ObjectType.Family.BUSINESS_ENTITY,"User",
                List.of(new ObjectType.Property("user_id",ObjectType.DataType.STRING,true,false)),
                List.of("device_id")));
        var type=new ObjectType(user,ObjectType.Family.BUSINESS_ENTITY,"Unique user",
            List.of(new ObjectType.Property("user_id",ObjectType.DataType.STRING,true,false)),
            List.of("user_id"));
        assertEquals("user_id",type.identityKeys().getFirst());
        assertInstanceOf(AweDefinition.class,type);
    }

    @Test void objectIdentityCannotBeNullable() {
        assertThrows(IllegalArgumentException.class,() -> new ObjectType(user,
            ObjectType.Family.BUSINESS_ENTITY,"User",
            List.of(new ObjectType.Property("user_id",ObjectType.DataType.STRING,false,false)),
            List.of("user_id")));
    }

    @Test void featureCannotConflateObservationWithDataAvailability() {
        var time=new TemporalContract(Set.of(TemporalContract.TimeRole.EVENT_TIME,
            TemporalContract.TimeRole.AVAILABLE_AT),ZoneId.of("Asia/Shanghai"),true);
        assertThrows(IllegalArgumentException.class,() -> new SemanticModel.Feature(
            ref("feature.video.active_days"),new Grain(List.of("user_id"),Grain.GrainKind.FEATURE),
            time,user,events,"event_time","event_time"));
    }

    @Test void linkTypeRequiresJoinCardinalityAndTemporalMeaning() {
        var safe=new LinkType(ref("link.video.user_dimension"),events,user,
            LinkType.Meaning.HAS_DIMENSION, RelationContract.Cardinality.MANY_TO_ONE,
            RelationContract.TemporalPolicy.AS_OF,"identity.user_map",true);
        assertTrue(safe.potentiallyFanoutSafe());
        var fanout=new LinkType(ref("link.video.device_user"),events,user,
            LinkType.Meaning.HAS_DIMENSION, RelationContract.Cardinality.MANY_TO_MANY,
            RelationContract.TemporalPolicy.AS_OF,"identity.device_map",true);
        assertFalse(fanout.potentiallyFanoutSafe());
    }

    @Test void metricSemanticsRejectInvalidDailyDistinctRollup() {
        var grain=new Grain(List.of("user_id","date"),Grain.GrainKind.FACT);
        var time=new TemporalContract(Set.of(TemporalContract.TimeRole.EVENT_TIME),
            ZoneId.of("Asia/Shanghai"),true);
        var model=new SemanticModel.Metric(dau,grain,time,user,events,
            new AggregationContract(AggregationContract.Kind.COUNT_DISTINCT,
                Set.of(),Set.of("day")),Set.of(appVersion));
        assertTrue(model.permitsDimension(appVersion));
        assertFalse(model.aggregation().maySumPreaggregatedValuesOver("day"));
    }

    @Test void featureRequiresAvailabilityAwareHistory() {
        var grain=new Grain(List.of("user_id","observation_time"),Grain.GrainKind.FEATURE);
        var eventOnly=new TemporalContract(Set.of(TemporalContract.TimeRole.EVENT_TIME),
            ZoneId.of("Asia/Shanghai"),true);
        assertThrows(IllegalArgumentException.class,() -> new SemanticModel.Feature(
            ref("feature.video.active_days"),grain,eventOnly,user,events,"observation_time","available_at"));
        var historical=new TemporalContract(Set.of(TemporalContract.TimeRole.EVENT_TIME,
            TemporalContract.TimeRole.AVAILABLE_AT),ZoneId.of("Asia/Shanghai"),true);
        assertNotNull(new SemanticModel.Feature(ref("feature.video.active_days"),grain,
            historical,user,events,"observation_time","available_at"));
    }

    @Test void functionsAndActionsCannotBeConflated() {
        var function=new FunctionDefinition(ref("function.video.query_dau"),dau,
            "metric.query",List.of(new FunctionDefinition.Parameter(
                "date",ObjectType.DataType.DATE,true)),"schema.metric_result",
            "adapter.bi.query",Set.of("assertion.quality_ready"));
        assertInstanceOf(AweDefinition.class,function);
        assertThrows(IllegalArgumentException.class,() ->
            new ActionDefinition(ref("action.video.rerun"),ref("pipeline.video.daily"),
                "pipeline.rerun",ActionDefinition.SideEffect.PIPELINE_RERUN,
                "adapter.scheduler.rerun","policy.pipeline.write","idempotency.job.date",true,
                "",Set.of("assertion.partition_ready")));
        var action=new ActionDefinition(ref("action.video.rerun"),ref("pipeline.video.daily"),
                "pipeline.rerun",ActionDefinition.SideEffect.PIPELINE_RERUN,
                "adapter.scheduler.rerun","policy.pipeline.write","idempotency.job.date",
                true,"compensation.restore_partition",Set.of("assertion.partition_ready"));
        assertNotEquals(function.getClass(),action.getClass());
    }

    @Test void contractNeedsActualEnforcementObligations() {
        assertThrows(IllegalArgumentException.class,() ->
            new ContractDefinition(ref("contract.video.dau"),dau,
                ContractDefinition.Kind.SEMANTIC,"video_dw",List.of()));
        var contract=new ContractDefinition(ref("contract.video.dau"),dau,
                ContractDefinition.Kind.SEMANTIC,"video_dw",
                List.of(new ContractDefinition.Obligation("check.join_fanout",
                    ContractDefinition.Enforcement.PLAN,true)));
        assertEquals(ContractDefinition.Enforcement.PLAN,contract.obligations().getFirst().at());
    }

    @Test void evidenceNeverPassesWithoutInspectableProof() {
        var id=ref("assertion.video.dau_quality");
        assertThrows(IllegalArgumentException.class,() ->
            new EvidenceAssertion(id,dau,EvidenceAssertion.Kind.QUALITY,
                EvidenceAssertion.Result.PASSED,"checker.quality",
                "hash:model-v1","snapshot:events-2026-11-11",Instant.now(),
                List.of(),List.of()));
        var evidence=new EvidenceAssertion(id,dau,EvidenceAssertion.Kind.QUALITY,
            EvidenceAssertion.Result.INCONCLUSIVE,"checker.quality",
            "hash:model-v1","snapshot:events-2026-11-11",Instant.now(),
            List.of(),List.of("SDK data capture was not independently reconciled"));
        assertEquals(EvidenceAssertion.Result.INCONCLUSIVE,evidence.result());
    }

    @Test void sevenMetamodelFamiliesAreAvailableAsOneClosedTypeSystem() {
        var permitted=Set.of(AweDefinition.class.getPermittedSubclasses());
        assertEquals(7,permitted.size());
        assertTrue(permitted.contains(SemanticModel.class));
        assertTrue(permitted.contains(ActionDefinition.class));
    }
}
