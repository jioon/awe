package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.model.ModelValidation;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

final class ModelValidationTest {
    final JsonDocuments json = new JsonDocuments();
    final ModelValidation validation = new ModelValidation();

    @Test
    void canonicalHashIndependentOfObjectKeyOrder() {
        assertEquals(
                json.sha256(json.parse("{\"b\":2,\"a\":1}")),
                json.sha256(json.parse("{\"a\":1,\"b\":2}")));
    }

    @Test
    void validTypedDatasetContract() {
        assertDoesNotThrow(
                () ->
                        validation.validate(
                                "video",
                                "dataset.video.events",
                                "Dataset",
                                TypedModelFixtures.spec("Dataset")));
    }

    @Test
    void allTypePrefixesAndOwnReferencesAreChecked() {
        for (String type : new TypedModels().types()) {
            var spec = TypedModelFixtures.spec(type);
            assertDoesNotThrow(
                    () ->
                            validation.validate(
                                    "video", spec.at("/model/ref/id").asText(), type, spec));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            validation.validate(
                                    "other", spec.at("/model/ref/id").asText(), type, spec));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> validation.validate("video", "entity.video.wrong", type, spec));
        }
    }

    @Test
    void invalidGrainAndTimeRejected() {
        var grain = TypedModelFixtures.spec("Dataset");
        ((ObjectNode) grain.at("/model/grain")).putArray("keys").add("event_id").add("event_id");
        assertThrows(
                IllegalArgumentException.class,
                () -> validation.validate("video", "dataset.video.events", "Dataset", grain));
        var time = TypedModelFixtures.spec("Dataset");
        ((ObjectNode) time.at("/model/time")).put("businessZone", "Not/AZone");
        assertThrows(
                IllegalArgumentException.class,
                () -> validation.validate("video", "dataset.video.events", "Dataset", time));
    }

    @Test
    void dimensionIsAnExplicitObjectFamilyWithItsOwnNamespace() {
        var spec = TypedModelFixtures.spec("ObjectType");
        ((ObjectNode) spec.get("model")).put("family", "DIMENSION");
        ((ObjectNode) spec.at("/model/ref")).put("id", "dimension.video.app_version");
        assertDoesNotThrow(
                () ->
                        validation.validate(
                                "video", "dimension.video.app_version", "ObjectType", spec));
        ((ObjectNode) spec.at("/model/ref")).put("id", "entity.video.app_version");
        assertThrows(
                IllegalArgumentException.class,
                () -> validation.validate("video", "entity.video.app_version", "ObjectType", spec));
    }

    @Test
    void contractCannotDisagreeWithEnvelopeOwnership() {
        var spec = TypedModelFixtures.spec("ContractDefinition");
        ((ObjectNode) spec.get("model")).put("ownerTeam", "another_team");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validation.validate(
                                "video", "contract.video.events", "ContractDefinition", spec));
    }
}
