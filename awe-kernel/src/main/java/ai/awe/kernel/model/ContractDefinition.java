package ai.awe.kernel.model;

import ai.awe.kernel.identity.EntityRef;
import java.util.*;

/**
 * Versioned enforceable producer/consumer obligation; keep governance and runtime claims separate.
 */
public record ContractDefinition(
        EntityRef ref, EntityRef subject, Kind kind, String ownerTeam, List<Obligation> obligations)
        implements AweDefinition {
    public enum Kind {
        DATA,
        SEMANTIC,
        CAPABILITY,
        QUALITY,
        ACCESS,
        CONSUMPTION
    }

    public enum Enforcement {
        AUTHORING,
        PUBLISH,
        PLAN,
        EXECUTE,
        OBSERVE
    }

    public record Obligation(String ruleId, Enforcement at, boolean blocking) {
        public Obligation {
            if (ruleId == null || ruleId.isBlank())
                throw new IllegalArgumentException("Rule reference required");
            Objects.requireNonNull(at);
        }
    }

    public ContractDefinition {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(kind);
        if (ownerTeam == null || ownerTeam.isBlank())
            throw new IllegalArgumentException("Owner required");
        obligations = List.copyOf(Objects.requireNonNull(obligations));
        if (obligations.isEmpty())
            throw new IllegalArgumentException("Contracts require enforceable obligations");
    }
}
