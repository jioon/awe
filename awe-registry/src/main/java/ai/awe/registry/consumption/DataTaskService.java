package ai.awe.registry.consumption;

import ai.awe.kernel.capability.DataTask;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.authoring.ModelRegistry;
import ai.awe.registry.consumption.jdbc.PostgresDatasetExtractor;
import ai.awe.registry.model.DatasetExtractProfile;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 正式发布约束一次真实数据消费；不管理外部工作流，也不裁决整体业务成功。 */
@Service
public final class DataTaskService {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final ModelRegistry registry;
    private final ConsumerAccess access;
    private final PostgresDatasetExtractor extractor;
    private final TransactionTemplate transactions;
    private final int resultTtlSeconds;
    private final TypedModels typed = new TypedModels();
    private final Semaphore globalSlots = new Semaphore(4);
    private final Map<String, Semaphore> principalSlots = new ConcurrentHashMap<>();

    public DataTaskService(
            JdbcTemplate db,
            JsonDocuments json,
            ModelRegistry registry,
            ConsumerAccess access,
            PostgresDatasetExtractor extractor,
            Environment environment,
            PlatformTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.registry = registry;
        this.access = access;
        this.extractor = extractor;
        this.transactions = new TransactionTemplate(transactionManager);
        String ttl = environment.getProperty("AWE_CONSUMPTION_RESULT_TTL_SECONDS", "900");
        if (!ttl.matches("[1-9][0-9]{1,3}")
                || Integer.parseInt(ttl) < 60
                || Integer.parseInt(ttl) > 3600)
            throw new IllegalArgumentException(
                    "AWE_CONSUMPTION_RESULT_TTL_SECONDS must be between 60 and 3600");
        resultTtlSeconds = Integer.parseInt(ttl);
    }

    private record Resolved(
            Map<String, Object> functionRelease,
            Map<String, Object> datasetRelease,
            Map<String, Object> schemaRelease,
            Map<String, Object> subjectRelease,
            FunctionDefinition function,
            SemanticModel.Dataset dataset,
            ObjectType schema,
            ObjectType subject,
            JsonNode binding) {}

    public List<Map<String, Object>> capabilities(ConsumerAccess.Grant grant) {
        if (grant.functionIds().isEmpty()) return List.of();
        String placeholders =
                String.join(",", Collections.nCopies(grant.functionIds().size(), "?"));
        var rows =
                db.queryForList(
                        "SELECT id::text FROM awe_release WHERE entity_type='FunctionDefinition' AND entity_id IN ("
                                + placeholders
                                + ") ORDER BY published_at DESC LIMIT 100",
                        grant.functionIds().toArray());
        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            Resolved resolved;
            try {
                resolved = resolve(UUID.fromString((String) row.get("id")), null, grant);
            } catch (IllegalArgumentException | NoSuchElementException ex) {
                continue;
            }
            var fields =
                    resolved.schema().properties().stream()
                            .filter(
                                    p ->
                                            !sensitive(resolved, p.name())
                                                    && grant.fields().contains(p.name()))
                            .map(
                                    p ->
                                            Map.<String, Object>of(
                                                    "name",
                                                    p.name(),
                                                    "type",
                                                    p.type().name(),
                                                    "required",
                                                    p.required(),
                                                    "sensitive",
                                                    false))
                            .toList();
            if (fields.isEmpty()) continue;
            var capability = new LinkedHashMap<String, Object>();
            capability.put("releaseId", resolved.functionRelease().get("id"));
            capability.put("contentHash", resolved.functionRelease().get("content_hash"));
            capability.put("functionId", resolved.function().ref().id());
            capability.put(
                    "name",
                    ((JsonNode) resolved.functionRelease().get("spec")).path("name").asText());
            capability.put("dataset", pointer(resolved.datasetRelease()));
            capability.put("rowSchema", pointer(resolved.schemaRelease()));
            capability.put("fields", fields);
            capability.put(
                    "time",
                    Map.of(
                            "businessZone",
                            resolved.dataset().time().businessZone().getId(),
                            "role",
                            "EVENT_TIME"));
            capability.put(
                    "limits",
                    Map.of(
                            "maxRows",
                            grant.maxRows(),
                            "maxBytes",
                            grant.maxBytes(),
                            "maxWindowSeconds",
                            grant.maxWindowSeconds()));
            capability.put("purposes", grant.purposes().stream().sorted().toList());
            capability.put("supply", "DIRECT");
            capability.put("readiness", "UNKNOWN");
            capability.put("view", "OBSERVED_SNAPSHOT");
            capability.put(
                    "limitations",
                    List.of(
                            "Registered capability only; connectivity, current source consistency and period readiness are not established by discovery",
                            "Explicit release resolution only; no natural-language resolver"));
            result.add(capability);
        }
        return result;
    }

    public Map<String, Object> create(JsonNode body, ConsumerAccess.Grant grant) {
        DataTask task = DataTaskRequests.decode(body);
        Resolved resolved =
                resolve(
                        task.proposedFunction().releaseId(),
                        task.proposedFunction().contentHash(),
                        grant);
        authorize(task, grant, resolved);
        JsonNode request = DataTaskRequests.normalized(task, json);
        String requestHash = json.sha256(request);
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("function", pointer(resolved.functionRelease()));
        manifest.put("dataset", pointer(resolved.datasetRelease()));
        manifest.put("schema", pointer(resolved.schemaRelease()));
        manifest.put("subject", pointer(resolved.subjectRelease()));
        manifest.put("requirements", DataTaskRequests.requirements(task));
        manifest.put("timeRole", "EVENT_TIME");
        manifest.put("businessZone", resolved.dataset().time().businessZone().getId());
        manifest.put("mapping", "SAME_NAME_COLUMNS");
        manifest.put("executor", DatasetExtractProfile.EXECUTOR);
        String planHash = json.sha256(json.object(json.write(manifest)));
        manifest.put("planHash", planHash);
        UUID id = UUID.randomUUID();
        // 请求落库后才读取数据；相同主体/幂等键只能关联一个固定载荷。
        int inserted =
                db.update(
                        "INSERT INTO awe_data_task(id,principal,idempotency_key,request_hash,request_doc,function_release_id,function_content_hash,policy_hash,resolved,status) VALUES(?,?,?,?,?::jsonb,?,?,?,?::jsonb,'RUNNING') ON CONFLICT(principal,idempotency_key) DO NOTHING",
                        id,
                        grant.principal(),
                        task.idempotencyKey(),
                        requestHash,
                        json.canonical(request),
                        task.proposedFunction().releaseId(),
                        task.proposedFunction().contentHash(),
                        grant.policyHash(),
                        json.write(manifest));
        if (inserted == 0) {
            var previous =
                    db.queryForMap(
                            "SELECT id::text,request_hash FROM awe_data_task WHERE principal=? AND idempotency_key=?",
                            grant.principal(),
                            task.idempotencyKey());
            if (!requestHash.equals(previous.get("request_hash")))
                throw new IllegalStateException(
                        "Idempotency key belongs to a different data request");
            return get(UUID.fromString((String) previous.get("id")), grant);
        }

        var limits = new ArrayList<String>();
        limits.add(
                "Rows are limited to this PostgreSQL transaction's visible relation and explicit half-open EVENT_TIME range; period completeness and reporting coverage are UNKNOWN");
        limits.add(
                "Structural schema/type and projection checks only; no independent numerical reconciliation, global business-truth or overall task-success certification");
        limits.add(
                "Snapshot reference is evidence of this read, not a restorable historical data version");
        Map<String, Object> gate = Map.of("status", "NOT_RUN");
        PostgresDatasetExtractor.Result extracted = null;
        String status = "BLOCKED";
        boolean globalAcquired = false, principalAcquired = false;
        Semaphore ownSlot =
                principalSlots.computeIfAbsent(grant.principal(), key -> new Semaphore(1));
        try {
            principalAcquired = ownSlot.tryAcquire();
            if (principalAcquired) globalAcquired = globalSlots.tryAcquire();
            if (!principalAcquired || !globalAcquired) {
                limits.add("Concurrent data-operation budget exceeded; no source read was started");
            } else {
                // 不使用发布时的 VERIFIED 作为永久许可；每次新调用重新观测来源。
                gate =
                        registry.verifyRelease(
                                UUID.fromString((String) resolved.datasetRelease().get("id")),
                                grant.principal());
                if (!"VERIFIED".equals(gate.get("status"))) {
                    status =
                            "NOT_CONFIGURED".equals(gate.get("status"))
                                    ? "NOT_CONFIGURED"
                                    : "UNAVAILABLE".equals(gate.get("status"))
                                            ? "UNAVAILABLE"
                                            : "BLOCKED";
                    limits.add(
                            "Current source gate did not establish the pinned implementation: "
                                    + gate.get("status"));
                } else {
                    extracted =
                            extractor.execute(
                                    new PostgresDatasetExtractor.Plan(
                                            resolved.dataset(),
                                            resolved.schema(),
                                            resolved.binding(),
                                            task.requirements().fields(),
                                            task.requirements().from(),
                                            task.requirements().to(),
                                            task.constraints().maxRows(),
                                            task.constraints().maxBytes()));
                    status = extracted.status();
                    limits.addAll(extracted.limitations());
                }
            }
        } catch (RuntimeException ex) {
            status = "UNAVAILABLE";
            // 禁止把原始异常、连接串、行值或凭据带入消费者证据。
            limits.add(
                    "The registered source could not complete this operation; no successful data result was produced");
        } finally {
            if (globalAcquired) globalSlots.release();
            if (principalAcquired) ownSlot.release();
        }
        boolean successful = "EXTRACTED".equals(status);
        List<Map<String, Object>> data =
                successful && extracted != null ? extracted.rows() : List.of();
        Map<String, Object> result =
                Map.of(
                        "state",
                        successful ? (data.isEmpty() ? "NO_RECORD" : "RECORDS") : "UNAVAILABLE",
                        "rowCount",
                        data.size(),
                        "scope",
                        "OBSERVED_SNAPSHOT",
                        "completeForObservedRange",
                        successful,
                        "periodCompleteness",
                        "UNKNOWN");
        var report = new LinkedHashMap<String, Object>();
        report.put(
                "decision",
                Map.of(
                        "resolution",
                        "RESOLVED",
                        "supply",
                        "DIRECT",
                        "authorization",
                        "ALLOWED",
                        "readiness",
                        "UNKNOWN"));
        report.put(
                "execution",
                successful ? "COMPLETED" : extracted == null ? "NOT_RUN" : "NOT_COMPLETED");
        report.put("result", result);
        report.put("resultHash", json.sha256(json.parse(json.write(data))));
        report.put("data_snapshot_ref", extracted == null ? "" : extracted.dataSnapshotRef());
        report.put("gate_report", publicGate(gate));
        report.put(
                "verification",
                Map.of(
                        "structural",
                        successful ? "PASSED" : "NOT_ESTABLISHED",
                        "independentReconciliation",
                        "NOT_RUN",
                        "businessAcceptance",
                        "EXTERNAL"));
        report.put("limitations", limits);
        String finalStatus = status;
        // 载荷可独立过期删除；接受上下文、结果摘要和 Hash 作为不可改证据原子完成。
        transactions.executeWithoutResult(
                tx -> {
                    if (successful) {
                        String expiresAt =
                                db.queryForObject(
                                        "INSERT INTO awe_data_task_result(operation_id,rows_doc,expires_at) VALUES(?,?::jsonb,clock_timestamp()+(? * interval '1 second')) RETURNING expires_at::text",
                                        String.class,
                                        id,
                                        json.write(data),
                                        resultTtlSeconds);
                        report.put("resultExpiresAt", expiresAt);
                    }
                    db.update(
                            "UPDATE awe_data_task SET status=?,report=?::jsonb,completed_at=clock_timestamp() WHERE id=? AND status='RUNNING'",
                            finalStatus,
                            json.write(report),
                            id);
                });
        return get(id, grant);
    }

    public Map<String, Object> get(UUID id, ConsumerAccess.Grant grant) {
        var row = stored(id, grant.principal());
        DataTask task = DataTaskRequests.decode(json.parse((String) row.get("request_doc")));
        JsonNode manifest = json.parse((String) row.get("resolved"));
        try {
            access.authorize(
                    grant,
                    manifest.path("function").path("entityId").asText(),
                    task.consumer().purpose(),
                    task.requirements().fields(),
                    task.constraints().maxRows(),
                    task.constraints().maxBytes(),
                    task.requirements().from(),
                    task.requirements().to());
        } catch (ResponseStatusException ex) {
            throw new NoSuchElementException(
                    "Data operation not found in current authorized scope");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id"));
        result.put("principal", row.get("principal"));
        result.put("status", row.get("status"));
        result.put("policyHash", row.get("policy_hash"));
        result.put("requestHash", row.get("request_hash"));
        result.put("request", json.parse((String) row.get("request_doc")));
        result.put("resolved", manifest);
        result.put("createdAt", row.get("created_at"));
        result.put("completedAt", row.get("completed_at"));
        JsonNode report = json.parse((String) row.get("report"));
        report.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
        if (report.path("result").isObject()) {
            var visibleResult =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            report.path("result").deepCopy();
            db.update(
                    "DELETE FROM awe_data_task_result WHERE operation_id=? AND expires_at<=clock_timestamp()",
                    id);
            var payload =
                    db.queryForList(
                            "SELECT rows_doc::text FROM awe_data_task_result WHERE operation_id=? AND expires_at>clock_timestamp()",
                            id);
            boolean available = !payload.isEmpty();
            JsonNode rows =
                    available
                            ? json.parse((String) payload.getFirst().get("rows_doc"))
                            : json.parse("[]");
            boolean invalid =
                    available && !json.sha256(rows).equals(report.path("resultHash").asText());
            if (invalid) available = false;
            result.put("payloadAvailable", available);
            visibleResult.set("rows", available ? rows : json.parse("[]"));
            if (!available && "EXTRACTED".equals(row.get("status"))) {
                visibleResult.put("recordedState", visibleResult.path("state").asText());
                visibleResult.put("state", invalid ? "INVALID" : "EXPIRED");
            }
            result.put("result", visibleResult);
        }
        if ("RUNNING".equals(row.get("status"))) {
            result.put(
                    "decision",
                    Map.of(
                            "resolution",
                            "RESOLVED",
                            "supply",
                            "DIRECT",
                            "authorization",
                            "ALLOWED",
                            "readiness",
                            "UNKNOWN"));
            result.put(
                    "limitations",
                    List.of(
                            "Operation has no terminal evidence; automatic cross-process recovery is not implemented"));
        }
        return result;
    }

    public List<Map<String, Object>> list(ConsumerAccess.Grant grant) {
        var rows =
                db.queryForList(
                        "SELECT id::text FROM awe_data_task WHERE principal=? ORDER BY created_at DESC LIMIT 100",
                        grant.principal());
        var visible = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            try {
                var operation = get(UUID.fromString((String) row.get("id")), grant);
                var summary = new LinkedHashMap<String, Object>();
                for (String key :
                        List.of(
                                "id",
                                "principal",
                                "status",
                                "resolved",
                                "createdAt",
                                "completedAt")) summary.put(key, operation.get(key));
                visible.add(summary);
            } catch (NoSuchElementException ignored) {
                // 当前策略收回的数据操作不在列表暴露，即使该主体曾经运行过。
            }
        }
        return visible;
    }

    private Map<String, Object> stored(UUID id, String principal) {
        var rows =
                db.queryForList(
                        "SELECT id::text,principal,status,policy_hash,request_hash,request_doc::text,resolved::text,report::text,created_at,completed_at FROM awe_data_task WHERE id=? AND principal=?",
                        id,
                        principal);
        if (rows.isEmpty()) throw new NoSuchElementException("Data operation not found");
        return rows.getFirst();
    }

    private void authorize(DataTask task, ConsumerAccess.Grant grant, Resolved resolved) {
        access.authorize(
                grant,
                resolved.function().ref().id(),
                task.consumer().purpose(),
                task.requirements().fields(),
                task.constraints().maxRows(),
                task.constraints().maxBytes(),
                task.requirements().from(),
                task.requirements().to());
        for (String name : task.requirements().fields()) {
            var property =
                    resolved.schema().properties().stream()
                            .filter(p -> p.name().equals(name))
                            .findFirst();
            if (property.isEmpty() || sensitive(resolved, name))
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Requested projection is outside supported data scope");
        }
    }

    private Resolved resolve(UUID id, String hash, ConsumerAccess.Grant grant) {
        String placeholders =
                String.join(",", Collections.nCopies(grant.functionIds().size(), "?"));
        var permitted = new ArrayList<Object>();
        permitted.add(id);
        permitted.addAll(grant.functionIds());
        // 不先读取权限外对象再返回不同错误，防止 UUID 被用作资产存在性探针。
        if (grant.functionIds().isEmpty()
                || db.queryForList(
                                "SELECT id FROM awe_release WHERE id=? AND entity_type='FunctionDefinition' AND entity_id IN ("
                                        + placeholders
                                        + ")",
                                permitted.toArray())
                        .isEmpty())
            throw new NoSuchElementException("Capability not found in authorized scope");
        var release = registry.release(id);
        if (!"FunctionDefinition".equals(release.get("entity_type"))
                || !Integer.valueOf(2).equals(release.get("schema_version")))
            throw new IllegalArgumentException("A typed FunctionDefinition release is required");
        if (hash != null && !hash.equals(release.get("content_hash")))
            throw new IllegalArgumentException(
                    "Function contentHash does not match the pinned release");
        var function =
                (FunctionDefinition)
                        typed.decode("FunctionDefinition", (JsonNode) release.get("spec"));
        if (!DatasetExtractProfile.matches(function))
            throw new IllegalArgumentException(
                    "Function has no supported registered data executor");
        var datasetRelease = dependency(release, function.ownerType(), "Dataset");
        var schemaRelease =
                dependency(
                        release,
                        DatasetExtractProfile.parseResultRef(function.resultSchemaRef()),
                        "ObjectType");
        var dataset =
                (SemanticModel.Dataset)
                        typed.decode("Dataset", (JsonNode) datasetRelease.get("spec"));
        var schema = (ObjectType) typed.decode("ObjectType", (JsonNode) schemaRelease.get("spec"));
        var subjectRelease = dependency(datasetRelease, dataset.subjectType(), "ObjectType");
        var subject =
                (ObjectType) typed.decode("ObjectType", (JsonNode) subjectRelease.get("spec"));
        DatasetExtractProfile.validate(
                function, dataset, schema, (JsonNode) datasetRelease.get("spec"));
        DatasetExtractProfile.validateSubject(schema, subject);
        JsonNode binding = ((JsonNode) datasetRelease.get("spec")).path("bindings").get(0);
        if (!"postgres".equals(binding.path("sourceSystem").asText()))
            throw new IllegalArgumentException(
                    "Registered extractor supports only an explicitly bound PostgreSQL source");
        return new Resolved(
                release,
                datasetRelease,
                schemaRelease,
                subjectRelease,
                function,
                dataset,
                schema,
                subject,
                binding);
    }

    private boolean sensitive(Resolved resolved, String field) {
        return java.util.stream.Stream.concat(
                        resolved.schema().properties().stream(),
                        resolved.subject().properties().stream())
                .anyMatch(p -> p.name().equals(field) && p.sensitive());
    }

    private Map<String, Object> dependency(
            Map<String, Object> release, EntityRef ref, String type) {
        var relations = (List<Map<String, Object>>) release.get("relations");
        var pinned =
                relations.stream()
                        .filter(
                                r ->
                                        ref.id().equals(r.get("target_entity_id"))
                                                && ref.modelVersion()
                                                        .equals(r.get("target_version")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Capability is missing an immutable dependency manifest"));
        var target = registry.release(UUID.fromString((String) pinned.get("target_release_id")));
        if (!type.equals(target.get("entity_type"))
                || !Integer.valueOf(2).equals(target.get("schema_version"))
                || !ref.id().equals(target.get("entity_id"))
                || !ref.modelVersion().equals(target.get("version"))
                || !pinned.get("target_content_hash").equals(target.get("content_hash")))
            throw new IllegalArgumentException(
                    "Pinned dependency identity/type/hash is inconsistent");
        return target;
    }

    private Map<String, Object> pointer(Map<String, Object> release) {
        return Map.of(
                "releaseId",
                release.get("id"),
                "contentHash",
                release.get("content_hash"),
                "entityId",
                release.get("entity_id"),
                "modelVersion",
                release.get("version"));
    }

    private Map<String, Object> publicGate(Map<String, Object> gate) {
        var checks = new ArrayList<Map<String, Object>>();
        Object value = gate.get("checks");
        if (value instanceof List<?> list)
            for (Object item : list)
                if (item instanceof Map<?, ?> check) {
                    var proof = new LinkedHashMap<String, Object>();
                    for (String key :
                            List.of(
                                    "status",
                                    "sourceSystem",
                                    "externalRef",
                                    "fingerprint",
                                    "snapshotId",
                                    "sourceVersion"))
                        if (check.get(key) != null) proof.put(key, check.get(key));
                    checks.add(proof);
                }
        return Map.of("status", gate.getOrDefault("status", "NOT_RUN"), "checks", checks);
    }
}
