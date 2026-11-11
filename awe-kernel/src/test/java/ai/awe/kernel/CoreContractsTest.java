package ai.awe.kernel;
import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.List; import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class CoreContractsTest {
    private final EntityRef dau = new EntityRef("metric.video.dau", "1.0.0");
    private final EntityRef data = new EntityRef("dataset.video.activity", "1.0.0");

    @Test void identityIsCanonicalAndVersioned() {
        assertThrows(IllegalArgumentException.class, () -> new EntityRef("DAU", "1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> new EntityRef("metric.video.dau", "latest"));
    }
    @Test void grainRejectsDuplicateAndMissingKeys() {
        assertThrows(IllegalArgumentException.class, () -> new Grain(List.of(), Grain.GrainKind.FACT));
        assertThrows(IllegalArgumentException.class, () -> new Grain(List.of("user_id","user_id"), Grain.GrainKind.FACT));
    }
    @Test void joinNeedsCardinalityAndTemporalContract() {
        assertFalse(new RelationContract(data,dau,"DIMENSION_OF","mapping.user",
            RelationContract.Cardinality.ONE_TO_MANY, RelationContract.TemporalPolicy.AS_OF,true).safeForNaiveMeasureJoin());
        assertFalse(new RelationContract(data,dau,"DIMENSION_OF","mapping.user",
            RelationContract.Cardinality.MANY_TO_ONE, RelationContract.TemporalPolicy.NOT_DEFINED,true).safeForNaiveMeasureJoin());
        assertTrue(new RelationContract(data,dau,"DIMENSION_OF","mapping.user",
            RelationContract.Cardinality.MANY_TO_ONE, RelationContract.TemporalPolicy.AS_OF,true).safeForNaiveMeasureJoin());
    }
    @Test void dailyDistinctCannotBeAddedToWeeklyDistinct() {
        assertFalse(new AggregationContract(AggregationContract.Kind.COUNT_DISTINCT,
            Set.of(),Set.of("day")).maySumPreaggregatedValuesOver("day"));
    }
    @Test void historyNeedsKnowledgeTimeNotJustEventTime() {
        assertFalse(new TemporalContract(Set.of(TemporalContract.TimeRole.EVENT_TIME),
            ZoneId.of("Asia/Shanghai"),true).supportsHistoricalAsOf());
        assertTrue(new TemporalContract(Set.of(TemporalContract.TimeRole.EVENT_TIME,
            TemporalContract.TimeRole.AVAILABLE_AT),ZoneId.of("Asia/Shanghai"),true).supportsHistoricalAsOf());
    }
    @Test void missingOnlyInsideCoverageScope() {
        var d = new CapabilityDecision(CapabilityDecision.Resolution.RESOLVED,
           CapabilityDecision.Supply.MISSING, CapabilityDecision.Authorization.ALLOWED,
           CapabilityDecision.Readiness.UNKNOWN,List.of());
        assertFalse(d.requiresGapReview(false));
        assertTrue(d.requiresGapReview(true));
    }
    @Test void runtimeAndSupplyIndependent() {
        var d = new CapabilityDecision(CapabilityDecision.Resolution.RESOLVED,
           CapabilityDecision.Supply.DERIVABLE, CapabilityDecision.Authorization.ALLOWED,
           CapabilityDecision.Readiness.NOT_READY,List.of());
        assertFalse(d.mayPlanReadOnly());
    }
    @Test void zeroIsNotMissing() {
        assertNotEquals(ResultValueState.ZERO,ResultValueState.NOT_READY);
        assertNotEquals(ResultValueState.NO_RECORD,ResultValueState.ACCESS_WITHHELD);
    }
}