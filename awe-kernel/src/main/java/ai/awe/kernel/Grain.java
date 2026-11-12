package ai.awe.kernel;
import java.util.List; import java.util.Objects; import java.util.Set;
/** A logical record's identity. Enforcing uniqueness on data is a separate runtime assertion. */
public record Grain(List<String> keys, GrainKind kind) {
    public enum GrainKind { EVENT, ENTITY_SNAPSHOT, FACT, AGGREGATE, FEATURE }
    public Grain {
        Objects.requireNonNull(keys); Objects.requireNonNull(kind);
        keys = List.copyOf(keys);
        if (keys.isEmpty() || keys.stream().anyMatch(String::isBlank)
            || Set.copyOf(keys).size() != keys.size())
            throw new IllegalArgumentException("Grain keys must be nonempty, unique and nonblank");
    }
}