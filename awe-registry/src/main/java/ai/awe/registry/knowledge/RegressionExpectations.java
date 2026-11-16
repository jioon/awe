package ai.awe.registry.knowledge;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 数据值与控制决策分别验收；空数组 Hash 不能证明系统正确拒绝或账期未就绪。 */
public final class RegressionExpectations {
    private RegressionExpectations() {}

    public static final Set<String> FAILURE_STATUSES =
            Set.of(
                    "DENIED",
                    "NOT_DISCLOSED",
                    "REJECTED",
                    "BLOCKED",
                    "NOT_READY",
                    "NOT_CONFIGURED",
                    "UNAVAILABLE",
                    "INCONCLUSIVE",
                    "FAILED_VERIFICATION");
    private static final Set<String> STAGES =
            Set.of(
                    "RESOLUTION",
                    "AUTHORIZATION",
                    "PLANNING",
                    "SOURCE_GATE",
                    "EXECUTION",
                    "READINESS",
                    "VERIFICATION",
                    "UNKNOWN");

    public static JsonNode decode(JsonNode value, String kind, JsonDocuments json) {
        String type = KnowledgeJson.text(value, "type", 40);
        if (type.equals("DATA_OUTCOME")) {
            if (!kind.equals("DATA"))
                throw new IllegalArgumentException("A data outcome needs a data observation");
            KnowledgeJson.fields(
                    value,
                    "type",
                    "status",
                    "httpStatus",
                    "decision",
                    "failureStage",
                    "noDataReturned");
            KnowledgeJson.enumeration(value, "status", FAILURE_STATUSES);
            KnowledgeJson.enumeration(value, "failureStage", STAGES);
            JsonNode status = value.path("httpStatus");
            if (!status.isIntegralNumber()
                    || !status.canConvertToInt()
                    || !Set.of(200, 400, 403, 404, 409, 503).contains(status.intValue())
                    || !value.path("noDataReturned").isBoolean()
                    || !value.path("noDataReturned").asBoolean())
                throw new IllegalArgumentException(
                        "A negative outcome must explicitly attest no data and an HTTP status");
            JsonNode axes = value.path("decision");
            KnowledgeJson.fields(axes, "resolution", "supply", "authorization", "readiness");
            for (String axis : List.of("resolution", "supply", "authorization", "readiness"))
                KnowledgeJson.text(axes, axis, 50);
        } else {
            if (!(type.equals("DATA_RESULT") && kind.equals("DATA")
                    || type.equals("RESOLUTION") && kind.equals("RESOLUTION")))
                throw new IllegalArgumentException("Expectation kind differs from its observation");
            KnowledgeJson.fields(value, "type", "state", "fingerprint");
            KnowledgeJson.enumeration(
                    value,
                    "state",
                    kind.equals("DATA")
                            ? Set.of("RECORDS", "NO_RECORD", "ZERO", "NULL", "NOT_APPLICABLE")
                            : Set.of("RESOLVED", "AMBIGUOUS", "UNRESOLVED"));
            KnowledgeJson.hash(value, "fingerprint");
        }
        return value.deepCopy();
    }

    public static boolean outcome(JsonNode expectation) {
        return expectation.path("type").asText().equals("DATA_OUTCOME");
    }

    public static JsonNode actual(JsonNode observation, JsonDocuments json) {
        if (observation.path("outcome").isObject()) return observation.path("outcome").deepCopy();
        var axes = new LinkedHashMap<String, String>();
        for (String key : List.of("resolution", "supply", "authorization", "readiness"))
            axes.put(key, observation.path("decision").path(key).asText("UNKNOWN"));
        String status = observation.path("status").asText();
        String stage = "UNKNOWN";
        var accepted =
                Set.of(
                        "RESOLVED",
                        "ALLOWED",
                        "VALIDATED",
                        "VERIFIED",
                        "QUERIED",
                        "EXTRACTED",
                        "READY",
                        "PASSED",
                        "UNKNOWN",
                        "NOT_RUN",
                        "NOT_REQUIRED");
        for (JsonNode value : observation.path("stages"))
            if (!accepted.contains(value.path("status").asText())
                    && STAGES.contains(value.path("stage").asText())) {
                stage = value.path("stage").asText();
                break;
            }
        if (stage.equals("UNKNOWN")) {
            if (status.equals("NOT_READY")) stage = "READINESS";
            else if (status.equals("FAILED_VERIFICATION")) stage = "VERIFICATION";
            else if (!Set.of("QUERIED", "EXTRACTED").contains(status)) stage = "EXECUTION";
        }
        boolean noData =
                FAILURE_STATUSES.contains(status)
                        && observation.path("result").path("rows").isEmpty();
        return json.parse(
                json.write(
                        Map.of(
                                "status",
                                status,
                                "httpStatus",
                                observation.path("httpStatus").asInt(200),
                                "decision",
                                axes,
                                "failureStage",
                                stage,
                                "noDataReturned",
                                noData)));
    }

    public static boolean matches(JsonNode expectation, JsonNode observation, JsonDocuments json) {
        var normalized = ((ObjectNode) expectation).deepCopy();
        normalized.remove("type");
        return json.canonical(normalized).equals(json.canonical(actual(observation, json)));
    }
}
