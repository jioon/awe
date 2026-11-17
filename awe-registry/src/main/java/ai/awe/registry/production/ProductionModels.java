package ai.awe.registry.production;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.*;
import ai.awe.registry.authoring.ModelRegistry;
import ai.awe.registry.model.LogicalDatasetBindings;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 以发布清单构造唯一加工制品；不从平台自报 planHash 推断代码遵守模型。 */
@Component
public final class ProductionModels {
    public static final String ARTIFACT_FORMAT = "AWE_PIPELINE_PLAN_V1";

    public record Plan(
            Map<String, Object> pipeline,
            PipelineContract contract,
            JsonNode artifact,
            String artifactHash,
            List<Map<String, Object>> dependencies,
            List<Target> targets,
            Optional<ProductionExternalArtifacts.Artifact> externalArtifact) {
        public Plan {
            dependencies = List.copyOf(dependencies);
            targets = List.copyOf(targets);
            externalArtifact = Objects.requireNonNull(externalArtifact);
        }

        public boolean external() {
            return contract.mode() == PipelineContract.Mode.REGISTERED_EXTERNAL;
        }

        public String candidateHash() {
            return external()
                    ? externalArtifact
                            .map(ProductionExternalArtifacts.Artifact::contentHash)
                            .orElse("")
                    : artifactHash;
        }

        public String proofScope() {
            return external()
                    ? ProductionExternalArtifacts.PROOF_SCOPE
                    : "REGISTERED_MODEL_COMPILED_ARTIFACT_AND_PINNED_REGRESSION_SUITE";
        }
    }

    public record Target(
            Map<String, Object> dataset, Map<String, Object> rowSchema, DatasetContract contract) {}

    private final ModelRegistry registry;
    private final JsonDocuments json;
    private final ProductionTargets targets;
    private final ObjectProvider<LogicalDatasetBindings> logicalBindings;
    private final TypedModels typed = new TypedModels();
    private final ProductionExternalArtifacts externalArtifacts;

    public ProductionModels(
            ModelRegistry registry,
            JsonDocuments json,
            ProductionTargets targets,
            ObjectProvider<LogicalDatasetBindings> logicalBindings,
            ProductionExternalArtifacts externalArtifacts) {
        this.registry = registry;
        this.json = json;
        this.targets = targets;
        this.logicalBindings = logicalBindings;
        this.externalArtifacts = externalArtifacts;
    }

    public Plan plan(UUID id, String hash) {
        var pipeline = release(id, hash);
        if (!"ObjectType".equals(pipeline.get("entity_type")))
            throw new IllegalArgumentException(
                    "Production candidate must be a typed Pipeline release");
        JsonNode spec = (JsonNode) pipeline.get("spec");
        var base = (ObjectType) typed.decode("ObjectType", spec);
        if (base.family() != ObjectType.Family.PIPELINE || spec.path("schemaVersion").asInt() != 3)
            throw new IllegalArgumentException(
                    "Production candidate requires a v3 Pipeline contract");
        PipelineContract contract = typed.pipelineContract(spec);
        var dependencies = closure(pipeline);
        if (contract.mode() == PipelineContract.Mode.REGISTERED_EXTERNAL) {
            // 首个外部执行 profile 只接受无参静态快照；行/时间受限读能力须先实现源端隔离。
            for (var source : contract.sources()) {
                var read = dependency(pipeline, source.readFunction(), "FunctionDefinition");
                var function =
                        (FunctionDefinition)
                                typed.decode("FunctionDefinition", (JsonNode) read.get("spec"));
                var dataset = dependency(read, function.ownerType(), "Dataset");
                if (!function.operation().equals("dataset.snapshot.v1")
                        || !function.executorBindingRef().equals("postgres.dataset.snapshot.v1")
                        || !function.parameters().isEmpty()
                        || !function.requiredAssertions().isEmpty()
                        || typed.datasetContract((JsonNode) dataset.get("spec")).readMode()
                                != DatasetContract.ReadMode.STATIC_SNAPSHOT)
                    throw new IllegalArgumentException(
                            "The external read-only profile currently supports only parameter-free PostgreSQL STATIC_SNAPSHOT capabilities; temporal or row-constrained capabilities require a separately implemented enforced profile");
            }
        }
        var resolvedTargets = new ArrayList<Target>();
        for (var target : contract.targets()) {
            var dataset = dependency(pipeline, target.dataset(), "Dataset");
            var schema = dependency(pipeline, target.rowSchema(), "ObjectType");
            var datasetContract = typed.datasetContract((JsonNode) dataset.get("spec"));
            if (!datasetContract.rowSchema().equals(target.rowSchema()))
                throw new IllegalArgumentException(
                        "Pipeline output row schema differs from the target Dataset contract");
            if (datasetContract.mode() != DatasetContract.Mode.LOGICAL_TARGET)
                throw new IllegalArgumentException(
                        "Production output must be a declared logical target, not an arbitrary existing source");
            resolvedTargets.add(new Target(dataset, schema, datasetContract));
        }
        JsonNode artifact =
                json.parse(
                        json.write(
                                Map.of(
                                        "format",
                                        ARTIFACT_FORMAT,
                                        "pipeline",
                                        pointer(pipeline),
                                        "pipelineContract",
                                        typed.encodeValue(contract),
                                        "dependencies",
                                        dependencies.stream()
                                                .map(ProductionModels::pointer)
                                                .toList())));
        return new Plan(
                pipeline,
                contract,
                artifact,
                json.sha256(artifact),
                dependencies,
                resolvedTargets,
                contract.mode() == PipelineContract.Mode.REGISTERED_EXTERNAL
                        ? externalArtifacts.find(
                                (String) pipeline.get("entity_id"),
                                (String) pipeline.get("version"))
                        : Optional.empty());
    }

    public String externalArtifactConfigurationHash() {
        return externalArtifacts.configurationHash();
    }

    public Map<String, Object> release(UUID id, String hash) {
        var release = registry.release(id);
        if (hash != null && !hash.equals(release.get("content_hash")))
            throw new IllegalArgumentException(
                    "Pinned production model content hash does not match release");
        return release;
    }

    public Map<String, Object> dependency(
            Map<String, Object> owner, EntityRef reference, String type) {
        var relations = (List<Map<String, Object>>) owner.get("relations");
        var edge =
                relations.stream()
                        .filter(
                                r ->
                                        reference.id().equals(r.get("target_entity_id"))
                                                && reference
                                                        .modelVersion()
                                                        .equals(r.get("target_version")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Production dependency is absent from the immutable release manifest"));
        var release =
                release(
                        UUID.fromString((String) edge.get("target_release_id")),
                        (String) edge.get("target_content_hash"));
        if (!reference.id().equals(release.get("entity_id"))
                || !reference.modelVersion().equals(release.get("version"))
                || !type.equals(release.get("entity_type")))
            throw new IllegalArgumentException(
                    "Production dependency identity or type differs from manifest");
        return release;
    }

    public List<Map<String, Object>> closure(Map<String, Object> pipeline) {
        var pending = new ArrayDeque<Map<String, Object>>();
        pending.add(pipeline);
        var seen = new TreeMap<String, Map<String, Object>>();
        while (!pending.isEmpty()) {
            var release = pending.removeFirst();
            if (seen.putIfAbsent((String) release.get("id"), release) != null) continue;
            if (seen.size() > 256)
                throw new IllegalStateException(
                        "Pipeline dependency closure exceeds the complete verification budget");
            for (var relation : (List<Map<String, Object>>) release.get("relations")) {
                if (relation.get("target_release_id") == null
                        || relation.get("target_content_hash") == null)
                    throw new IllegalArgumentException(
                            "Legacy dependency manifest cannot be used for production admission");
                pending.add(
                        release(
                                UUID.fromString((String) relation.get("target_release_id")),
                                (String) relation.get("target_content_hash")));
            }
        }
        seen.remove(pipeline.get("id"));
        return List.copyOf(seen.values());
    }

    public Set<String> changedEntities(Plan plan) {
        var result = new TreeSet<String>();
        plan.targets().forEach(t -> result.add((String) t.dataset().get("entity_id")));
        return Set.copyOf(result);
    }

    public Set<ProductionImpactGraph.SourceKey> physicalTargets(Plan plan) {
        var result = new HashSet<ProductionImpactGraph.SourceKey>();
        for (var target : plan.targets()) {
            String ref = target.contract().targetBindingRef().orElseThrow();
            targets.find(ref)
                    .ifPresent(
                            binding ->
                                    result.add(
                                            new ProductionImpactGraph.SourceKey(
                                                    binding.path("sourceSystem").asText(),
                                                    binding.path("externalRef").asText())));
            for (var binding : (List<Map<String, Object>>) target.dataset().get("bindings"))
                result.add(
                        new ProductionImpactGraph.SourceKey(
                                (String) binding.get("source_system"),
                                (String) binding.get("external_ref")));
        }
        return Set.copyOf(result);
    }

    public List<JsonNode> sourceBindings(Plan plan) {
        var result = new ArrayList<JsonNode>();
        var seen = new HashSet<String>();
        for (var source : plan.contract().sources()) {
            var functionRelease =
                    dependency(plan.pipeline(), source.readFunction(), "FunctionDefinition");
            var function =
                    (FunctionDefinition)
                            typed.decode(
                                    "FunctionDefinition", (JsonNode) functionRelease.get("spec"));
            var release = dependency(functionRelease, function.ownerType(), "Dataset");
            JsonNode spec = (JsonNode) release.get("spec");
            var bindings = new ArrayList<JsonNode>();
            spec.path("bindings").forEach(bindings::add);
            if (bindings.isEmpty() && spec.path("schemaVersion").asInt() == 3) {
                var logical = typed.datasetContract(spec);
                String bindingRef =
                        logical.targetBindingRef()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Logical source has no registered target reference"));
                JsonNode configured = targets.require(bindingRef);
                var provider = logicalBindings.getIfAvailable();
                var effective =
                        provider == null
                                ? Optional.<JsonNode>empty()
                                : provider.binding(
                                        new EntityRef(
                                                (String) release.get("entity_id"),
                                                (String) release.get("version")));
                if (effective.isPresent()) {
                    JsonNode binding = effective.get();
                    ObjectNode withoutFingerprint = ((ObjectNode) binding).deepCopy();
                    withoutFingerprint.remove("fingerprint");
                    if (!withoutFingerprint.equals(configured))
                        throw new IllegalStateException(
                                "NOT_CONFIGURED: logical input realization no longer matches its registered target mapping");
                    bindings.add(binding);
                }
            }
            if (bindings.isEmpty())
                throw new IllegalStateException(
                        "NOT_CONFIGURED: pipeline input has no observed source or trusted realization");
            for (var binding : bindings)
                if (seen.add(
                        binding.path("sourceSystem").asText()
                                + "\u0000"
                                + binding.path("externalRef").asText()
                                + "\u0000"
                                + binding.path("fingerprint").asText()))
                    result.add(binding.deepCopy());
        }
        return List.copyOf(result);
    }

    public static Map<String, Object> pointer(Map<String, Object> release) {
        return Map.of(
                "releaseId",
                release.get("id"),
                "entityId",
                release.get("entity_id"),
                "modelVersion",
                release.get("version"),
                "contentHash",
                release.get("content_hash"));
    }
}
