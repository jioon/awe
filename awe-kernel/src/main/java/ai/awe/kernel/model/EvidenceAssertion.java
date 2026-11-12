package ai.awe.kernel.model;

import ai.awe.kernel.identity.EntityRef;
import java.time.Instant;
import java.util.*;

/**
 * Scoped proof about a specific claim and source snapshot. PASS is not global truth and governance
 * certification of a model is not evidence for a correct business fact.
 */
public record EvidenceAssertion(
        EntityRef ref,
        EntityRef subject,
        Kind kind,
        Result result,
        String checkerRef,
        String modelReleaseHash,
        String sourceSnapshotRef,
        Instant checkedAt,
        List<String> evidenceRefs,
        List<String> limitations)
        implements AweDefinition {
    public enum Kind {
        SCHEMA,
        GRAIN,
        TEMPORAL,
        JOIN_SAFETY,
        QUALITY,
        SOURCE_RECONCILIATION,
        SEMANTIC_EQUIVALENCE,
        FEATURE_POINT_IN_TIME,
        BUSINESS_OBSERVATION
    }

    public enum Result {
        PASSED,
        FAILED,
        NOT_RUN,
        INCONCLUSIVE
    }

    public EvidenceAssertion {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(result);
        Objects.requireNonNull(checkedAt);
        for (String s : new String[] {checkerRef, modelReleaseHash, sourceSnapshotRef})
            if (s == null || s.isBlank())
                throw new IllegalArgumentException(
                        "Evidence must be scoped to checker/release/snapshot");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs));
        limitations = List.copyOf(Objects.requireNonNull(limitations));
        if (result == Result.PASSED && evidenceRefs.isEmpty())
            throw new IllegalArgumentException("PASSED requires inspectable evidence");
    }
}
