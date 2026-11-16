package ai.awe.registry.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegressionExpectationsTest {
    private final JsonDocuments json = new JsonDocuments();

    private ObjectNode outcome() {
        return json.object(
                json.write(
                        Map.of(
                                "type",
                                "DATA_OUTCOME",
                                "status",
                                "NOT_READY",
                                "httpStatus",
                                200,
                                "decision",
                                Map.of(
                                        "resolution",
                                        "RESOLVED",
                                        "supply",
                                        "DIRECT",
                                        "authorization",
                                        "ALLOWED",
                                        "readiness",
                                        "NOT_READY"),
                                "failureStage",
                                "READINESS",
                                "noDataReturned",
                                true)));
    }

    @Test
    void exactDecisionComparisonDoesNotUseEmptyRowFingerprint() {
        var expected = outcome();
        var actual = outcome();
        actual.remove("type");
        assertTrue(
                RegressionExpectations.matches(
                        RegressionExpectations.decode(expected, "DATA", json),
                        json.object(json.write(Map.of("outcome", actual))),
                        json));
        ((ObjectNode) actual.path("decision")).put("authorization", "DENIED");
        assertFalse(
                RegressionExpectations.matches(
                        expected, json.object(json.write(Map.of("outcome", actual))), json));
    }

    @Test
    void statusStageAndHttpStatusMustAllMatch() {
        for (String field : java.util.List.of("status", "failureStage", "httpStatus")) {
            var actual = outcome();
            actual.remove("type");
            if (field.equals("httpStatus")) actual.put(field, 503);
            else actual.put(field, field.equals("status") ? "UNAVAILABLE" : "EXECUTION");
            assertFalse(
                    RegressionExpectations.matches(
                            outcome(), json.object(json.write(Map.of("outcome", actual))), json));
        }
    }

    @Test
    void successfulEmptyDataCannotBecomeAnExpectedRefusal() {
        var expected = outcome();
        expected.put("status", "QUERIED");
        assertThrows(
                IllegalArgumentException.class,
                () -> RegressionExpectations.decode(expected, "DATA", json));
        var actual =
                json.object(
                        "{\"status\":\"QUERIED\",\"result\":{\"state\":\"NO_RECORD\",\"rows\":[]}}");
        assertFalse(RegressionExpectations.actual(actual, json).path("noDataReturned").asBoolean());
    }

    @Test
    void incompleteUnsafeAndOverflowingExpectationsAreRejected() {
        for (String variant :
                java.util.List.of("missing-axis", "data", "extra", "overflow", "wrong-kind")) {
            var value = outcome();
            if (variant.equals("missing-axis"))
                ((ObjectNode) value.path("decision")).remove("supply");
            else if (variant.equals("data")) value.put("noDataReturned", false);
            else if (variant.equals("extra")) value.put("sql", "select anything");
            else if (variant.equals("overflow")) value.put("httpStatus", 4294967496L);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RegressionExpectations.decode(
                                    value,
                                    variant.equals("wrong-kind") ? "RESOLUTION" : "DATA",
                                    json));
        }
    }

    @Test
    void dataAndResolutionReferencesRetainTheirDeclaredStateAndFingerprint() {
        var reference =
                json.object(
                        json.write(
                                Map.of(
                                        "type",
                                        "DATA_RESULT",
                                        "state",
                                        "NULL",
                                        "fingerprint",
                                        "a".repeat(64))));
        assertEquals(reference, RegressionExpectations.decode(reference, "DATA", json));
        assertThrows(
                IllegalArgumentException.class,
                () -> RegressionExpectations.decode(reference, "RESOLUTION", json));
    }
}
