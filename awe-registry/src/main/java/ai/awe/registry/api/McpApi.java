package ai.awe.registry.api;

import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.consumption.DataTaskOperations;
import ai.awe.registry.knowledge.*;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** MCP 是同一受控数据能力的传输层，不执行任意工具、URL 或 Agent 工作流。 */
@RestController
@RequestMapping(value = "/api/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
public final class McpApi {
    private static final Set<String> VERSIONS = Set.of("2024-11-05", "2025-03-26");
    private final DataTaskOperations operations;
    private final KnowledgeService knowledge;
    private final FeedbackService feedback;
    private final JsonDocuments json;

    public McpApi(
            DataTaskOperations operations,
            KnowledgeService knowledge,
            FeedbackService feedback,
            JsonDocuments json) {
        this.operations = operations;
        this.knowledge = knowledge;
        this.feedback = feedback;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<String> call(@RequestBody String body, HttpServletRequest request) {
        JsonNode id = json.parse("null");
        try {
            KnowledgeHttp.bounded(body);
            JsonNode rpc;
            try {
                rpc = json.parse(body);
            } catch (IllegalArgumentException malformed) {
                return error(id, -32700, "Malformed JSON document");
            }
            if (!rpc.isObject())
                return error(id, -32600, "A single JSON-RPC request object is required");
            try {
                KnowledgeJson.fields(rpc, "jsonrpc", "id", "method", "params");
            } catch (IllegalArgumentException invalid) {
                return error(id, -32600, "Unsupported JSON-RPC request fields");
            }
            if (!"2.0".equals(rpc.path("jsonrpc").asText()))
                return error(id, -32600, "JSON-RPC 2.0 is required");
            if (rpc.has("id")) {
                id = rpc.get("id");
                if (!(id.isNull()
                        || id.isTextual() && id.asText().length() <= 120
                        || id.isIntegralNumber()))
                    return error(json.parse("null"), -32600, "Unsupported request id");
            }
            String method = KnowledgeJson.text(rpc, "method", 120);
            JsonNode params = rpc.has("params") ? rpc.get("params") : json.object("{}");
            if (!params.isObject()) return error(id, -32602, "params must be an object");
            if (method.equals("notifications/initialized") && !rpc.has("id"))
                return ResponseEntity.accepted().header("Cache-Control", "no-store").build();
            if (!rpc.has("id"))
                return ResponseEntity.accepted().header("Cache-Control", "no-store").build();
            Object result;
            switch (method) {
                case "initialize" -> {
                    KnowledgeJson.fields(params, "protocolVersion", "capabilities", "clientInfo");
                    String proposed = KnowledgeJson.text(params, "protocolVersion", 30);
                    result =
                            Map.of(
                                    "protocolVersion",
                                    VERSIONS.contains(proposed) ? proposed : "2025-03-26",
                                    "capabilities",
                                    Map.of("tools", Map.of("listChanged", false)),
                                    "serverInfo",
                                    Map.of("name", "awe-data-capabilities", "version", "0.1.0"),
                                    "instructions",
                                    "Use registered data capabilities. Resolution, authorization, readiness, execution and verification remain separate; no overall business-task success is certified.");
                }
                case "ping" -> {
                    KnowledgeJson.fields(params);
                    result = Map.of();
                }
                case "tools/list" -> {
                    KnowledgeJson.fields(params);
                    result = Map.of("tools", tools());
                }
                case "tools/call" -> {
                    KnowledgeJson.fields(params, "name", "arguments");
                    String name = KnowledgeJson.text(params, "name", 80);
                    JsonNode arguments = params.path("arguments");
                    if (!arguments.isObject())
                        throw new IllegalArgumentException("Tool arguments must be an object");
                    ConsumerAccess.Grant grant = KnowledgeHttp.grant(request);
                    Object output =
                            switch (name) {
                                case "awe_capabilities" -> {
                                    KnowledgeJson.fields(arguments);
                                    yield Map.of("capabilities", operations.capabilities(grant));
                                }
                                case "awe_resolve" -> knowledge.resolve(arguments, grant);
                                case "awe_assess" -> knowledge.assess(arguments, grant);
                                case "awe_query_metric" -> {
                                    if (arguments.path("protocolVersion").asInt() != 2
                                            || !"QUERY_METRIC"
                                                    .equals(arguments.path("intent").asText()))
                                        throw new IllegalArgumentException(
                                                "This tool requires a QUERY_METRIC v2 request");
                                    yield operations.create(
                                            arguments, grant, KnowledgeHttp.identity(request));
                                }
                                case "awe_extract_dataset" -> {
                                    if (arguments.path("protocolVersion").asInt() != 1
                                            || !"EXTRACT".equals(arguments.path("intent").asText()))
                                        throw new IllegalArgumentException(
                                                "This tool requires an EXTRACT v1 request");
                                    yield operations.create(
                                            arguments, grant, KnowledgeHttp.identity(request));
                                }
                                case "awe_get_operation" -> {
                                    KnowledgeJson.fields(arguments, "operationId");
                                    yield operations.get(
                                            KnowledgeJson.uuid(arguments, "operationId"), grant);
                                }
                                case "awe_report_feedback" -> feedback.report(arguments, grant);
                                case "awe_validate_plan" ->
                                        operations.validatePlan(arguments, grant);
                                default ->
                                        throw new IllegalArgumentException(
                                                "Unregistered data tool");
                            };
                    JsonNode view = json.parse(json.write(output));
                    boolean failed =
                            Set.of(
                                            "BLOCKED",
                                            "NOT_CONFIGURED",
                                            "UNAVAILABLE",
                                            "INCONCLUSIVE",
                                            "NOT_READY",
                                            "FAILED_VERIFICATION")
                                    .contains(view.path("status").asText());
                    result =
                            Map.of(
                                    "content",
                                    List.of(Map.of("type", "text", "text", json.write(output))),
                                    "structuredContent",
                                    output,
                                    "isError",
                                    failed);
                }
                default -> {
                    return error(id, -32601, "Method is not supported");
                }
            }
            return ok(id, result);
        } catch (NoSuchElementException ex) {
            return error(id, -32004, "Requested data reference was not found in authorized scope");
        } catch (ResponseStatusException ex) {
            return error(
                    id,
                    -32003,
                    "The current identity or data policy does not authorize this operation");
        } catch (IllegalArgumentException ex) {
            return error(id, -32602, ex.getMessage());
        } catch (IllegalStateException ex) {
            return error(id, -32009, "The operation conflicts with its current immutable state");
        }
    }

    private List<Map<String, Object>> tools() {
        var tools = new ArrayList<Map<String, Object>>();
        tools.add(
                tool(
                        "awe_capabilities",
                        "Discover only the current principal's registered data capabilities",
                        Map.of(),
                        List.of()));
        var resolution =
                Map.<String, Object>of(
                        "text",
                        Map.of("type", "string"),
                        "domain",
                        Map.of("type", "string"),
                        "intent",
                        Map.of("enum", List.of("EXTRACT", "QUERY_METRIC")),
                        "functionId",
                        Map.of("type", "string"),
                        "releaseId",
                        Map.of("type", "string"),
                        "contentHash",
                        Map.of("type", "string"));
        tools.add(
                tool(
                        "awe_resolve",
                        "Resolve published data meaning or return candidates for clarification; a miss is not enterprise absence",
                        resolution,
                        List.of("intent")));
        tools.add(
                tool(
                        "awe_assess",
                        "Assess registered authorized capability scope separately from current data readiness",
                        resolution,
                        List.of("intent")));
        var task =
                Map.<String, Object>of(
                        "protocolVersion",
                        Map.of("type", "integer"),
                        "intent",
                        Map.of("type", "string"),
                        "externalTaskRef",
                        Map.of("type", "object"),
                        "proposedFunction",
                        Map.of("type", "object"),
                        "requirements",
                        Map.of("type", "object"),
                        "consumer",
                        Map.of("type", "object"),
                        "constraints",
                        Map.of("type", "object"),
                        "idempotencyKey",
                        Map.of("type", "string"));
        var required =
                List.of(
                        "protocolVersion",
                        "intent",
                        "proposedFunction",
                        "requirements",
                        "consumer",
                        "constraints",
                        "idempotencyKey");
        tools.add(
                tool(
                        "awe_query_metric",
                        "Execute one published QUERY_METRIC v2 under the same authorization, plan and evidence gates",
                        task,
                        required));
        tools.add(
                tool(
                        "awe_extract_dataset",
                        "Execute one published EXTRACT v1; no arbitrary SQL or scripts",
                        task,
                        required));
        tools.add(
                tool(
                        "awe_get_operation",
                        "Read the caller's own operation under its current policy",
                        Map.of("operationId", Map.of("type", "string")),
                        List.of("operationId")));
        tools.add(
                tool(
                        "awe_report_feedback",
                        "Record a caller report tied to actual data or resolution evidence; it does not self-certify root cause",
                        Map.of(
                                "observationId",
                                Map.of("type", "string"),
                                "issueCode",
                                Map.of("type", "string"),
                                "stage",
                                Map.of("type", "string"),
                                "description",
                                Map.of("type", "string")),
                        List.of("observationId", "issueCode", "stage", "description")));
        tools.add(
                tool(
                        "awe_validate_plan",
                        "Validate the server-resolved published metric plan, without granting execution by caller assertion",
                        task,
                        required));
        return tools;
    }

    private Map<String, Object> tool(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required) {
        return Map.of(
                "name",
                name,
                "description",
                description,
                "inputSchema",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        required,
                        "additionalProperties",
                        false));
    }

    private ResponseEntity<String> ok(JsonNode id, Object result) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(json.write(Map.of("jsonrpc", "2.0", "id", id, "result", result)));
    }

    private ResponseEntity<String> error(JsonNode id, int code, String message) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        json.write(
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        id,
                                        "error",
                                        Map.of("code", code, "message", message))));
    }
}
