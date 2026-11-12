package ai.awe.registry.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.*;

/** Conservative semantic compatibility, independent from approval and physical readiness. */
public final class ModelCompatibility {
    public record Version(BigInteger major, BigInteger minor, BigInteger patch)
            implements Comparable<Version> {
        public static Version parse(String value) {
            if (value == null
                    || !value.matches(
                            "(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"))
                throw new IllegalArgumentException("version must be canonical major.minor.patch");
            var p = value.split("\\.");
            return new Version(new BigInteger(p[0]), new BigInteger(p[1]), new BigInteger(p[2]));
        }

        public int compareTo(Version other) {
            int m = major.compareTo(other.major);
            if (m != 0) return m;
            int n = minor.compareTo(other.minor);
            return n != 0 ? n : patch.compareTo(other.patch);
        }
    }

    public Map<String, Object> compare(String type, JsonNode previous, JsonNode candidate) {
        var reasons = new ArrayList<String>();
        if (previous == null) return report(reasons);
        if (previous.path("schemaVersion").asInt() != 2) {
            reasons.add("Legacy release requires an explicitly reviewed major-version migration");
            return report(reasons);
        }
        JsonNode before = withoutVersion(previous.path("model")),
                after = withoutVersion(candidate.path("model"));
        if ("ObjectType".equals(type)) {
            var oldProperties = properties(before);
            var newProperties = properties(after);
            for (var e : oldProperties.entrySet())
                if (!e.getValue().equals(newProperties.get(e.getKey())))
                    reasons.add("Removed or changed object property: " + e.getKey());
            for (var e : newProperties.entrySet())
                if (!oldProperties.containsKey(e.getKey())
                        && e.getValue().path("required").asBoolean())
                    reasons.add("New required property: " + e.getKey());
            var a = ((ObjectNode) before).deepCopy();
            var b = ((ObjectNode) after).deepCopy();
            a.remove(List.of("properties", "description"));
            b.remove(List.of("properties", "description"));
            if (!a.equals(b)) reasons.add("Object family or identity contract changed");
        } else if (!before.equals(after)) reasons.add("Typed semantic/capability contract changed");
        if (!previous.path("bindings").equals(candidate.path("bindings")))
            reasons.add("Physical implementation binding changed");
        if (!previous.path("dependsOn").equals(candidate.path("dependsOn")))
            reasons.add("Pinned dependency manifest changed");
        return report(reasons);
    }

    private JsonNode withoutVersion(JsonNode model) {
        var result = model.deepCopy();
        if (result.path("ref") instanceof ObjectNode ref) ref.remove("modelVersion");
        return result;
    }

    private Map<String, JsonNode> properties(JsonNode model) {
        var values = new TreeMap<String, JsonNode>();
        model.path("properties").forEach(p -> values.put(p.path("name").asText(), p));
        return values;
    }

    private Map<String, Object> report(List<String> reasons) {
        return Map.of(
                "compatible",
                reasons.isEmpty(),
                "breaking",
                !reasons.isEmpty(),
                "reasons",
                reasons);
    }
}
