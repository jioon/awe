package ai.awe.registry.model;

import ai.awe.kernel.identity.EntityRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;

/** 可信生产实现提供已核验绑定；只含 sourceSystem/externalRef/fingerprint/timeFields，不改原发布。 */
public interface LogicalDatasetBindings {
    record RealizationProof(
            EntityRef dataset,
            UUID datasetReleaseId,
            String datasetContentHash,
            EntityRef rowSchema,
            UUID rowSchemaReleaseId,
            String rowSchemaContentHash,
            UUID realizationId,
            UUID consumptionId,
            String artifactHash,
            String deploymentRef,
            String runRef,
            String attemptRef,
            UUID sourceSnapshotId,
            JsonNode binding) {
        public RealizationProof {
            java.util.Objects.requireNonNull(dataset);
            java.util.Objects.requireNonNull(datasetReleaseId);
            java.util.Objects.requireNonNull(rowSchema);
            java.util.Objects.requireNonNull(rowSchemaReleaseId);
            java.util.Objects.requireNonNull(realizationId);
            java.util.Objects.requireNonNull(consumptionId);
            java.util.Objects.requireNonNull(sourceSnapshotId);
            if (datasetContentHash == null
                    || !datasetContentHash.matches("[a-f0-9]{64}")
                    || rowSchemaContentHash == null
                    || !rowSchemaContentHash.matches("[a-f0-9]{64}")
                    || artifactHash == null
                    || !artifactHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException(
                        "Realization proof requires exact content hashes");
            for (String value : java.util.List.of(deploymentRef, runRef, attemptRef))
                if (value == null || value.isBlank() || value.length() > 240)
                    throw new IllegalArgumentException(
                            "Realization proof requires bounded platform identities");
            binding = java.util.Objects.requireNonNull(binding).deepCopy();
        }

        @Override
        public JsonNode binding() {
            return binding.deepCopy();
        }
    }

    Optional<JsonNode> binding(EntityRef dataset);

    default Optional<RealizationProof> realization(EntityRef dataset) {
        return Optional.empty();
    }
}
