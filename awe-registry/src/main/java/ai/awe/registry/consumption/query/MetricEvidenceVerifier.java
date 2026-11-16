package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.MetricContract;
import ai.awe.registry.source.TrustedHttpGateway;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 就绪与独立对账是不同证明；可信连接仍须核对操作、快照、窗口和检查器版本。 */
@Component
public final class MetricEvidenceVerifier {
    public record Result(String status, Map<String, Object> evidence, List<String> limitations) {
        public Result {
            evidence = Map.copyOf(evidence);
            limitations = List.copyOf(limitations);
        }
    }

    private final TrustedHttpGateway readiness, verification;
    private final JsonDocuments json;
    private final Map<String, JsonNode> checkers;

    public MetricEvidenceVerifier(Environment env, JsonDocuments json) {
        this.json = json;
        readiness =
                new TrustedHttpGateway(
                        env.getProperty("AWE_DATA_READINESS_URL", ""),
                        env.getProperty("AWE_DATA_READINESS_TOKEN", ""),
                        json);
        verification =
                new TrustedHttpGateway(
                        env.getProperty("AWE_METRIC_VERIFICATION_URL", ""),
                        env.getProperty("AWE_METRIC_VERIFICATION_TOKEN", ""),
                        json);
        var registered = new TreeMap<String, JsonNode>();
        try {
            String raw = env.getProperty("AWE_METRIC_VERIFICATION_CHECKERS", "");
            if (!raw.isBlank()) {
                var document = json.object(raw);
                if (document.size() > 64) throw new IllegalArgumentException();
                document.fields()
                        .forEachRemaining(
                                e -> {
                                    var value = e.getValue();
                                    if (!e.getKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}")
                                            || !value.isObject()
                                            || value.size() != 2
                                            || !value.path("implementationVersion").isTextual()
                                            || value.path("implementationVersion")
                                                    .asText()
                                                    .isBlank()
                                            || !value.path("implementationHash")
                                                    .asText()
                                                    .matches("[a-f0-9]{64}"))
                                        throw new IllegalArgumentException();
                                    registered.put(e.getKey(), value.deepCopy());
                                });
            }
        } catch (RuntimeException invalid) {
            registered.clear();
        }
        checkers = Map.copyOf(registered);
    }

    public boolean readinessConfigured() {
        return readiness.configured();
    }

    public boolean verificationConfigured() {
        return verification.configured() && !checkers.isEmpty();
    }

    public Result readiness(
            Map<String, Object> context,
            MetricQueryRequest request,
            Map<String, String> snapshots) {
        var body = body(context, request, snapshots);
        var response = readiness.post("/readiness", body);
        if (!"RECEIVED".equals(response.status()))
            return result(response.status(), Map.of(), response.limitation());
        JsonNode doc = response.document();
        if (!bound(doc, body) || !sameSnapshots(doc.path("snapshots"), snapshots))
            return result(
                    "INCONCLUSIVE",
                    Map.of(),
                    "Readiness evidence is not bound to this exact operation and read snapshots");
        String status = doc.path("status").asText();
        if (!Set.of("READY", "NOT_READY", "UNKNOWN").contains(status))
            return result("INCONCLUSIVE", Map.of(), "Unsupported readiness observation state");
        try {
            Instant observed = Instant.parse(doc.path("observedAt").asText());
            if (observed.isAfter(Instant.now().plusSeconds(60))
                    || observed.isBefore(Instant.now().minusSeconds(300)))
                throw new IllegalArgumentException();
            if (doc.path("runRef").asText().isBlank()
                    || doc.path("attemptRef").asText().isBlank()
                    || doc.path("sourceRevision").asText().isBlank())
                throw new IllegalArgumentException();
            if (status.equals("READY")
                    && (!doc.path("complete").isBoolean()
                            || !doc.path("complete").booleanValue()
                            || !doc.path("from").asText().equals(request.from().toString())
                            || !doc.path("to").asText().equals(request.to().toString())
                            || !coversAllScans(
                                    doc.path("coverage"), body.path("scanWindows"), snapshots)))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            return result(
                    "INCONCLUSIVE",
                    Map.of(),
                    "Readiness proof is stale, incomplete or has a different coverage window");
        }
        return result(
                status,
                Map.of("observation", doc),
                "Readiness is a scoped source-platform observation, not proof of every downstream business claim");
    }

    public Result verify(
            Map<String, Object> context,
            MetricQueryRequest request,
            MetricContract contract,
            Map<String, String> snapshots,
            List<Map<String, Object>> actualRows,
            Map<String, Object> executionEvidence) {
        if (!verificationConfigured())
            return result("NOT_RUN", Map.of(), "No independent registered checker is configured");
        var body = body(context, request, snapshots);
        body.remove("requestHash");
        body.put("resultHash", json.sha256(json.parse(json.write(actualRows))));
        body.set("executionEvidence", json.parse(json.write(executionEvidence)));
        body.set(
                "outputs", new ai.awe.registry.model.TypedModels().encodeValue(contract.outputs()));
        body.set("checkers", json.parse(json.write(checkers)));
        body.put("requestHash", json.sha256(body));
        var response = verification.post("/verify", body);
        if (!"RECEIVED".equals(response.status()))
            return result(response.status(), Map.of(), response.limitation());
        JsonNode doc = response.document(),
                registered = checkers.get(doc.path("checkerRef").asText());
        if (!bound(doc, body)
                || registered == null
                || !sameSnapshots(doc.path("snapshots"), snapshots)
                || !doc.path("implementationVersion")
                        .equals(registered.path("implementationVersion"))
                || !doc.path("implementationHash").equals(registered.path("implementationHash")))
            return result(
                    "INCONCLUSIVE",
                    Map.of(),
                    "Independent checker identity, implementation or data scope is not the registered one");
        try {
            Instant observed = Instant.parse(doc.path("observedAt").asText());
            if (observed.isAfter(Instant.now().plusSeconds(60))
                    || observed.isBefore(Instant.now().minusSeconds(300))
                    || !doc.path("sameSnapshotVerified").isBoolean()
                    || !doc.path("sameSnapshotVerified").booleanValue()
                    || !doc.path("complete").isBoolean()
                    || !doc.path("complete").booleanValue()
                    || doc.path("runRef").asText().isBlank()
                    || doc.path("attemptRef").asText().isBlank()
                    || doc.path("referenceBasis").asText().isBlank()
                    || !doc.path("referenceRows").isArray()) throw new IllegalArgumentException();
            validateRows(
                    doc.path("referenceRows"), contract, request.maxRows(), request.maxBytes());
        } catch (RuntimeException invalid) {
            return result(
                    "INCONCLUSIVE",
                    Map.of(),
                    "Checker could not establish fresh complete reference results on the same data snapshots");
        }
        JsonNode normalizedReference;
        try {
            normalizedReference =
                    json.parse(
                            json.write(
                                    MetricResults.canonical(
                                            contract, doc.path("referenceRows"), json)));
        } catch (IllegalArgumentException invalid) {
            return result(
                    "INCONCLUSIVE",
                    Map.of(),
                    "Reference result violates the published output grain");
        }
        boolean equal =
                json.sha256(normalizedReference)
                        .equals(json.sha256(json.parse(json.write(actualRows))));
        return result(
                equal ? "PASSED" : "FAILED",
                Map.of(
                        "checkerRef",
                        doc.path("checkerRef").asText(),
                        "implementation",
                        registered,
                        "runRef",
                        doc.path("runRef").asText(),
                        "attemptRef",
                        doc.path("attemptRef").asText(),
                        "referenceBasis",
                        doc.path("referenceBasis").asText(),
                        "referenceHash",
                        json.sha256(normalizedReference),
                        "snapshots",
                        snapshots,
                        "observedAt",
                        doc.path("observedAt").asText()),
                equal
                        ? "Exact comparison against a registered independently produced reference, limited to the pinned window and snapshots"
                        : "The actual result differs from the independently produced reference");
    }

    public static void validateRows(
            JsonNode rows, MetricContract contract, int maxRows, int maxBytes) {
        if (!rows.isArray() || rows.size() > maxRows)
            throw new IllegalArgumentException("Reference row budget exceeded");
        var json = new JsonDocuments();
        if (json.canonical(rows).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > maxBytes) throw new IllegalArgumentException("Reference byte budget exceeded");
        Set<String> names =
                new HashSet<>(
                        contract.outputs().stream().map(MetricContract.Output::name).toList());
        for (JsonNode row : rows) {
            var actual = new HashSet<String>();
            row.fieldNames().forEachRemaining(actual::add);
            if (!row.isObject() || !actual.equals(names))
                throw new IllegalArgumentException("Reference output schema differs");
            for (var field : contract.outputs()) {
                var value = row.get(field.name());
                if (value.isNull()) {
                    if (!field.nullable())
                        throw new IllegalArgumentException("Reference required output is null");
                    continue;
                }
                boolean type =
                        switch (field.type()) {
                            case INTEGER -> value.isIntegralNumber();
                            case DECIMAL -> value.isNumber();
                            case BOOLEAN -> value.isBoolean();
                            case STRING, DATE, TIMESTAMP -> value.isTextual();
                            default -> false;
                        };
                if (!type) throw new IllegalArgumentException("Reference output type differs");
            }
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode body(
            Map<String, Object> context,
            MetricQueryRequest request,
            Map<String, String> snapshots) {
        var body = json.object(json.write(context));
        body.put("protocolVersion", 1);
        body.put("from", request.from().toString());
        body.put("to", request.to().toString());
        body.put("view", request.view().name());
        body.set("snapshots", json.parse(json.write(snapshots)));
        body.put("requestHash", json.sha256(body));
        return body;
    }

    private boolean bound(JsonNode doc, JsonNode body) {
        return doc.path("operationId").equals(body.path("operationId"))
                && doc.path("requestHash").equals(body.path("requestHash"));
    }

    private boolean coversAllScans(
            JsonNode coverage, JsonNode windows, Map<String, String> snapshots) {
        if (!coverage.isArray() || !windows.isArray() || windows.isEmpty()) return false;
        for (JsonNode expected : windows) {
            boolean covered = false;
            for (JsonNode actual : coverage) {
                if (!actual.path("source").equals(expected.path("source"))
                        || !actual.path("kind").equals(expected.path("kind"))
                        || !actual.path("snapshotRef")
                                .asText()
                                .equals(snapshots.get(expected.path("source").asText()))) continue;
                if (expected.path("kind").asText().equals("STATIC_SNAPSHOT"))
                    covered =
                            Set.of("OBSERVED", "COMPLETE").contains(actual.path("status").asText());
                else if (actual.path("status").asText().equals("COMPLETE")) {
                    Instant from = Instant.parse(actual.path("from").asText()),
                            to = Instant.parse(actual.path("to").asText());
                    covered =
                            !from.isAfter(Instant.parse(expected.path("from").asText()))
                                    && !to.isBefore(Instant.parse(expected.path("to").asText()));
                }
                if (covered) break;
            }
            if (!covered) return false;
        }
        return true;
    }

    private boolean sameSnapshots(JsonNode node, Map<String, String> snapshots) {
        return node.equals(json.parse(json.write(snapshots))) && !snapshots.isEmpty();
    }

    private Result result(String status, Map<String, Object> evidence, String limitation) {
        return new Result(status, evidence, List.of(limitation));
    }
}
