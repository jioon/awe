package ai.awe.kernel;

import java.util.*;

/** Side-effecting capability, never conflated with a read-only Function. */
public record ActionDefinition(EntityRef ref, EntityRef ownerType, String operation,
        SideEffect sideEffect, String executorBindingRef, String authorizationPolicyRef,
        String idempotencyKeySpec, boolean requiresApproval, String rollbackOrCompensationRef,
        Set<String> postconditionAssertions) implements AweDefinition {
    public enum SideEffect { MODEL_PUBLISH, PIPELINE_RERUN, DATA_BACKFILL, DATA_MUTATION }
    public ActionDefinition {
        Objects.requireNonNull(ref);Objects.requireNonNull(ownerType);
        Objects.requireNonNull(sideEffect);
        for(String s:new String[]{operation,executorBindingRef,authorizationPolicyRef,idempotencyKeySpec})
            if(s==null || s.isBlank()) throw new IllegalArgumentException("Action operation/executor/policy/idempotency required");
        if(rollbackOrCompensationRef==null || rollbackOrCompensationRef.isBlank())
            throw new IllegalArgumentException("Action requires rollback or documented compensation");
        postconditionAssertions=Set.copyOf(Objects.requireNonNull(postconditionAssertions));
        if(postconditionAssertions.isEmpty())
            throw new IllegalArgumentException("Action requires postcondition verification");
    }
}
