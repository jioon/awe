package ai.awe.kernel;

import java.util.*;

/**
 * Domain semantics. Dataset, Metric and Feature deliberately remain distinct despite sharing
 * grain, identity and time, preventing feature PIT requirements from being erased by metrics.
 */
public sealed interface SemanticModel extends AweDefinition
        permits SemanticModel.Dataset, SemanticModel.Metric, SemanticModel.Feature {
    Grain grain();
    TemporalContract time();

    record Dataset(EntityRef ref, Grain grain, TemporalContract time,
                   EntityRef subjectType, Set<EntityRef> dimensions)
            implements SemanticModel {
        public Dataset {
            Objects.requireNonNull(ref);Objects.requireNonNull(grain);
            Objects.requireNonNull(time);Objects.requireNonNull(subjectType);
            dimensions=Set.copyOf(Objects.requireNonNull(dimensions));
        }
    }

    record Metric(EntityRef ref, Grain grain, TemporalContract time,
                  EntityRef subjectType, EntityRef sourceDataset,
                  AggregationContract aggregation, Set<EntityRef> allowedDimensions)
            implements SemanticModel {
        public Metric {
            Objects.requireNonNull(ref);Objects.requireNonNull(grain);
            Objects.requireNonNull(time);Objects.requireNonNull(subjectType);
            Objects.requireNonNull(sourceDataset);Objects.requireNonNull(aggregation);
            allowedDimensions=Set.copyOf(Objects.requireNonNull(allowedDimensions));
        }
        public boolean permitsDimension(EntityRef dimension) {
            return allowedDimensions.contains(dimension);
        }
    }

    record Feature(EntityRef ref, Grain grain, TemporalContract time,
                   EntityRef subjectType, EntityRef sourceDataset,
                   String observationTimeField, String availableAtField)
            implements SemanticModel {
        public Feature {
            Objects.requireNonNull(ref);Objects.requireNonNull(grain);
            Objects.requireNonNull(time);Objects.requireNonNull(subjectType);
            Objects.requireNonNull(sourceDataset);
            if(observationTimeField==null || observationTimeField.isBlank()
                    || availableAtField==null || availableAtField.isBlank())
                throw new IllegalArgumentException("Feature requires observation and availability timestamps");
            if(!time.supportsHistoricalAsOf())
                throw new IllegalArgumentException("Feature must support availability-aware historical as-of");
        }
    }
}
