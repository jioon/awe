package ai.awe.kernel;
import java.util.Objects;
/** Type-aware relationship: an edge without a cardinality/time rule is not a safe join. */
public record RelationContract(EntityRef source, EntityRef target, String relationType,
    String identityMappingRef, Cardinality cardinality, TemporalPolicy temporalPolicy,
    boolean certified) {
    public enum Cardinality { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY, UNKNOWN }
    public enum TemporalPolicy { SAME_SNAPSHOT, AS_OF, TIME_INDEPENDENT, NOT_DEFINED }
    public RelationContract {
        Objects.requireNonNull(source); Objects.requireNonNull(target);
        Objects.requireNonNull(cardinality); Objects.requireNonNull(temporalPolicy);
        if (relationType == null || relationType.isBlank()) throw new IllegalArgumentException("relation type required");
        if (identityMappingRef == null || identityMappingRef.isBlank())
            throw new IllegalArgumentException("approved identity mapping ref required");
    }
    /** A conservative check: real compiler must also validate grain and policies. */
    public boolean safeForNaiveMeasureJoin() {
        return certified && (cardinality == Cardinality.ONE_TO_ONE
              || cardinality == Cardinality.MANY_TO_ONE)
              && temporalPolicy != TemporalPolicy.NOT_DEFINED;
    }
}