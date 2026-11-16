package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.DatasetContract;
import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.model.LogicalDatasetBindings;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 服务端从不可变发布清单解析的实际读取来源；不是客户端可自授的扫描节点。 */
public record BoundQuerySource(
        String alias,
        SemanticModel.Dataset dataset,
        ObjectType rowSchema,
        JsonNode binding,
        UUID releaseId,
        String contentHash,
        Optional<DatasetContract> datasetContract,
        Optional<LogicalDatasetBindings.RealizationProof> realization) {
    public BoundQuerySource(
            String alias,
            SemanticModel.Dataset dataset,
            ObjectType rowSchema,
            JsonNode binding,
            UUID releaseId,
            String contentHash,
            Optional<DatasetContract> datasetContract) {
        this(
                alias,
                dataset,
                rowSchema,
                binding,
                releaseId,
                contentHash,
                datasetContract,
                Optional.empty());
    }

    public BoundQuerySource(
            String alias,
            SemanticModel.Dataset dataset,
            ObjectType rowSchema,
            JsonNode binding,
            UUID releaseId,
            String contentHash) {
        this(
                alias,
                dataset,
                rowSchema,
                binding,
                releaseId,
                contentHash,
                Optional.empty(),
                Optional.empty());
    }

    public BoundQuerySource {
        if (alias == null || !alias.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid source alias");
        Objects.requireNonNull(dataset);
        Objects.requireNonNull(rowSchema);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(releaseId);
        if (contentHash == null || !contentHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Bound source requires an exact release hash");
        binding = binding.deepCopy();
        Objects.requireNonNull(datasetContract);
        Objects.requireNonNull(realization);
    }

    public boolean staticSnapshot() {
        return datasetContract
                .map(c -> c.readMode() == DatasetContract.ReadMode.STATIC_SNAPSHOT)
                .orElse(false);
    }

    public TemporalContract.TimeRole rangeRole() {
        return datasetContract
                .map(DatasetContract::rangeRole)
                .orElse(TemporalContract.TimeRole.EVENT_TIME);
    }
}
