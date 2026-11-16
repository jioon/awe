package ai.awe.registry.production;

import ai.awe.registry.model.ModelCompatibility;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 现有质量/工程平台的版本化回归实现绑定；未知套件不能靠自报 PASSED 代替。 */
@Component
public final class ProductionRegressionSuites {
    public record Suite(
            String pipelineId,
            String suiteId,
            String version,
            String contentHash,
            Set<String> independentCheckers) {
        public Suite {
            independentCheckers = Set.copyOf(independentCheckers);
        }

        public Map<String, Object> descriptor() {
            return Map.of(
                    "pipelineId",
                    pipelineId,
                    "suiteId",
                    suiteId,
                    "version",
                    version,
                    "contentHash",
                    contentHash,
                    "independentCheckers",
                    new TreeSet<>(independentCheckers));
        }
    }

    private final Map<String, Suite> suites;
    private final String hash;

    public ProductionRegressionSuites(Environment env, JsonDocuments json) {
        var parsed = new TreeMap<String, Suite>();
        try {
            String raw = env.getProperty("AWE_PRODUCTION_REGRESSION_SUITES", "");
            if (!raw.isBlank()) {
                if (raw.length() > 262144) throw new IllegalArgumentException();
                JsonNode array = json.parse(raw);
                if (!array.isArray() || array.size() > 256) throw new IllegalArgumentException();
                for (JsonNode entry : array) {
                    ProductionJson.fields(
                            entry,
                            "pipelineId",
                            "suiteId",
                            "version",
                            "contentHash",
                            "independentCheckers");
                    String pipeline = ProductionJson.text(entry, "pipelineId", 240),
                            id = ProductionJson.text(entry, "suiteId", 240),
                            version = ProductionJson.text(entry, "version", 50),
                            digest = ProductionJson.hash(entry, "contentHash");
                    ModelCompatibility.Version.parse(version);
                    if (!pipeline.matches("pipeline(?:\\.[a-z][a-z0-9_-]*){2,}")
                            || !entry.path("independentCheckers").isArray()
                            || entry.path("independentCheckers").isEmpty()
                            || entry.path("independentCheckers").size() > 64)
                        throw new IllegalArgumentException();
                    var checkers = new HashSet<String>();
                    for (JsonNode checker : entry.path("independentCheckers")) {
                        if (!checker.isTextual()
                                || !checker.asText().matches("[A-Za-z0-9][A-Za-z0-9._:@-]{0,239}")
                                || !checkers.add(checker.asText()))
                            throw new IllegalArgumentException();
                    }
                    if (parsed.put(pipeline, new Suite(pipeline, id, version, digest, checkers))
                            != null) throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException ex) {
            parsed.clear();
        }
        suites = Map.copyOf(parsed);
        hash =
                json.sha256(
                        json.parse(
                                json.write(
                                        parsed.values().stream().map(Suite::descriptor).toList())));
    }

    public Optional<Suite> find(String pipelineId) {
        return Optional.ofNullable(suites.get(pipelineId));
    }

    public String configurationHash() {
        return hash;
    }
}
