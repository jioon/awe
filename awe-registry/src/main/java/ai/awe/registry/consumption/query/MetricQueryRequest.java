package ai.awe.registry.consumption.query;

import ai.awe.kernel.capability.DataTask;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** QUERY_METRIC 是 DataTask 的显式新版本；不把调用方提供的 SQL、计划或权限声明带入执行。 */
public record MetricQueryRequest(
        DataTask.ProposedFunction proposedFunction,
        Instant from,
        Instant to,
        View view,
        Verification verification,
        String purpose,
        int maxRows,
        int maxBytes,
        String idempotencyKey,
        Optional<DataTask.ExternalTaskRef> externalTaskRef) {
    public enum View {
        OBSERVED_SNAPSHOT,
        PERIOD_COMPLETE
    }

    public enum Verification {
        OBSERVE,
        REQUIRE_INDEPENDENT
    }

    public MetricQueryRequest {
        Objects.requireNonNull(proposedFunction);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(view);
        Objects.requireNonNull(verification);
        Objects.requireNonNull(externalTaskRef);
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must precede to");
        new DataTask.Consumer(purpose);
        new DataTask.Constraints(maxRows, maxBytes);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid idempotencyKey");
    }

    public static MetricQueryRequest decode(JsonNode request) {
        fields(
                request,
                Set.of(
                        "protocolVersion",
                        "intent",
                        "proposedFunction",
                        "requirements",
                        "consumer",
                        "constraints",
                        "idempotencyKey",
                        "externalTaskRef"));
        if (!request.path("protocolVersion").isIntegralNumber()
                || !request.path("protocolVersion").canConvertToInt()
                || request.path("protocolVersion").intValue() != 2
                || !"QUERY_METRIC".equals(text(request, "intent")))
            throw new IllegalArgumentException("Expected protocolVersion 2 QUERY_METRIC");
        JsonNode proposed = request.path("proposedFunction"),
                requirements = request.path("requirements"),
                consumer = request.path("consumer"),
                constraints = request.path("constraints");
        fields(proposed, Set.of("releaseId", "contentHash"));
        fields(requirements, Set.of("from", "to", "view", "verification"));
        fields(consumer, Set.of("purpose"));
        fields(constraints, Set.of("maxRows", "maxBytes"));
        Optional<DataTask.ExternalTaskRef> external = Optional.empty();
        if (request.has("externalTaskRef")) {
            var ref = request.get("externalTaskRef");
            fields(ref, Set.of("issuer", "id"));
            external =
                    Optional.of(new DataTask.ExternalTaskRef(text(ref, "issuer"), text(ref, "id")));
        }
        try {
            return new MetricQueryRequest(
                    new DataTask.ProposedFunction(
                            UUID.fromString(text(proposed, "releaseId")),
                            text(proposed, "contentHash")),
                    Instant.parse(text(requirements, "from")),
                    Instant.parse(text(requirements, "to")),
                    View.valueOf(text(requirements, "view")),
                    requirements.has("verification")
                            ? Verification.valueOf(text(requirements, "verification"))
                            : Verification.OBSERVE,
                    text(consumer, "purpose"),
                    integer(constraints, "maxRows"),
                    integer(constraints, "maxBytes"),
                    text(request, "idempotencyKey"),
                    external);
        } catch (java.time.DateTimeException ex) {
            throw new IllegalArgumentException(
                    "Metric query needs ISO-8601 timestamps with explicit offsets");
        }
    }

    public ObjectNode normalized(JsonDocuments json) {
        var body = new LinkedHashMap<String, Object>();
        body.put("protocolVersion", 2);
        body.put("intent", "QUERY_METRIC");
        body.put(
                "proposedFunction",
                Map.of(
                        "releaseId",
                        proposedFunction.releaseId().toString(),
                        "contentHash",
                        proposedFunction.contentHash()));
        body.put("requirements", requirements());
        body.put("consumer", Map.of("purpose", purpose));
        body.put("constraints", Map.of("maxRows", maxRows, "maxBytes", maxBytes));
        body.put("idempotencyKey", idempotencyKey);
        externalTaskRef.ifPresent(
                ref -> body.put("externalTaskRef", Map.of("issuer", ref.issuer(), "id", ref.id())));
        return json.object(json.write(body));
    }

    public Map<String, Object> requirements() {
        return Map.of(
                "from",
                from.toString(),
                "to",
                to.toString(),
                "view",
                view.name(),
                "verification",
                verification.name());
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject())
            throw new IllegalArgumentException("Metric request component must be an object");
        node.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!allowed.contains(k))
                                throw new IllegalArgumentException(
                                        "Unsupported metric request field: " + k);
                        });
    }

    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank())
            throw new IllegalArgumentException("Missing text field: " + key);
        return node.path(key).asText();
    }

    private static int integer(JsonNode node, String key) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt())
            throw new IllegalArgumentException("Expected integer: " + key);
        return node.path(key).intValue();
    }
}
