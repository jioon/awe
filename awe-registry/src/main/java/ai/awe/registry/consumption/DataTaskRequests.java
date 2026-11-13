package ai.awe.registry.consumption;

import ai.awe.kernel.capability.DataTask;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 请求边界只接受已实现的能力与约束，未知字段不能被静默忽略。 */
public final class DataTaskRequests {
    private DataTaskRequests() {}

    public static DataTask decode(JsonNode node) {
        fields(
                node,
                Set.of(
                        "protocolVersion",
                        "intent",
                        "externalTaskRef",
                        "proposedFunction",
                        "requirements",
                        "consumer",
                        "constraints",
                        "idempotencyKey"));
        if (!"EXTRACT".equals(text(node, "intent")))
            throw new IllegalArgumentException("Only EXTRACT is supported");
        JsonNode proposed = node.path("proposedFunction"),
                requirements = node.path("requirements"),
                consumer = node.path("consumer"),
                constraints = node.path("constraints");
        fields(proposed, Set.of("releaseId", "contentHash"));
        fields(requirements, Set.of("fields", "from", "to", "view"));
        fields(consumer, Set.of("purpose"));
        fields(constraints, Set.of("maxRows", "maxBytes"));
        if (!"OBSERVED_SNAPSHOT".equals(text(requirements, "view")))
            throw new IllegalArgumentException(
                    "Only OBSERVED_SNAPSHOT is supported; period completeness is not established");
        if (!requirements.path("fields").isArray())
            throw new IllegalArgumentException("requirements.fields must be an array");
        var projection = new ArrayList<String>();
        for (JsonNode field : requirements.path("fields")) {
            if (!field.isTextual()) throw new IllegalArgumentException("Each field must be text");
            projection.add(field.asText());
        }
        DataTask.ExternalTaskRef external = null;
        if (node.has("externalTaskRef")) {
            var ref = node.get("externalTaskRef");
            fields(ref, Set.of("issuer", "id"));
            external = new DataTask.ExternalTaskRef(text(ref, "issuer"), text(ref, "id"));
        }
        UUID functionId;
        try {
            functionId = UUID.fromString(text(proposed, "releaseId"));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid function releaseId");
        }
        return new DataTask(
                integer(node, "protocolVersion"),
                DataTask.Intent.EXTRACT,
                external,
                new DataTask.ProposedFunction(functionId, text(proposed, "contentHash")),
                new DataTask.Requirements(
                        projection,
                        instant(requirements, "from"),
                        instant(requirements, "to"),
                        DataTask.View.OBSERVED_SNAPSHOT),
                new DataTask.Consumer(text(consumer, "purpose")),
                new DataTask.Constraints(
                        integer(constraints, "maxRows"), integer(constraints, "maxBytes")),
                text(node, "idempotencyKey"));
    }

    public static ObjectNode normalized(DataTask task, JsonDocuments json) {
        var body = new LinkedHashMap<String, Object>();
        body.put("protocolVersion", task.protocolVersion());
        body.put("intent", task.intent().name());
        if (task.externalTaskRef() != null)
            body.put(
                    "externalTaskRef",
                    Map.of(
                            "issuer",
                            task.externalTaskRef().issuer(),
                            "id",
                            task.externalTaskRef().id()));
        body.put(
                "proposedFunction",
                Map.of(
                        "releaseId",
                        task.proposedFunction().releaseId().toString(),
                        "contentHash",
                        task.proposedFunction().contentHash()));
        body.put("requirements", requirements(task));
        body.put("consumer", Map.of("purpose", task.consumer().purpose()));
        body.put(
                "constraints",
                Map.of(
                        "maxRows",
                        task.constraints().maxRows(),
                        "maxBytes",
                        task.constraints().maxBytes()));
        body.put("idempotencyKey", task.idempotencyKey());
        return json.object(json.write(body));
    }

    public static Map<String, Object> requirements(DataTask task) {
        return Map.of(
                "fields",
                task.requirements().fields(),
                "from",
                task.requirements().from().toString(),
                "to",
                task.requirements().to().toString(),
                "view",
                task.requirements().view().name());
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject())
            throw new IllegalArgumentException("Request component must be an object");
        node.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!allowed.contains(key))
                                throw new IllegalArgumentException(
                                        "Unsupported request field: " + key);
                        });
    }

    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank())
            throw new IllegalArgumentException("Missing text field: " + key);
        return node.path(key).asText();
    }

    private static int integer(JsonNode node, String key) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt())
            throw new IllegalArgumentException("Required integer: " + key);
        return node.path(key).intValue();
    }

    private static Instant instant(JsonNode node, String key) {
        try {
            return Instant.parse(text(node, key));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    key + " requires an ISO-8601 timestamp with explicit offset");
        }
    }
}
