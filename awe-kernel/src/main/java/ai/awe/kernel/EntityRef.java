package ai.awe.kernel;
import java.util.Objects;
import java.util.regex.Pattern;
/** Canonical identity, independent of aliases and physical storage. */
public record EntityRef(String id, String modelVersion) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*){2,}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?");
    public EntityRef {
        Objects.requireNonNull(id); Objects.requireNonNull(modelVersion);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Non-canonical entity id: " + id);
        if (!VERSION.matcher(modelVersion).matches()) throw new IllegalArgumentException("Invalid model version: " + modelVersion);
    }
}