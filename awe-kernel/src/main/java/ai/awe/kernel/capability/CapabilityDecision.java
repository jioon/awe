package ai.awe.kernel.capability;

import java.util.List;
import java.util.Objects;

/** Feasibility is NOT proof of task completion, readiness or real-world truth. */
public record CapabilityDecision(
        Resolution resolution,
        Supply supply,
        Authorization authorization,
        Readiness readiness,
        List<Diagnostic> diagnostics) {
    public enum Resolution {
        RESOLVED,
        AMBIGUOUS,
        UNRESOLVED
    }

    public enum Supply {
        DIRECT,
        DERIVABLE,
        MISSING,
        UNKNOWN
    }

    public enum Authorization {
        ALLOWED,
        DENIED,
        UNDETERMINED
    }

    public enum Readiness {
        READY,
        NOT_READY,
        QUALITY_FAILED,
        UNKNOWN
    }

    public record Diagnostic(String code, String message, String evidenceRef) {
        public Diagnostic {
            if (code == null || code.isBlank())
                throw new IllegalArgumentException("Diagnostic code");
            Objects.requireNonNull(message);
        }
    }

    public CapabilityDecision {
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(supply);
        Objects.requireNonNull(authorization);
        Objects.requireNonNull(readiness);
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics));
    }

    /** Planning only, never unconditional permission to execute. */
    public boolean mayPlanReadOnly() {
        return resolution == Resolution.RESOLVED
                && (supply == Supply.DIRECT || supply == Supply.DERIVABLE)
                && authorization == Authorization.ALLOWED
                && readiness == Readiness.READY;
    }

    /** MISSING can only become a gap candidate inside a declared complete coverage scope. */
    public boolean requiresGapReview(boolean certifiedCoverageComplete) {
        return certifiedCoverageComplete
                && resolution == Resolution.RESOLVED
                && supply == Supply.MISSING
                && authorization != Authorization.DENIED;
    }
}
