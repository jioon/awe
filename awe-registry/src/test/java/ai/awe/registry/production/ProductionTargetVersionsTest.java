package ai.awe.registry.production;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionTargetVersionsTest {
    private final JsonDocuments json = new JsonDocuments();
    private final List<String> keys = List.of("postgres:domain.a", "postgres:domain.b");
    private final String owner = UUID.randomUUID().toString();

    private ObjectNode authorization() {
        return json.object(
                json.write(
                        Map.of(
                                "protocolVersion",
                                2,
                                "consumptionId",
                                owner,
                                "physicalTargets",
                                keys,
                                "targetBaselines",
                                keys.stream()
                                        .map(
                                                k ->
                                                        Map.of(
                                                                "targetKey",
                                                                k,
                                                                "revision",
                                                                "old",
                                                                "consumptionId",
                                                                "",
                                                                "intentId",
                                                                "0"))
                                        .toList(),
                                "targetWriteIntents",
                                keys.stream()
                                        .map(
                                                k ->
                                                        Map.of(
                                                                "targetKey",
                                                                k,
                                                                "intentId",
                                                                "9007199254740993"))
                                        .toList())));
    }

    private ObjectNode deployment() {
        return json.object(
                json.write(
                        Map.of(
                                "targetRevisions",
                                keys.stream()
                                        .map(
                                                k ->
                                                        Map.of(
                                                                "targetKey",
                                                                k,
                                                                "previousRevision",
                                                                "old",
                                                                "revision",
                                                                "new",
                                                                "consumptionId",
                                                                owner,
                                                                "intentId",
                                                                "9007199254740993"))
                                        .toList(),
                                "currentTargets",
                                keys.stream()
                                        .map(
                                                k ->
                                                        Map.of(
                                                                "targetKey",
                                                                k,
                                                                "revision",
                                                                "new",
                                                                "consumptionId",
                                                                owner,
                                                                "intentId",
                                                                "9007199254740993"))
                                        .toList())));
    }

    @Test
    void completeVectorIsOrderIndependentAndPreservesExactIntentIds() {
        ObjectNode deployment = deployment();
        ArrayNode current = (ArrayNode) deployment.path("currentTargets");
        JsonNode first = current.remove(0);
        current.add(first);
        assertTrue(ProductionTargetVersions.active(deployment, authorization()));
        assertEquals(
                "9007199254740993",
                ProductionTargetVersions.intents(authorization().path("targetWriteIntents"), keys)
                        .get(keys.getFirst()));
    }

    @Test
    void subsetExtraAndDuplicateTargetsCannotAttestTheDeployment() {
        for (String mode : List.of("subset", "extra", "duplicate")) {
            ObjectNode document = deployment();
            ArrayNode vector = (ArrayNode) document.path("currentTargets");
            if (mode.equals("subset")) vector.remove(0);
            else if (mode.equals("duplicate")) vector.add(vector.get(0).deepCopy());
            else {
                var added = vector.addObject();
                added.put("targetKey", "postgres:other")
                        .put("revision", "new")
                        .put("consumptionId", owner)
                        .put("intentId", "3");
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProductionTargetVersions.active(document, authorization()));
        }
    }

    @Test
    void historicalSuccessDoesNotAttestCurrentOwnership() {
        ObjectNode document = deployment();
        ((ObjectNode) document.path("currentTargets").get(0))
                .put("consumptionId", UUID.randomUUID().toString());
        assertFalse(ProductionTargetVersions.active(document, authorization()));
    }

    @Test
    void wrongPriorRevisionSameRevisionAndOldIntentFailClosed() {
        for (String mode : List.of("prior", "unchanged", "old-intent")) {
            ObjectNode document = deployment(), auth = authorization();
            ObjectNode transition = (ObjectNode) document.path("targetRevisions").get(0);
            if (mode.equals("prior")) transition.put("previousRevision", "wrong");
            else if (mode.equals("unchanged")) transition.put("revision", "old");
            else {
                ((ObjectNode) auth.path("targetBaselines").get(0))
                        .put("intentId", "9007199254740994")
                        .put("consumptionId", owner);
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProductionTargetVersions.active(document, auth));
        }
    }

    @Test
    void forgedIntentEncodingsAndLegacyAuthorizationsAreRejected() {
        for (String value : List.of("0", "-1", "01", "1.0", "9223372036854775808")) {
            var auth = authorization();
            ((ObjectNode) auth.path("targetWriteIntents").get(0)).put("intentId", value);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProductionTargetVersions.active(deployment(), auth));
        }
        var legacy = authorization();
        legacy.remove("protocolVersion");
        assertThrows(
                IllegalArgumentException.class,
                () -> ProductionTargetVersions.active(deployment(), legacy));
    }
}
