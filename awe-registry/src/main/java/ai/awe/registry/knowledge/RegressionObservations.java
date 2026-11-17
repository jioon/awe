package ai.awe.registry.knowledge;

import ai.awe.registry.consumption.*;
import ai.awe.registry.consumption.query.MetricQueryRequest;
import ai.awe.registry.consumption.query.SemanticMetricRequest;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 对格式正确的请求产生一次真实消费观测；原 API 的 HTTP 拒绝语义保持不变。 */
@Service
public class RegressionObservations {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final DataTaskOperations operations;
    private final ConsumerAccess access;

    public RegressionObservations(
            JdbcTemplate db,
            JsonDocuments json,
            DataTaskOperations operations,
            ConsumerAccess access) {
        this.db = db;
        this.json = json;
        this.operations = operations;
        this.access = access;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> create(
            JsonNode body, ConsumerAccess.Grant grant, Map<String, Object> identity) {
        KnowledgeJson.fields(body, "request", "idempotencyKey");
        return observe(
                body.path("request"),
                KnowledgeJson.text(body, "idempotencyKey", 120),
                grant,
                identity);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> observe(
            JsonNode candidate,
            String key,
            ConsumerAccess.Grant grant,
            Map<String, Object> identity) {
        access.requireCurrent(grant);
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid observation idempotency key");
        if (json.write(candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Observation request exceeds 64 KiB");
        ObjectNode request =
                candidate.path("protocolVersion").asInt() == 3
                        ? SemanticMetricRequest.decode(candidate).normalized(json)
                        : candidate.path("protocolVersion").asInt() == 2
                                ? MetricQueryRequest.decode(candidate).normalized(json)
                                : DataTaskRequests.normalized(
                                        DataTaskRequests.decode(candidate), json);
        String hash = json.sha256(request);
        UUID id = UUID.randomUUID();
        int inserted =
                db.update(
                        "INSERT INTO awe_regression_observation(id,principal,policy_hash,idempotency_key,request_hash,request_doc,status) VALUES(?,?,?,?,?,?::jsonb,'RUNNING') ON CONFLICT(principal,idempotency_key) DO NOTHING",
                        id,
                        grant.principal(),
                        grant.policyHash(),
                        key,
                        hash,
                        json.canonical(request));
        if (inserted == 0) {
            var prior =
                    db.queryForMap(
                            "SELECT id::text,request_hash,policy_hash FROM awe_regression_observation WHERE principal=? AND idempotency_key=?",
                            grant.principal(),
                            key);
            if (!hash.equals(prior.get("request_hash"))
                    || !grant.policyHash().equals(prior.get("policy_hash")))
                throw new IllegalStateException(
                        "Observation key belongs to a different request or policy");
            return get(UUID.fromString((String) prior.get("id")), grant);
        }
        var submitted = request.deepCopy();
        submitted.put("idempotencyKey", "probe." + id);
        ObjectNode observed;
        try {
            observed = json.object(json.write(operations.create(submitted, grant, identity)));
        } catch (ResponseStatusException denied) {
            int http = denied.getStatusCode().value();
            if (http != 403) throw denied;
            observed = rejected(request, "DENIED", 403, "AUTHORIZATION", "DENIED");
        } catch (NoSuchElementException hidden) {
            observed = rejected(request, "NOT_DISCLOSED", 404, "RESOLUTION", "NOT_EVALUATED");
        } catch (IllegalArgumentException invalid) {
            observed = rejected(request, "REJECTED", 400, "PLANNING", "NOT_EVALUATED");
        }
        // 子操作可能尚在运行（例如跨进程幂等重试）；不能把缺少终态当成完成的拒绝。
        if (observed.path("status").asText().equals("RUNNING"))
            throw new IllegalStateException("Child data operation has no terminal evidence");
        JsonNode outcome = RegressionExpectations.actual(observed, json);
        String child = observed.path("id").asText();
        if (!child.isBlank()) observed.put("actualOperationId", child);
        observed.put("id", id.toString());
        observed.put("policyHash", grant.policyHash());
        observed.put("probeStatus", "OBSERVED");
        observed.put("payloadAvailable", false);
        observed.put("resultPayloadScope", "CHILD_OPERATION_ONLY");
        observed.set("request", request);
        observed.set("outcome", outcome);
        if (observed.path("result") instanceof ObjectNode result) result.remove("rows");
        db.update(
                "UPDATE awe_regression_observation SET status='OBSERVED',report=?::jsonb,completed_at=clock_timestamp() WHERE id=? AND status='RUNNING'",
                json.canonical(observed),
                id);
        return get(id, grant);
    }

    private ObjectNode rejected(
            JsonNode request, String status, int http, String stage, String authorization) {
        return json.object(
                json.write(
                        Map.of(
                                "status",
                                status,
                                "httpStatus",
                                http,
                                "request",
                                request,
                                "resolved",
                                Map.of(),
                                "decision",
                                Map.of(
                                        "resolution",
                                        "NOT_EVALUATED",
                                        "supply",
                                        "UNKNOWN",
                                        "authorization",
                                        authorization,
                                        "readiness",
                                        "UNKNOWN"),
                                "outcome",
                                Map.of(
                                        "status",
                                        status,
                                        "httpStatus",
                                        http,
                                        "decision",
                                        Map.of(
                                                "resolution",
                                                "NOT_EVALUATED",
                                                "supply",
                                                "UNKNOWN",
                                                "authorization",
                                                authorization,
                                                "readiness",
                                                "UNKNOWN"),
                                        "failureStage",
                                        stage,
                                        "noDataReturned",
                                        true),
                                "limitations",
                                List.of(
                                        "This is a fresh authenticated request observation; no hidden capability metadata or data was disclosed",
                                        "An expected refusal is not evidence that data has been repaired"))));
    }

    public Map<String, Object> get(UUID id, ConsumerAccess.Grant grant) {
        access.requireCurrent(grant);
        var rows =
                db.queryForList(
                        "SELECT id::text,principal,policy_hash,request_doc::text,status,report::text,created_at,completed_at FROM awe_regression_observation WHERE id=? AND principal=? AND policy_hash=?",
                        id,
                        grant.principal(),
                        grant.policyHash());
        if (rows.isEmpty())
            throw new NoSuchElementException("Regression observation not found in current scope");
        var row = rows.getFirst();
        var result = new LinkedHashMap<String, Object>();
        if ("RUNNING".equals(row.get("status"))) {
            result.put("id", row.get("id"));
            result.put("status", "RUNNING");
            result.put("request", json.parse((String) row.get("request_doc")));
            result.put("policyHash", row.get("policy_hash"));
        } else
            json.parse((String) row.get("report"))
                    .fields()
                    .forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
        result.put("createdAt", row.get("created_at"));
        result.put("completedAt", row.get("completed_at"));
        return result;
    }
}
