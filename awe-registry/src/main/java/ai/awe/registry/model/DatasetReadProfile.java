package ai.awe.registry.model;

import ai.awe.kernel.contract.DatasetContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 显式区分时间窗口与静态事务快照，不给维表编造事件日期。 */
public final class DatasetReadProfile {
    public static final String SCAN_OPERATION = "dataset.scan.v1";
    public static final String SNAPSHOT_OPERATION = "dataset.snapshot.v1";
    public static final String SCAN_EXECUTOR = "postgres.dataset.scan.v1";
    public static final String SNAPSHOT_EXECUTOR = "postgres.dataset.snapshot.v1";
    public static final String WAREHOUSE_SCAN_EXECUTOR = "warehouse.dataset.scan.v1";
    public static final String WAREHOUSE_SNAPSHOT_EXECUTOR = "warehouse.dataset.snapshot.v1";

    private DatasetReadProfile() {}

    public static boolean matches(FunctionDefinition function) {
        boolean scan = SCAN_OPERATION.equals(function.operation()),
                snapshot = SNAPSHOT_OPERATION.equals(function.operation());
        boolean
                scanExecutor =
                        Set.of(SCAN_EXECUTOR, WAREHOUSE_SCAN_EXECUTOR)
                                .contains(function.executorBindingRef()),
                snapshotExecutor =
                        Set.of(SNAPSHOT_EXECUTOR, WAREHOUSE_SNAPSHOT_EXECUTOR)
                                .contains(function.executorBindingRef());
        if (scan != scanExecutor || snapshot != snapshotExecutor)
            throw new IllegalArgumentException("Dataset read operation/executor must match");
        return scan || snapshot;
    }

    public static EntityRef parseResultRef(String value) {
        return DatasetExtractProfile.parseResultRef(value);
    }

    public static void validate(
            FunctionDefinition function,
            SemanticModel.Dataset dataset,
            ObjectType rowSchema,
            JsonNode spec) {
        if (!matches(function) || spec.path("schemaVersion").asInt() != 3)
            throw new IllegalArgumentException(
                    "Dataset scan/snapshot requires an explicit schemaVersion 3 contract");
        var contract = new TypedModels().datasetContract(spec);
        if (!function.ownerType().equals(dataset.ref())
                || !parseResultRef(function.resultSchemaRef()).equals(contract.rowSchema())
                || !rowSchema.ref().equals(contract.rowSchema()))
            throw new IllegalArgumentException(
                    "Read profile must fix the exact Dataset and declared row Schema");
        if (rowSchema.family() != ObjectType.Family.DATASET
                || !rowSchema.identityKeys().equals(dataset.grain().keys())
                || rowSchema.properties().size() > 64)
            throw new IllegalArgumentException(
                    "Dataset read Schema must explicitly match row grain and supported field budget");
        for (var property : rowSchema.properties()) {
            ai.awe.kernel.contract.RelationalIR.identifier(property.name());
            if (Set.of(ObjectType.DataType.JSON, ObjectType.DataType.ENTITY_REF)
                    .contains(property.type()))
                throw new IllegalArgumentException(
                        "No implicit JSON/entity-reference field interpretation");
        }
        if (!function.requiredAssertions().isEmpty())
            throw new IllegalArgumentException(
                    "Dataset read profile cannot treat static Evidence declarations as runtime assertions");
        if (contract.readMode() == DatasetContract.ReadMode.STATIC_SNAPSHOT) {
            if (!SNAPSHOT_OPERATION.equals(function.operation())
                    || !function.parameters().isEmpty())
                throw new IllegalArgumentException(
                        "Static snapshot uses the parameter-free snapshot profile");
        } else {
            if (!SCAN_OPERATION.equals(function.operation())
                    || function.parameters().size() != 2
                    || !function.parameters().stream()
                            .allMatch(
                                    p -> p.required() && p.type() == ObjectType.DataType.TIMESTAMP)
                    || !function.parameters().stream()
                            .map(FunctionDefinition.Parameter::name)
                            .collect(java.util.stream.Collectors.toSet())
                            .equals(Set.of("from", "to")))
                throw new IllegalArgumentException(
                        "Window scan requires TIMESTAMP from/to parameters");
        }
        if (contract.mode() == DatasetContract.Mode.OBSERVED_SOURCE) {
            var bindings = spec.path("bindings");
            if (bindings.size() != 1)
                throw new IllegalArgumentException(
                        "Read profile requires one explicit source binding");
            if (Set.of(SCAN_EXECUTOR, SNAPSHOT_EXECUTOR).contains(function.executorBindingRef())
                    && !"postgres".equals(bindings.get(0).path("sourceSystem").asText()))
                throw new IllegalArgumentException(
                        "Native read profile requires an explicit PostgreSQL source");
            if (contract.readMode() == DatasetContract.ReadMode.EVENT_WINDOW) {
                String field =
                        bindings.get(0)
                                .path("timeFields")
                                .path(contract.rangeRole().name())
                                .asText();
                var property =
                        rowSchema.properties().stream()
                                .filter(p -> p.name().equals(field))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Range role must map to the declared row Schema"));
                if (property.type() != ObjectType.DataType.TIMESTAMP
                        && property.type() != ObjectType.DataType.DATE)
                    throw new IllegalArgumentException(
                            "Range field must have an explicit temporal type");
            }
        }
    }
}
