package ai.awe.kernel.contract;

import java.util.Objects;
import java.util.Set;

/** Distinct daily metrics cannot be rolled up by summing daily counts. */
public record AggregationContract(
        Kind kind, Set<String> permittedRollupAxes, Set<String> forbiddenRollupAxes) {
    public enum Kind {
        SUM,
        COUNT,
        COUNT_DISTINCT,
        AVG,
        RATIO,
        LAST,
        CUSTOM
    }

    public AggregationContract {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(permittedRollupAxes);
        Objects.requireNonNull(forbiddenRollupAxes);
        permittedRollupAxes = Set.copyOf(permittedRollupAxes);
        forbiddenRollupAxes = Set.copyOf(forbiddenRollupAxes);
        if (!java.util.Collections.disjoint(permittedRollupAxes, forbiddenRollupAxes))
            throw new IllegalArgumentException("Conflicting rollup axes");
    }

    public boolean maySumPreaggregatedValuesOver(String axis) {
        return (kind == Kind.SUM || kind == Kind.COUNT)
                && permittedRollupAxes.contains(axis)
                && !forbiddenRollupAxes.contains(axis);
    }
}
