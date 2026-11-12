package ai.awe.registry.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Read-only authoritative metadata adapter. Importing observations never certifies business semantics. */
public interface SourceAdapter {
    String system();
    boolean configured();
    Fetch fetch(String externalRef);

    record Fetch(String status, String sourceVersion, JsonNode metadata, List<String> limitations) {
        public Fetch { limitations = List.copyOf(limitations); }
        public static Fetch unavailable(String status, String limitation) {
            return new Fetch(status, "", null, List.of(limitation));
        }
    }
}
