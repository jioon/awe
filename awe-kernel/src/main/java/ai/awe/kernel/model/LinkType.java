package ai.awe.kernel.model;

import ai.awe.kernel.contract.RelationContract;
import ai.awe.kernel.identity.EntityRef;
import java.util.Objects;

/**
 * A typed relation definition, distinct from physical joins. Semantic relationships can exist
 * without being safe or even meaningful SQL joins.
 */
public record LinkType(
        EntityRef ref,
        EntityRef fromType,
        EntityRef toType,
        Meaning meaning,
        RelationContract.Cardinality cardinality,
        RelationContract.TemporalPolicy temporalPolicy,
        String identityMappingRef,
        boolean certified)
        implements AweDefinition {
    public enum Meaning {
        PARTICIPATES_IN,
        HAS_DIMENSION,
        DERIVED_FROM,
        PRODUCED_BY,
        DEPENDS_ON,
        IMPLEMENTED_BY,
        HAS_FEATURE,
        DOMAIN_SPECIFIC
    }

    public LinkType {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(fromType);
        Objects.requireNonNull(toType);
        Objects.requireNonNull(meaning);
        Objects.requireNonNull(cardinality);
        Objects.requireNonNull(temporalPolicy);
        if (identityMappingRef == null || identityMappingRef.isBlank())
            throw new IllegalArgumentException("Identity/relationship mapping reference required");
    }

    /**
     * This is a necessary but not sufficient condition for naive measure joins. The compiler must
     * additionally check source grain, uniqueness, aggregation and policy.
     */
    public boolean potentiallyFanoutSafe() {
        return certified
                && (cardinality == RelationContract.Cardinality.ONE_TO_ONE
                        || cardinality == RelationContract.Cardinality.MANY_TO_ONE)
                && temporalPolicy != RelationContract.TemporalPolicy.NOT_DEFINED;
    }
}
