package ai.awe.registry.knowledge;

import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.authoring.ModelRegistry;
import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.model.ModelCompatibility;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 修订模型需要显式受审替代；不改写旧义务，也不把局部参考提升为全局模型等价。 */
@Service
public class ObligationMigrations {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final FeedbackService feedback;
    private final ModelRegistry registry;
    private final TypedModels typed = new TypedModels();

    public ObligationMigrations(
            JdbcTemplate db, JsonDocuments json, FeedbackService feedback, ModelRegistry registry) {
        this.db = db;
        this.json = json;
        this.feedback = feedback;
        this.registry = registry;
    }

    @Transactional
    public Map<String, Object> propose(
            UUID feedbackId, JsonNode request, ConsumerAccess.Grant grant) {
        KnowledgeJson.fields(
                request,
                "fromCaseId",
                "toCaseId",
                "reason",
                "referenceEvidenceRef",
                "idempotencyKey");
        var issue = feedback.get(feedbackId, grant);
        UUID from = KnowledgeJson.uuid(request, "fromCaseId"),
                to = KnowledgeJson.uuid(request, "toCaseId");
        String reason = KnowledgeJson.text(request, "reason", 2000),
                reference = KnowledgeJson.text(request, "referenceEvidenceRef", 500),
                key = KnowledgeJson.text(request, "idempotencyKey", 120);
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid migration idempotency key");
        // 幂等域跨反馈，先锁主体/键，再锁反馈，避免竞争同键时退化为数据库唯一约束错误。
        db.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                json.write(List.of("migration.request", grant.principal(), key)));
        lock(feedbackId);
        String requestHash =
                json.sha256(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "feedbackId",
                                                feedbackId.toString(),
                                                "request",
                                                request))));
        var previous =
                db.queryForList(
                        "SELECT id::text,request_hash FROM awe_obligation_migration WHERE principal=? AND idempotency_key=?",
                        grant.principal(),
                        key);
        if (!previous.isEmpty()) {
            if (!requestHash.equals(previous.getFirst().get("request_hash")))
                throw new IllegalStateException("Migration key belongs to a different proposal");
            return get(feedbackId, UUID.fromString((String) previous.getFirst().get("id")), grant);
        }
        var before = feedback.regressionCase(from, grant);
        var after = feedback.regressionCase(to, grant);
        var comparison = validate(issue, before, after);
        UUID id = UUID.randomUUID();
        var proposal = new LinkedHashMap<String, Object>();
        proposal.put("schemaVersion", 1);
        proposal.put("feedbackId", feedbackId.toString());
        proposal.put("principal", grant.principal());
        proposal.put("fromCaseId", from.toString());
        proposal.put("toCaseId", to.toString());
        proposal.put("fromCaseHash", before.get("case_hash"));
        proposal.put("toCaseHash", after.get("case_hash"));
        proposal.put("fromScopeHash", before.get("scope_hash"));
        proposal.put("toScopeHash", after.get("scope_hash"));
        proposal.put("comparison", comparison);
        proposal.put("reason", reason);
        proposal.put("referenceEvidenceRef", reference);
        proposal.put("proofScope", "REVIEWED_TASK_REPLACEMENT_REFERENCE_ONLY");
        proposal.put(
                "limitations",
                List.of(
                        "The old model, failed observation and reference are retained; the original pinned version is not certified repaired",
                        "A passing replacement proves this reviewed task/reference only, not universal model equivalence or the external Agent's overall success"));
        JsonNode document = json.parse(json.write(proposal));
        String hash = json.sha256(document);
        db.update(
                "INSERT INTO awe_obligation_migration(id,feedback_id,principal,from_case_id,to_case_id,proposal_doc,migration_hash,proposed_by,idempotency_key,request_hash) VALUES(?,?,?,?,?,?::jsonb,?,?,?,?)",
                id,
                feedbackId,
                grant.principal(),
                from,
                to,
                json.canonical(document),
                hash,
                grant.principal(),
                key,
                requestHash);
        return get(feedbackId, id, grant);
    }

    public Map<String, Object> get(UUID feedbackId, UUID migrationId, ConsumerAccess.Grant grant) {
        feedback.get(feedbackId, grant);
        var row = row(feedbackId, migrationId);
        if (!grant.principal().equals(row.get("principal")))
            throw new NoSuchElementException("Migration not found");
        feedback.regressionCase(UUID.fromString((String) row.get("from_case_id")), grant);
        feedback.regressionCase(UUID.fromString((String) row.get("to_case_id")), grant);
        return view(row);
    }

    public List<Map<String, Object>> list(UUID feedbackId, ConsumerAccess.Grant grant) {
        feedback.get(feedbackId, grant);
        var rows = new ArrayList<Map<String, Object>>();
        for (var row :
                db.queryForList(
                        "SELECT id::text FROM awe_obligation_migration WHERE feedback_id=? AND principal=? ORDER BY created_at DESC LIMIT 100",
                        feedbackId,
                        grant.principal()))
            rows.add(get(feedbackId, UUID.fromString((String) row.get("id")), grant));
        return rows;
    }

    public Map<String, Object> review(UUID feedbackId, UUID migrationId) {
        return view(row(feedbackId, migrationId));
    }

    @Transactional
    public Map<String, Object> approve(
            UUID feedbackId, UUID migrationId, JsonNode request, String actor) {
        KnowledgeJson.fields(request, "migrationHash");
        lock(feedbackId);
        var row = row(feedbackId, migrationId);
        if (actor == null || actor.equals(row.get("proposed_by")))
            throw new IllegalArgumentException("Migration needs an independent reviewer");
        if (!row.get("migration_hash").equals(KnowledgeJson.hash(request, "migrationHash")))
            throw new IllegalArgumentException(
                    "Migration hash does not match the reviewed proposal");
        validateStored(row);
        db.update(
                "INSERT INTO awe_obligation_migration_approval(migration_id,migration_hash,reviewed_by) VALUES(?,?,?) ON CONFLICT(migration_id) DO NOTHING",
                migrationId,
                row.get("migration_hash"),
                actor);
        return view(row);
    }

    @Transactional
    public Map<String, Object> resolve(
            UUID feedbackId, UUID migrationId, JsonNode request, String actor) {
        KnowledgeJson.fields(request, "replayId", "note");
        lock(feedbackId);
        var row = row(feedbackId, migrationId);
        validateStored(row);
        var approvals =
                db.queryForList(
                        "SELECT migration_hash,reviewed_at FROM awe_obligation_migration_approval WHERE migration_id=?",
                        migrationId);
        if (approvals.isEmpty()
                || !row.get("migration_hash").equals(approvals.getFirst().get("migration_hash")))
            throw new IllegalArgumentException(
                    "Migration must be independently approved before replacement replay");
        UUID replayId = KnowledgeJson.uuid(request, "replayId");
        var replay = feedback.replayRow(replayId);
        var target = feedback.caseRow(UUID.fromString((String) row.get("to_case_id")));
        if (!Boolean.TRUE.equals(replay.get("passed"))
                || !row.get("principal").equals(replay.get("principal"))
                || !row.get("to_case_id").equals(replay.get("case_id"))
                || !target.get("case_hash").equals(replay.get("accepted_case_hash"))
                || !target.get("scope_hash").equals(replay.get("scope_hash"))
                || ((Timestamp) replay.get("created_at"))
                        .before((Timestamp) approvals.getFirst().get("reviewed_at")))
            throw new IllegalArgumentException(
                    "Replacement requires a passing actual replay of the exact new case after migration approval");
        String actual =
                feedback.storedScope(
                        "DATA",
                        UUID.fromString((String) replay.get("actual_observation_id")),
                        (String) row.get("principal"));
        if (!actual.equals(target.get("scope_hash")))
            throw new IllegalArgumentException(
                    "Replacement replay has changed its immutable obligation scope");
        var occurrences =
                db.queryForList(
                        "SELECT created_at FROM awe_feedback_occurrence WHERE feedback_id=? ORDER BY created_at DESC LIMIT 1",
                        feedbackId);
        if (!occurrences.isEmpty()
                && ((Timestamp) replay.get("created_at"))
                        .before((Timestamp) occurrences.getFirst().get("created_at")))
            throw new IllegalArgumentException(
                    "A past replacement replay cannot resolve a later recurrence");
        var review = modelReview(feedbackId);
        db.update(
                "INSERT INTO awe_obligation_migration_resolution(id,migration_id,replay_id,review_id,reviewed_by,note) VALUES(?,?,?,?,?,?) ON CONFLICT(migration_id,replay_id) DO NOTHING",
                UUID.randomUUID(),
                migrationId,
                replayId,
                review.get("id"),
                actor,
                KnowledgeJson.text(request, "note", 2000));
        return view(row);
    }

    private Map<String, Object> validate(
            Map<String, Object> issue, Map<String, Object> before, Map<String, Object> after) {
        if (!issue.get("principal").equals(before.get("principal"))
                || !issue.get("principal").equals(after.get("principal"))
                || !before.get("observation_kind").equals("DATA")
                || !after.get("observation_kind").equals("DATA")
                || before.get("reviewed_by") == null
                || after.get("reviewed_by") == null)
            throw new IllegalArgumentException(
                    "Migration requires same-principal reviewed data reference cases");
        if (!((JsonNode) before.get("expectation")).path("type").asText().equals("DATA_RESULT")
                || !((JsonNode) after.get("expectation"))
                        .path("type")
                        .asText()
                        .equals("DATA_RESULT")
                || !before.get("expected_state").equals(after.get("expected_state"))
                || !before.get("expected_fingerprint").equals(after.get("expected_fingerprint")))
            throw new IllegalArgumentException(
                    "Model repair cannot change the original data-result expectation or replace it with a refusal");
        String principal = (String) issue.get("principal");
        JsonNode
                oldDoc =
                        feedback.storedDocument(
                                "DATA",
                                UUID.fromString((String) before.get("anchor_id")),
                                principal),
                newDoc =
                        feedback.storedDocument(
                                "DATA",
                                UUID.fromString((String) after.get("anchor_id")),
                                principal);
        String original =
                feedback.storedScope(
                        (String) issue.get("observation_kind"),
                        UUID.fromString((String) issue.get("observation_id")),
                        principal);
        String oldScope = feedback.scopeHash("DATA", oldDoc.path("request"), oldDoc),
                newScope = feedback.scopeHash("DATA", newDoc.path("request"), newDoc);
        if (!original.equals(issue.get("scope_hash"))
                || !original.equals(oldScope)
                || !oldScope.equals(before.get("scope_hash"))
                || !newScope.equals(after.get("scope_hash"))
                || oldScope.equals(newScope))
            throw new IllegalArgumentException(
                    "Migration must start from the exact old feedback obligation and name a distinct new scope");
        if (!oldDoc.path("policyHash").equals(newDoc.path("policyHash")))
            throw new IllegalArgumentException("Model migration cannot change the consumer policy");
        boolean semantic = oldDoc.path("request").path("protocolVersion").asInt() == 3;
        if (semantic || newDoc.path("request").path("protocolVersion").asInt() == 3)
            validateSemanticRevision(oldDoc, newDoc);
        JsonNode oldTask = task(oldDoc.path("request"), semantic),
                newTask = task(newDoc.path("request"), semantic);
        if (!oldTask.equals(newTask))
            throw new IllegalArgumentException(
                    "Model migration cannot change the business request, time, purpose, fields or budget");
        JsonNode oldFunction = oldDoc.path("resolved").path("function"),
                newFunction = newDoc.path("resolved").path("function");
        if (!oldFunction.path("entityId").equals(newFunction.path("entityId"))
                || oldFunction.path("releaseId").equals(newFunction.path("releaseId"))
                || ModelCompatibility.Version.parse(newFunction.path("modelVersion").asText())
                                .compareTo(
                                        ModelCompatibility.Version.parse(
                                                oldFunction.path("modelVersion").asText()))
                        <= 0)
            throw new IllegalArgumentException(
                    "Replacement must use a newer published release of the same Function entity");
        JsonNode oldSignature = signature(oldDoc), newSignature = signature(newDoc);
        if (!oldSignature.equals(newSignature))
            throw new IllegalArgumentException(
                    "Model migration cannot change the statistical subject, output types/units/grain or time semantics");
        if (!oldDoc.path("resolved")
                .path("rowScope")
                .equals(newDoc.path("resolved").path("rowScope")))
            throw new IllegalArgumentException(
                    "Model migration cannot change mandatory row-scope obligations");
        modelReview(UUID.fromString((String) issue.get("id")));
        // 审核人必须看到哈希实际约束的物理投影及执行绑定，避免只凭版本号批准替代。
        JsonNode oldScopeDocument = feedback.scopeDocument("DATA", oldDoc.path("request"), oldDoc),
                newScopeDocument = feedback.scopeDocument("DATA", newDoc.path("request"), newDoc);
        return Map.of(
                "request",
                oldTask,
                "policyHash",
                oldDoc.path("policyHash"),
                "resultSignature",
                oldSignature,
                "oldModelsAndBindings",
                oldDoc.path("resolved"),
                "newModelsAndBindings",
                newDoc.path("resolved"),
                "oldExecutionBinding",
                oldScopeDocument.path("provider"),
                "newExecutionBinding",
                newScopeDocument.path("provider"),
                "oldPhysicalProjections",
                oldScopeDocument.path("physicalProjections"),
                "newPhysicalProjections",
                newScopeDocument.path("physicalProjections"),
                "scopeChangeRequiresReview",
                true);
    }

    private void validateSemanticRevision(JsonNode oldDocument, JsonNode newDocument) {
        if (oldDocument.path("request").path("protocolVersion").asInt() != 3
                || newDocument.path("request").path("protocolVersion").asInt() != 3)
            throw new IllegalArgumentException(
                    "Semantic model replacement cannot change the data request protocol");
        JsonNode oldMetric = semanticMetric(oldDocument), newMetric = semanticMetric(newDocument);
        // 只有服务端固定的同一指标新发布可从业务请求投影中移除版本指针；不可借此换指标。
        if (!oldMetric.path("entityId").equals(newMetric.path("entityId"))
                || oldMetric.path("releaseId").equals(newMetric.path("releaseId"))
                || ModelCompatibility.Version.parse(newMetric.path("modelVersion").asText())
                                .compareTo(
                                        ModelCompatibility.Version.parse(
                                                oldMetric.path("modelVersion").asText()))
                        <= 0)
            throw new IllegalArgumentException(
                    "Semantic replacement requires a newer published release of the same Metric entity");
    }

    private JsonNode semanticMetric(JsonNode document) {
        JsonNode requested = document.path("request").path("metric"),
                fixed = document.path("resolved").path("metric");
        if (!KnowledgeJson.uuid(requested, "releaseId")
                        .toString()
                        .equals(fixed.path("releaseId").asText())
                || !KnowledgeJson.hash(requested, "contentHash")
                        .equals(fixed.path("contentHash").asText())
                || fixed.path("entityId").asText().isBlank())
            throw new IllegalArgumentException(
                    "Semantic request does not match its server-pinned Metric release");
        // 重读不可变发布证明实体和版本；不信任调用方的版本/模型身份声明。
        var release = registry.release(UUID.fromString(fixed.path("releaseId").asText()));
        if (!"Metric".equals(release.get("entity_type"))
                || !fixed.path("entityId").asText().equals(release.get("entity_id"))
                || !fixed.path("modelVersion").asText().equals(release.get("version"))
                || !fixed.path("contentHash").asText().equals(release.get("content_hash")))
            throw new IllegalArgumentException(
                    "Semantic replacement Metric identity differs from its immutable release");
        return fixed;
    }

    private JsonNode task(JsonNode request, boolean semantic) {
        var result = ((ObjectNode) request).deepCopy();
        result.remove(List.of("proposedFunction", "idempotencyKey", "externalTaskRef"));
        if (semantic) result.remove(List.of("metric", "candidateFunction"));
        return result;
    }

    private JsonNode signature(JsonNode document) {
        JsonNode resolved = document.path("resolved");
        if (document.path("request").path("intent").asText().equals("QUERY_METRIC")) {
            JsonNode contract = resolved.path("contract");
            var fields = new TreeMap<String, JsonNode>();
            for (JsonNode output : contract.path("outputs"))
                fields.put(output.path("name").asText(), output);
            String identity = contract.path("identityField").asText();
            identity = identity.substring(identity.lastIndexOf('.') + 1);
            return json.parse(
                    json.write(
                            Map.of(
                                    "subject",
                                    resolved.path("statisticalSubject").path("entityId"),
                                    "outputs",
                                    fields,
                                    "grain",
                                    contract.path("grain"),
                                    "unit",
                                    contract.path("unit"),
                                    "businessZone",
                                    contract.path("businessZone"),
                                    "timeRole",
                                    contract.path("timeRole"),
                                    "identityField",
                                    identity)));
        }
        var schema =
                registry.release(
                        UUID.fromString(resolved.path("schema").path("releaseId").asText()));
        var dataset =
                registry.release(
                        UUID.fromString(resolved.path("dataset").path("releaseId").asText()));
        ObjectType type = (ObjectType) typed.decode("ObjectType", (JsonNode) schema.get("spec"));
        var fields = new TreeMap<String, Object>();
        for (JsonNode field : document.path("request").path("requirements").path("fields")) {
            var property =
                    type.properties().stream()
                            .filter(p -> p.name().equals(field.asText()))
                            .findFirst()
                            .orElseThrow();
            fields.put(
                    property.name(),
                    Map.of(
                            "type",
                            property.type().name(),
                            "required",
                            property.required(),
                            "sensitive",
                            property.sensitive()));
        }
        return json.parse(
                json.write(
                        Map.of(
                                "subject",
                                resolved.path("subject").path("entityId"),
                                "fields",
                                fields,
                                "grain",
                                ((JsonNode) dataset.get("spec")).path("model").path("grain"),
                                "identityKeys",
                                type.identityKeys(),
                                "timeRole",
                                resolved.path("timeRole"),
                                "businessZone",
                                resolved.path("businessZone"))));
    }

    private void validateStored(Map<String, Object> row) {
        JsonNode comparison =
                json.parse(
                        json.write(
                                validate(
                                        feedback.feedback(
                                                UUID.fromString((String) row.get("feedback_id"))),
                                        feedback.caseRow(
                                                UUID.fromString((String) row.get("from_case_id"))),
                                        feedback.caseRow(
                                                UUID.fromString((String) row.get("to_case_id"))))));
        JsonNode proposal = (JsonNode) row.get("proposal_doc");
        if (!json.sha256(proposal).equals(row.get("migration_hash"))
                || !comparison.equals(proposal.path("comparison")))
            throw new IllegalArgumentException(
                    "Migration no longer matches its immutable reviewed scope");
    }

    private Map<String, Object> modelReview(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id,classification FROM awe_feedback_review WHERE feedback_id=? ORDER BY reviewed_at DESC,id DESC LIMIT 1",
                        id);
        if (rows.isEmpty() || !"MODEL_GAP".equals(rows.getFirst().get("classification")))
            throw new IllegalArgumentException(
                    "A model replacement requires a concrete MODEL_GAP review");
        return rows.getFirst();
    }

    private void lock(UUID feedbackId) {
        db.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "feedback:" + feedbackId);
    }

    private Map<String, Object> row(UUID feedbackId, UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id::text,feedback_id::text,principal,from_case_id::text,to_case_id::text,proposal_doc::text,migration_hash,proposed_by,created_at FROM awe_obligation_migration WHERE id=? AND feedback_id=?",
                        id,
                        feedbackId);
        if (rows.isEmpty()) throw new NoSuchElementException("Migration not found");
        var row = new LinkedHashMap<>(rows.getFirst());
        row.put("proposal_doc", json.parse((String) row.get("proposal_doc")));
        return row;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id"));
        result.put("feedbackId", row.get("feedback_id"));
        result.put("principal", row.get("principal"));
        result.put("migrationHash", row.get("migration_hash"));
        result.put("proposal", row.get("proposal_doc"));
        result.put("createdAt", row.get("created_at"));
        var approval =
                db.queryForList(
                        "SELECT migration_hash,reviewed_by,reviewed_at FROM awe_obligation_migration_approval WHERE migration_id=?",
                        UUID.fromString((String) row.get("id")));
        var resolutions =
                db.queryForList(
                        "SELECT id::text,replay_id::text,reviewed_by,note,resolved_at FROM awe_obligation_migration_resolution WHERE migration_id=? ORDER BY resolved_at DESC",
                        UUID.fromString((String) row.get("id")));
        result.put("approval", approval.isEmpty() ? Map.of() : approval.getFirst());
        result.put("resolutions", resolutions);
        var issue = feedback.feedback(UUID.fromString((String) row.get("feedback_id")));
        String feedbackStatus = (String) issue.get("status");
        boolean current = false;
        if ("RESOLVED_BY_REPLACEMENT".equals(feedbackStatus)
                && issue.get("replacement") instanceof Map<?, ?> replacement)
            current = row.get("id").equals(replacement.get("migration_id"));
        result.put("feedbackStatus", feedbackStatus);
        result.put("resolutionCurrent", current);
        result.put(
                "status",
                resolutions.isEmpty()
                        ? (approval.isEmpty() ? "PROPOSED" : "APPROVED")
                        : current
                                ? "RESOLVED_BY_REPLACEMENT"
                                : "OPEN".equals(feedbackStatus) ? "REOPENED" : "SUPERSEDED");
        return result;
    }
}
