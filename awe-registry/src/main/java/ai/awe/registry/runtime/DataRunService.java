package ai.awe.registry.runtime;

import ai.awe.kernel.model.ContractDefinition;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.authoring.ModelRegistry;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.runtime.jdbc.JdbcContractExecutor;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 模型约束进入真实数据读取；这不是 Agent 工作流引擎，也不执行数仓写操作。 */
@Service
public class DataRunService {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final ModelRegistry registry;
    private final JdbcContractExecutor executor;
    private final TransactionTemplate transactions;
    private final TypedModels typed = new TypedModels();

    public DataRunService(
            JdbcTemplate db,
            JsonDocuments json,
            ModelRegistry registry,
            JdbcContractExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.registry = registry;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> create(JsonNode request, String actor) {
        fields(
                request,
                Set.of(
                        "releaseId",
                        "contentHash",
                        "contractReleaseId",
                        "contractContentHash",
                        "idempotencyKey"));
        return execute(request, actor, null, "");
    }

    public Map<String, Object> replay(UUID id, JsonNode request, String actor) {
        fields(request, Set.of("idempotencyKey", "repairNote"));
        var previous = get(id);
        if ("RUNNING".equals(previous.get("status")))
            throw new IllegalStateException(
                    "Original run is still RUNNING; do not assume it completed");
        if ("PASSED".equals(previous.get("status")))
            throw new IllegalArgumentException("A successful run does not need repair replay");
        String note = text(request, "repairNote");
        if (note.length() > 2000) throw new IllegalArgumentException("repairNote is too long");
        var body = json.object(json.write(previous.get("request_doc")));
        body.put("idempotencyKey", text(request, "idempotencyKey"));
        UUID original =
                previous.get("replay_of") == null
                        ? id
                        : UUID.fromString((String) previous.get("replay_of"));
        return transactions.execute(
                tx -> {
                    // 同一原始任务的重验串行化，防止较旧结果覆盖较新的问题状态。
                    db.queryForList(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                            "data-replay:" + original);
                    return execute(body, actor, original, note);
                });
    }

    private Map<String, Object> execute(
            JsonNode request, String actor, UUID original, String note) {
        UUID modelId = uuid(request, "releaseId"), contractId = uuid(request, "contractReleaseId");
        String key = text(request, "idempotencyKey");
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid idempotencyKey");
        var modelRelease = registry.release(modelId);
        var contractRelease = registry.release(contractId);
        if (!text(request, "contentHash").equals(modelRelease.get("content_hash"))
                || !text(request, "contractContentHash")
                        .equals(contractRelease.get("content_hash")))
            throw new IllegalArgumentException(
                    "Pinned model/contract content Hash does not match release");
        if (!"Dataset".equals(modelRelease.get("entity_type"))
                || !"ContractDefinition".equals(contractRelease.get("entity_type")))
            throw new IllegalArgumentException(
                    "This registered capability requires a typed Dataset and ContractDefinition");
        var model =
                (SemanticModel.Dataset)
                        typed.decode("Dataset", (JsonNode) modelRelease.get("spec"));
        var contract =
                (ContractDefinition)
                        typed.decode("ContractDefinition", (JsonNode) contractRelease.get("spec"));
        if (!contract.subject().equals(model.ref()))
            throw new IllegalArgumentException("Contract must pin this exact Dataset version");
        var rules = new TreeSet<String>();
        var limits = new ArrayList<String>();
        for (var obligation : contract.obligations()) {
            if (obligation.at() == ContractDefinition.Enforcement.EXECUTE) {
                if (!RuntimeRules.DATA_CHECKS.contains(obligation.ruleId()))
                    throw new IllegalArgumentException(
                            "No registered executor for contract rule: " + obligation.ruleId());
                rules.add(obligation.ruleId());
            } else
                limits.add(
                        "Not executed in this data run: "
                                + obligation.ruleId()
                                + " at "
                                + obligation.at());
        }
        if (rules.isEmpty())
            throw new IllegalArgumentException("Contract has no supported EXECUTE data checks");
        JsonNode bindings = ((JsonNode) modelRelease.get("spec")).path("bindings");
        if (bindings.size() != 1)
            throw new IllegalArgumentException(
                    "This capability requires exactly one physical source binding");
        var normalized =
                json.object(
                        json.write(
                                Map.of(
                                        "releaseId",
                                        modelId.toString(),
                                        "contentHash",
                                        modelRelease.get("content_hash"),
                                        "contractReleaseId",
                                        contractId.toString(),
                                        "contractContentHash",
                                        contractRelease.get("content_hash"))));
        String requestHash =
                json.sha256(
                        json.object(
                                json.write(
                                        Map.of(
                                                "request",
                                                normalized,
                                                "replayOf",
                                                original == null ? "" : original.toString(),
                                                "repairNote",
                                                note))));
        UUID id = UUID.randomUUID();
        // 唯一键先落库：重复提交返回同一次任务，不重读数据；不同载荷复用键直接冲突。
        int inserted =
                db.update(
                        "INSERT INTO awe_data_run(id,actor,idempotency_key,request_hash,request_doc,model_release_id,model_content_hash,contract_release_id,contract_content_hash,replay_of,repair_note,status) VALUES(?,?,?,?,?::jsonb,?,?,?,?,?,?,'RUNNING') ON CONFLICT(actor,idempotency_key) DO NOTHING",
                        id,
                        actor,
                        key,
                        requestHash,
                        json.canonical(normalized),
                        modelId,
                        modelRelease.get("content_hash"),
                        contractId,
                        contractRelease.get("content_hash"),
                        original,
                        note);
        if (inserted == 0) {
            var existing =
                    db.queryForMap(
                            "SELECT id::text,request_hash FROM awe_data_run WHERE actor=? AND idempotency_key=?",
                            actor,
                            key);
            if (!requestHash.equals(existing.get("request_hash")))
                throw new IllegalStateException(
                        "Idempotency key already belongs to a different request");
            return get(UUID.fromString((String) existing.get("id")));
        }
        String status;
        String snapshot = "";
        String dataSnapshot = "";
        int rows = 0;
        Map<String, Long> violations = Map.of();
        Map<String, Object> gate = Map.of();
        limits.add(
                "Read-only registered data checks only; no SQL mutation, BI numeric equivalence or global business-truth claim");
        try {
            // 每次新任务重新检查权威来源；发布时的 VERIFIED 不是永久运行许可。
            gate = registry.verifyRelease(modelId, actor);
            if (!"VERIFIED".equals(gate.get("status"))) {
                status = "NOT_CONFIGURED".equals(gate.get("status")) ? "NOT_CONFIGURED" : "BLOCKED";
                limits.add("Source gate: " + gate.get("status"));
            } else {
                var checks = (List<Map<String, Object>>) gate.get("checks");
                snapshot =
                        (String)
                                checks.stream()
                                        .filter(c -> Boolean.TRUE.equals(c.get("direct")))
                                        .findFirst()
                                        .orElseThrow()
                                        .get("snapshotId");
                var result = executor.execute(model, bindings.get(0), rules);
                status = result.status();
                dataSnapshot = result.dataSnapshotRef();
                rows = result.rowsChecked();
                violations = result.violations();
                limits.addAll(result.limitations());
            }
        } catch (RuntimeException ex) {
            status = "UNAVAILABLE";
            // 不持久化 JDBC 连接串、原始行或异常堆栈，避免凭据和业务值泄露。
            limits.add(
                    "The registered source/checker could not complete; no successful evidence was produced");
        }
        String resultStatus = status, sourceSnapshot = snapshot, finalDataSnapshot = dataSnapshot;
        int finalRows = rows;
        Map<String, Long> finalViolations = violations;
        Map<String, Object> finalGate = gate;
        // 任务终态和问题状态同事务提交；旧证据永不改写，重验形成新的任务与新数据快照。
        transactions.executeWithoutResult(
                tx -> {
                    boolean canClose = true;
                    if (original != null && "PASSED".equals(resultStatus)) {
                        var proof =
                                db.queryForList(
                                        "SELECT r.data_snapshot_ref FROM awe_data_gap g JOIN awe_data_run r ON r.id=g.failed_run_id WHERE g.original_run_id=?",
                                        original);
                        if (!proof.isEmpty()
                                && !DataRunScope.sameProjection(
                                        (String) proof.getFirst().get("data_snapshot_ref"),
                                        finalDataSnapshot)) {
                            canClose = false;
                            limits.add(
                                    "Physical projection changed; this pass cannot close the original relation's problem");
                        }
                    }
                    db.update(
                            "UPDATE awe_data_run SET status=?,source_snapshot_id=?::uuid,data_snapshot_ref=?,rows_checked=?,violations=?::jsonb,limitations=?::jsonb,gate_report=?::jsonb,completed_at=clock_timestamp() WHERE id=?",
                            resultStatus,
                            sourceSnapshot.isBlank() ? null : sourceSnapshot,
                            finalDataSnapshot,
                            finalRows,
                            json.write(finalViolations),
                            json.write(limits),
                            json.write(finalGate),
                            id);
                    UUID origin = original == null ? id : original;
                    if ("FAILED".equals(resultStatus))
                        db.update(
                                "INSERT INTO awe_data_gap(id,original_run_id,failed_run_id,reason) VALUES(?,?,?,'DATA_CONTRACT_VIOLATION') ON CONFLICT(original_run_id) DO UPDATE SET status='OPEN',closed_run_id=NULL,closed_at=NULL",
                                UUID.randomUUID(),
                                origin,
                                id);
                    if (original != null && "PASSED".equals(resultStatus) && canClose)
                        db.update(
                                "UPDATE awe_data_gap SET status='CLOSED',closed_run_id=?,closed_at=clock_timestamp() WHERE original_run_id=? AND status='OPEN'",
                                id,
                                origin);
                });
        return get(id);
    }

    public List<Map<String, Object>> list() {
        return db.queryForList(
                "SELECT id::text,status,model_release_id::text,model_content_hash,contract_release_id::text,contract_content_hash,replay_of::text,rows_checked,created_at,completed_at FROM awe_data_run ORDER BY created_at DESC LIMIT 200");
    }

    public Map<String, Object> get(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id::text,actor,idempotency_key,request_hash,request_doc::text,model_release_id::text,model_content_hash,contract_release_id::text,contract_content_hash,replay_of::text,repair_note,status,source_snapshot_id::text,data_snapshot_ref,rows_checked,violations::text,limitations::text,gate_report::text,created_at,completed_at FROM awe_data_run WHERE id=?",
                        id);
        if (rows.isEmpty()) throw new NoSuchElementException("Data run not found");
        var result = new LinkedHashMap<>(rows.getFirst());
        for (String field : List.of("request_doc", "violations", "limitations", "gate_report"))
            result.put(field, json.parse((String) result.get(field)));
        return result;
    }

    public List<Map<String, Object>> gaps() {
        return db.queryForList(
                "SELECT id::text,original_run_id::text,failed_run_id::text,status,reason,closed_run_id::text,created_at,closed_at FROM awe_data_gap ORDER BY created_at DESC LIMIT 200");
    }

    private void fields(JsonNode request, Set<String> allowed) {
        if (!request.isObject()) throw new IllegalArgumentException("Request must be an object");
        request.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!allowed.contains(k))
                                throw new IllegalArgumentException(
                                        "Unsupported request field: " + k);
                        });
    }

    private String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank())
            throw new IllegalArgumentException("Missing " + key);
        return node.path(key).asText();
    }

    private UUID uuid(JsonNode node, String key) {
        try {
            return UUID.fromString(text(node, key));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid UUID: " + key);
        }
    }
}
