package ai.awe.registry.consumption.query;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.*;
import ai.awe.registry.model.LogicalDatasetBindings;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import ai.awe.registry.support.JsonDocuments;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class LogicalSourceRealizationTest {
    private final JsonDocuments json = new JsonDocuments();
    private final UUID releaseId = UUID.randomUUID();
    private final ObjectType schema =
            new ObjectType(
                    new EntityRef("dataset.test.output_rows", "1.0.0"),
                    ObjectType.Family.DATASET,
                    "Realized rows",
                    List.of(
                            new ObjectType.Property(
                                    "event_id", ObjectType.DataType.STRING, true, false),
                            new ObjectType.Property(
                                    "event_time", ObjectType.DataType.TIMESTAMP, true, false)),
                    List.of("event_id"));
    private final SemanticModel.Dataset dataset =
            new SemanticModel.Dataset(
                    new EntityRef("dataset.test.logical_output", "1.0.0"),
                    new Grain(List.of("event_id"), Grain.GrainKind.EVENT),
                    new TemporalContract(
                            Set.of(TemporalContract.TimeRole.EVENT_TIME), ZoneId.of("UTC"), false),
                    new EntityRef("entity.test.event", "1.0.0"),
                    Set.of());

    private BoundQuerySource source(Optional<LogicalDatasetBindings.RealizationProof> proof) {
        var binding =
                json.object(
                        "{\"sourceSystem\":\"postgres\",\"externalRef\":\"real.output\",\"fingerprint\":\""
                                + "a".repeat(64)
                                + "\",\"timeFields\":{\"EVENT_TIME\":\"event_time\"}}");
        var facet =
                new DatasetContract(
                        schema.ref(),
                        DatasetContract.Mode.LOGICAL_TARGET,
                        Optional.of("target.test"),
                        DatasetContract.ReadMode.EVENT_WINDOW,
                        TemporalContract.TimeRole.EVENT_TIME);
        return new BoundQuerySource(
                "out",
                dataset,
                schema,
                binding,
                releaseId,
                "b".repeat(64),
                Optional.of(facet),
                proof);
    }

    private LogicalDatasetBindings.RealizationProof proof() {
        return new LogicalDatasetBindings.RealizationProof(
                dataset.ref(),
                releaseId,
                "b".repeat(64),
                schema.ref(),
                UUID.randomUUID(),
                "c".repeat(64),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "d".repeat(64),
                "deploy-7",
                "run-8",
                "attempt-2",
                UUID.randomUUID(),
                source(Optional.empty()).binding());
    }

    @Test
    void logicalModeStaysLogicalAndOnlyExactTrustedProofCanBindIt() {
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> PostgresMetricExecutor.validateRealization(source(Optional.empty())));
        var proof = proof();
        var exact = source(Optional.of(proof));
        assertDoesNotThrow(() -> PostgresMetricExecutor.validateRealization(exact));
        assertEquals(
                DatasetContract.Mode.LOGICAL_TARGET, exact.datasetContract().orElseThrow().mode());
        var wrongBinding = exact.binding().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongBinding)
                .put("fingerprint", "0".repeat(64));
        var swapped =
                new BoundQuerySource(
                        exact.alias(),
                        exact.dataset(),
                        exact.rowSchema(),
                        wrongBinding,
                        exact.releaseId(),
                        exact.contentHash(),
                        exact.datasetContract(),
                        exact.realization());
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> PostgresMetricExecutor.validateRealization(swapped));
        var otherRelease =
                new BoundQuerySource(
                        exact.alias(),
                        exact.dataset(),
                        exact.rowSchema(),
                        exact.binding(),
                        UUID.randomUUID(),
                        exact.contentHash(),
                        exact.datasetContract(),
                        exact.realization());
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> PostgresMetricExecutor.validateRealization(otherRelease));
    }

    @Test
    void currentProducerFenceIsRecheckedRatherThanTrustingAnEarlierAcceptedProof() {
        var proof = proof();
        var source = source(Optional.of(proof));
        var beans = new StaticListableBeanFactory();
        LogicalDatasetBindings current =
                new LogicalDatasetBindings() {
                    @Override
                    public Optional<com.fasterxml.jackson.databind.JsonNode> binding(
                            EntityRef ref) {
                        return Optional.of(proof.binding());
                    }

                    @Override
                    public Optional<RealizationProof> realization(EntityRef ref) {
                        return Optional.of(proof);
                    }
                };
        beans.addBean("trusted", current);
        assertDoesNotThrow(
                () ->
                        PostgresMetricExecutor.currentRealizations(
                                Map.of("out", source),
                                beans.getBeanProvider(LogicalDatasetBindings.class)));
        var fenced = new StaticListableBeanFactory();
        fenced.addBean("trusted", (LogicalDatasetBindings) ref -> Optional.empty());
        assertThrows(
                PostgresWarehouse.Failure.class,
                () ->
                        PostgresMetricExecutor.currentRealizations(
                                Map.of("out", source),
                                fenced.getBeanProvider(LogicalDatasetBindings.class)));
        assertThrows(
                PostgresWarehouse.Failure.class,
                () -> PostgresMetricExecutor.currentRealizations(Map.of("out", source), null));
    }
}
