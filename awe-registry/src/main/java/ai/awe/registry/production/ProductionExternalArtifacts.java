package ai.awe.registry.production;

import ai.awe.registry.model.ModelCompatibility;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 存量代码仅按管理员登记的确切字节及有限验证范围准入，不把回归提升为通用语义等价。 */
@Component
public final class ProductionExternalArtifacts {
    public static final String PROOF_SCOPE = "REGISTERED_EXTERNAL_FINITE_REGRESSION";
    public static final String EXECUTION_MODE = "READ_ONLY_QUERY_DECLARED_TARGETS";

    public record Artifact(
            String pipelineId,
            String modelVersion,
            String artifactId,
            String artifactVersion,
            String mediaType,
            String contentHash,
            String proofScopeRef) {
        public Map<String, Object> descriptor() {
            return Map.of(
                    "pipelineId", pipelineId,
                    "modelVersion", modelVersion,
                    "artifactId", artifactId,
                    "artifactVersion", artifactVersion,
                    "mediaType", mediaType,
                    "contentHash", contentHash,
                    "proofScope", PROOF_SCOPE,
                    "executionMode", EXECUTION_MODE,
                    "proofScopeRef", proofScopeRef);
        }
    }

    private final Map<String, Artifact> artifacts;
    private final String hash;

    public ProductionExternalArtifacts(Environment env, JsonDocuments json) {
        var parsed = new TreeMap<String, Artifact>();
        try {
            String raw = env.getProperty("AWE_PRODUCTION_EXTERNAL_ARTIFACTS", "");
            if (!raw.isBlank()) {
                if (raw.length() > 262144) throw new IllegalArgumentException();
                JsonNode entries = json.parse(raw);
                if (!entries.isArray() || entries.size() > 256)
                    throw new IllegalArgumentException();
                for (JsonNode entry : entries) {
                    ProductionJson.fields(
                            entry,
                            "pipelineId",
                            "modelVersion",
                            "artifactId",
                            "artifactVersion",
                            "mediaType",
                            "contentHash",
                            "proofScopeRef");
                    String pipeline = ProductionJson.text(entry, "pipelineId", 240),
                            version = ProductionJson.text(entry, "modelVersion", 50),
                            id = ProductionJson.text(entry, "artifactId", 240),
                            artifactVersion = ProductionJson.text(entry, "artifactVersion", 240),
                            media = ProductionJson.text(entry, "mediaType", 120),
                            digest = ProductionJson.hash(entry, "contentHash"),
                            scope = ProductionJson.text(entry, "proofScopeRef", 240);
                    ModelCompatibility.Version.parse(version);
                    if (!pipeline.matches("pipeline(?:\\.[a-z][a-z0-9_-]*){2,}")
                            || !media.matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
                            || !scope.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,239}"))
                        throw new IllegalArgumentException();
                    var artifact =
                            new Artifact(
                                    pipeline, version, id, artifactVersion, media, digest, scope);
                    if (parsed.put(pipeline + "@" + version, artifact) != null)
                        throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException malformed) {
            // 配置错误时整组失效；不能静默接受其中一个可解析的候选。
            parsed.clear();
        }
        artifacts = Map.copyOf(parsed);
        hash =
                json.sha256(
                        json.parse(
                                json.write(
                                        parsed.values().stream()
                                                .map(Artifact::descriptor)
                                                .toList())));
    }

    public Optional<Artifact> find(String pipelineId, String modelVersion) {
        return Optional.ofNullable(artifacts.get(pipelineId + "@" + modelVersion));
    }

    static void requireUtf8Text(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 1_000_000
                || value.chars()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                && c != '\n'
                                                && c != '\r'
                                                && c != '\t')
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new IllegalArgumentException(
                    "External artifact must contain bounded exact valid UTF-8 text");
    }

    public String configurationHash() {
        return hash;
    }
}
