package ai.awe.kernel.model;

import ai.awe.kernel.identity.EntityRef;
import java.util.*;

/**
 * Side-effect-free, governed capability. Executor binding is a reference, never arbitrary SQL or
 * code supplied by an external agent.
 */
public record FunctionDefinition(
        EntityRef ref,
        EntityRef ownerType,
        String operation,
        List<Parameter> parameters,
        String resultSchemaRef,
        String executorBindingRef,
        Set<String> requiredAssertions)
        implements AweDefinition {
    public record Parameter(String name, ObjectType.DataType type, boolean required) {
        public Parameter {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("Parameter name required");
            Objects.requireNonNull(type);
        }
    }

    public FunctionDefinition {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(ownerType);
        if (operation == null
                || operation.isBlank()
                || resultSchemaRef == null
                || resultSchemaRef.isBlank()
                || executorBindingRef == null
                || executorBindingRef.isBlank())
            throw new IllegalArgumentException("Function operation/result/executor required");
        parameters = List.copyOf(Objects.requireNonNull(parameters));
        requiredAssertions = Set.copyOf(Objects.requireNonNull(requiredAssertions));
        var seen = new HashSet<String>();
        for (var p : parameters)
            if (!seen.add(p.name()))
                throw new IllegalArgumentException("Duplicate function input: " + p.name());
    }
}
