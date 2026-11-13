package ai.awe.registry.model;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.Grain;
import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DatasetExtractProfileTest {
    private final JsonDocuments json = new JsonDocuments();
    private final EntityRef owner = new EntityRef("dataset.video.events", "1.0.0");
    private final EntityRef result = new EntityRef("dataset.video.event_rows", "1.0.0");
    private final JsonNode spec =
            json.parse(
                    "{\"bindings\":[{\"sourceSystem\":\"postgres\",\"externalRef\":\"video.events\",\"timeFields\":{\"EVENT_TIME\":\"event_time\"}}]}");

    private SemanticModel.Dataset dataset() {
        return new SemanticModel.Dataset(
                owner,
                new Grain(List.of("event_id"), Grain.GrainKind.EVENT),
                new TemporalContract(
                        Set.of(TemporalContract.TimeRole.EVENT_TIME), ZoneId.of("UTC"), true),
                new EntityRef("entity.video.device", "1.0.0"),
                Set.of());
    }

    private ObjectType row() {
        return new ObjectType(
                result,
                ObjectType.Family.DATASET,
                "Declared extraction rows",
                List.of(
                        new ObjectType.Property(
                                "event_id", ObjectType.DataType.INTEGER, true, false),
                        new ObjectType.Property(
                                "event_time", ObjectType.DataType.TIMESTAMP, true, false),
                        new ObjectType.Property(
                                "amount", ObjectType.DataType.DECIMAL, false, false)),
                List.of("event_id"));
    }

    private FunctionDefinition function(
            String operation,
            String executor,
            String resultRef,
            List<FunctionDefinition.Parameter> parameters,
            Set<String> assertions) {
        return new FunctionDefinition(
                new EntityRef("function.video.extract", "1.0.0"),
                owner,
                operation,
                parameters,
                resultRef,
                executor,
                assertions);
    }

    private List<FunctionDefinition.Parameter> parameters() {
        return List.of(
                new FunctionDefinition.Parameter("from", ObjectType.DataType.TIMESTAMP, true),
                new FunctionDefinition.Parameter("to", ObjectType.DataType.TIMESTAMP, true));
    }

    private FunctionDefinition function() {
        return function(
                DatasetExtractProfile.OPERATION,
                DatasetExtractProfile.EXECUTOR,
                "dataset.video.event_rows@1.0.0",
                parameters(),
                Set.of());
    }

    @Test
    void validPublishedProfileHasExplicitRowSchemaAndTimeBinding() {
        assertTrue(DatasetExtractProfile.matches(function()));
        assertEquals(result, DatasetExtractProfile.parseResultRef(function().resultSchemaRef()));
        assertDoesNotThrow(
                () -> DatasetExtractProfile.validate(function(), dataset(), row(), spec));
    }

    @Test
    void reservedOperationAndExecutorCannotBePartiallyMatched() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DatasetExtractProfile.matches(
                                function(
                                        DatasetExtractProfile.OPERATION,
                                        "other.executor",
                                        "dataset.video.event_rows@1.0.0",
                                        parameters(),
                                        Set.of())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DatasetExtractProfile.matches(
                                function(
                                        "other.operation",
                                        DatasetExtractProfile.EXECUTOR,
                                        "dataset.video.event_rows@1.0.0",
                                        parameters(),
                                        Set.of())));
        assertFalse(
                DatasetExtractProfile.matches(
                        function(
                                "dataset.inspect",
                                "adapter.inspect",
                                "schema.video.old",
                                parameters(),
                                Set.of())));
    }

    @Test
    void resultReferencesMustHaveOneCanonicalVersion() {
        for (String invalid :
                List.of(
                        "dataset.video.event_rows",
                        "dataset.video.event_rows@latest",
                        "dataset.video.event_rows@01.0.0",
                        "dataset.video.event_rows@1.0.0@2.0.0",
                        "bad@1.0.0"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DatasetExtractProfile.parseResultRef(invalid));
    }

    @Test
    void ownerAndResultMustPinTheExactSuppliedModels() {
        var different =
                new SemanticModel.Dataset(
                        new EntityRef(owner.id(), "2.0.0"),
                        dataset().grain(),
                        dataset().time(),
                        dataset().subjectType(),
                        Set.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(function(), different, row(), spec));
        var wrongVersion =
                new ObjectType(
                        new EntityRef(result.id(), "2.0.0"),
                        row().family(),
                        row().description(),
                        row().properties(),
                        row().identityKeys());
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(function(), dataset(), wrongVersion, spec));
    }

    @Test
    void subjectSchemaCannotReplaceAnExplicitDatasetRowSchema() {
        var subject =
                new ObjectType(
                        result,
                        ObjectType.Family.BUSINESS_ENTITY,
                        "Subject is not row",
                        row().properties(),
                        row().identityKeys());
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(function(), dataset(), subject, spec));
    }

    @Test
    void rowIdentityMustMatchTheEntireDatasetGrainInOrder() {
        var properties =
                List.of(
                        new ObjectType.Property(
                                "event_id", ObjectType.DataType.INTEGER, true, false),
                        new ObjectType.Property(
                                "device_id", ObjectType.DataType.STRING, true, false),
                        new ObjectType.Property(
                                "event_time", ObjectType.DataType.TIMESTAMP, true, false));
        var source =
                new SemanticModel.Dataset(
                        owner,
                        new Grain(List.of("event_id", "device_id"), Grain.GrainKind.EVENT),
                        dataset().time(),
                        dataset().subjectType(),
                        Set.of());
        var schema =
                new ObjectType(
                        result,
                        ObjectType.Family.DATASET,
                        "Rows",
                        properties,
                        List.of("device_id", "event_id"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(function(), source, schema, spec));
    }

    @Test
    void parametersAndUnsupportedAssertionsCannotBeIgnored() {
        var wrongType =
                function(
                        DatasetExtractProfile.OPERATION,
                        DatasetExtractProfile.EXECUTOR,
                        function().resultSchemaRef(),
                        List.of(
                                new FunctionDefinition.Parameter(
                                        "from", ObjectType.DataType.STRING, true),
                                parameters().get(1)),
                        Set.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(wrongType, dataset(), row(), spec));
        var optional =
                function(
                        DatasetExtractProfile.OPERATION,
                        DatasetExtractProfile.EXECUTOR,
                        function().resultSchemaRef(),
                        List.of(
                                new FunctionDefinition.Parameter(
                                        "from", ObjectType.DataType.TIMESTAMP, false),
                                parameters().get(1)),
                        Set.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(optional, dataset(), row(), spec));
        var assertions =
                function(
                        DatasetExtractProfile.OPERATION,
                        DatasetExtractProfile.EXECUTOR,
                        function().resultSchemaRef(),
                        parameters(),
                        Set.of("assertion.video.ready@1.0.0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(assertions, dataset(), row(), spec));
    }

    @Test
    void unsupportedColumnTypesAndSqlFragmentsFailClosed() {
        for (var property :
                List.of(
                        new ObjectType.Property("payload", ObjectType.DataType.JSON, false, false),
                        new ObjectType.Property(
                                "x;DROP TABLE events", ObjectType.DataType.STRING, false, false))) {
            var properties = new java.util.ArrayList<>(row().properties());
            properties.add(property);
            var schema =
                    new ObjectType(
                            result,
                            ObjectType.Family.DATASET,
                            "Rows",
                            properties,
                            row().identityKeys());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DatasetExtractProfile.validate(function(), dataset(), schema, spec));
        }
    }

    @Test
    void eventTimeMustBeAnExplicitTimestampColumnAndBindingMustBeUnique() {
        var properties =
                List.of(
                        row().properties().get(0),
                        new ObjectType.Property(
                                "event_time", ObjectType.DataType.DATE, true, false));
        var schema =
                new ObjectType(
                        result,
                        ObjectType.Family.DATASET,
                        "Rows",
                        properties,
                        row().identityKeys());
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(function(), dataset(), schema, spec));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DatasetExtractProfile.validate(
                                function(), dataset(), row(), json.parse("{\"bindings\":[]}")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DatasetExtractProfile.validate(
                                function(),
                                dataset(),
                                row(),
                                json.parse("{\"bindings\":[{\"timeFields\":{}}]}")));
    }

    @Test
    void subjectSensitivityAndSameNameTypesCannotBeDowngraded() {
        var sensitiveSubject =
                new ObjectType(
                        new EntityRef("entity.video.device", "1.0.0"),
                        ObjectType.Family.BUSINESS_ENTITY,
                        "Protected device identity",
                        List.of(
                                new ObjectType.Property(
                                        "event_id", ObjectType.DataType.INTEGER, true, true)),
                        List.of("event_id"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validateSubject(row(), sensitiveSubject));
        var safeProperties = new java.util.ArrayList<>(row().properties());
        safeProperties.set(
                0, new ObjectType.Property("event_id", ObjectType.DataType.INTEGER, true, true));
        var safeRows =
                new ObjectType(
                        result,
                        row().family(),
                        row().description(),
                        safeProperties,
                        row().identityKeys());
        assertDoesNotThrow(() -> DatasetExtractProfile.validateSubject(safeRows, sensitiveSubject));
        var differentType =
                new ObjectType(
                        new EntityRef("entity.video.device", "1.0.0"),
                        ObjectType.Family.BUSINESS_ENTITY,
                        "String device identity",
                        List.of(
                                new ObjectType.Property(
                                        "event_id", ObjectType.DataType.STRING, true, false)),
                        List.of("event_id"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validateSubject(row(), differentType));
    }

    @Test
    void unrelatedSubjectFieldsAreNotInventedAsOutputRequirements() {
        var subject =
                new ObjectType(
                        new EntityRef("entity.video.device", "1.0.0"),
                        ObjectType.Family.BUSINESS_ENTITY,
                        "Device identity not projected",
                        List.of(
                                new ObjectType.Property(
                                        "device_id", ObjectType.DataType.STRING, true, true)),
                        List.of("device_id"));
        assertDoesNotThrow(() -> DatasetExtractProfile.validateSubject(row(), subject));
    }

    @Test
    void unsupportedSourceCannotBePublishedAsPostgresExtract() {
        var other =
                json.parse(
                        "{\"bindings\":[{\"sourceSystem\":\"hive\",\"timeFields\":{\"EVENT_TIME\":\"event_time\"}}]}");
        assertThrows(
                IllegalArgumentException.class,
                () -> DatasetExtractProfile.validate(function(), dataset(), row(), other));
    }

    @Test
    void executionAndResultSchemaChangesRemainBreakingVersionedContracts() {
        var compatibility = new ModelCompatibility();
        JsonNode before = json.parse(json.write(Map.of("schemaVersion", 2, "model", function())));
        var changedSchema =
                function(
                        DatasetExtractProfile.OPERATION,
                        DatasetExtractProfile.EXECUTOR,
                        "dataset.video.event_rows@2.0.0",
                        parameters(),
                        Set.of());
        JsonNode after = json.parse(json.write(Map.of("schemaVersion", 2, "model", changedSchema)));
        assertEquals(
                true, compatibility.compare("FunctionDefinition", before, after).get("breaking"));
        var changedExecutor =
                function(
                        DatasetExtractProfile.OPERATION,
                        "different.executor",
                        function().resultSchemaRef(),
                        parameters(),
                        Set.of());
        after = json.parse(json.write(Map.of("schemaVersion", 2, "model", changedExecutor)));
        assertEquals(
                true, compatibility.compare("FunctionDefinition", before, after).get("breaking"));
        assertEquals(
                false, compatibility.compare("FunctionDefinition", before, before).get("breaking"));
    }
}
