package ai.awe.kernel.contract;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/** Historical truth and what data was known at the time must not be conflated. */
public record TemporalContract(Set<TimeRole> roles, ZoneId businessZone, boolean backfillPossible) {
    public enum TimeRole {
        EVENT_TIME,
        PROCESSING_TIME,
        BUSINESS_DATE,
        VALID_FROM,
        VALID_TO,
        OBSERVED_AT,
        AVAILABLE_AT,
        SYSTEM_TIME
    }

    public TemporalContract {
        Objects.requireNonNull(roles);
        Objects.requireNonNull(businessZone);
        roles = Set.copyOf(roles);
        if (roles.isEmpty()) throw new IllegalArgumentException("Time roles required");
    }

    public boolean supportsHistoricalAsOf() {
        return roles.contains(TimeRole.EVENT_TIME)
                && (roles.contains(TimeRole.AVAILABLE_AT) || roles.contains(TimeRole.SYSTEM_TIME));
    }
}
