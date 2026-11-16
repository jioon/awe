package ai.awe.registry.knowledge;

import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.consumption.DataTaskOperations;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 调用方报告、责任方归因和真实回放分别保存；没有执行证据就不能用一句“修复”结案。 */
@Service
public class FeedbackService {
    private static final Set<String> ISSUES =
            Set.of(
                    "KNOWLEDGE_GAP",
                    "WRONG_RESULT",
                    "AMBIGUITY",
                    "EXECUTION_FAILURE",
                    "AGENT_INTERPRETATION",
                    "VALIDATION_REQUEST",
                    "OTHER");
    private static final Set<String> STAGES =
            Set.of(
                    "DISCOVERY",
                    "RESOLUTION",
                    "ASSESSMENT",
                    "PLAN",
                    "ACCESS",
                    "READINESS",
                    "EXECUTION",
                    "VERIFICATION",
                    "CONSUMER");
    private static final Set<String> CLASSES =
            Set.of(
                    "MODEL_GAP",
                    "RETRIEVAL_GAP",
                    "DATA_QUALITY",
                    "PLATFORM_FAILURE",
                    "POLICY",
                    "AGENT_USAGE",
                    "UNKNOWN");
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final DataTaskOperations operations;
    private final KnowledgeService knowledge;

    public FeedbackService(
            JdbcTemplate db,
            JsonDocuments json,
            DataTaskOperations operations,
            KnowledgeService knowledge) {
        this.db = db;
        this.json = json;
        this.operations = operations;
        this.knowledge = knowledge;
    }

    private record Evidence(
            String kind,
            UUID id,
            JsonNode request,
            JsonNode document,
            String scopeHash,
            String state,
            String fingerprint) {}

    @Transactional
    public Map<String, Object> report(JsonNode request, ConsumerAccess.Grant grant) {
        KnowledgeJson.fields(request, "observationId", "issueCode", "stage", "description");
        Evidence evidence = evidence(KnowledgeJson.uuid(request, "observationId"), grant);
        String code = KnowledgeJson.enumeration(request, "issueCode", ISSUES),
                stage = KnowledgeJson.enumeration(request, "stage", STAGES);
        KnowledgeJson.text(request, "description", 2000);
        String fingerprint =
                json.sha256(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "scopeHash",
                                                evidence.scopeHash(),
                                                "issue",
                                                code,
                                                "stage",
                                                stage))));
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO awe_consumption_feedback(id,principal,observation_id,observation_kind,scope_hash,fingerprint,issue_code,stage,reporter_doc,evidence_snapshot) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(principal,fingerprint) DO NOTHING",
                id,
                grant.principal(),
                evidence.id(),
                evidence.kind(),
                evidence.scopeHash(),
                fingerprint,
                code,
                stage,
                json.canonical(request),
                json.write(sanitized(evidence)));
        UUID actual =
                db.queryForObject(
                        "SELECT id FROM awe_consumption_feedback WHERE principal=? AND fingerprint=?",
                        UUID.class,
                        grant.principal(),
                        fingerprint);
        db.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "feedback:" + actual);
        db.update(
                "INSERT INTO awe_feedback_occurrence(id,feedback_id,observation_id,reporter_doc,evidence_snapshot) VALUES(?,?,?,?::jsonb,?::jsonb) ON CONFLICT(feedback_id,observation_id) DO NOTHING",
                UUID.randomUUID(),
                actual,
                evidence.id(),
                json.canonical(request),
                json.write(sanitized(evidence)));
        return get(actual, grant);
    }

    public List<Map<String, Object>> list(ConsumerAccess.Grant grant) {
        var result = new ArrayList<Map<String, Object>>();
        for (var row :
                db.queryForList(
                        "SELECT id FROM awe_consumption_feedback WHERE principal=? ORDER BY created_at DESC LIMIT 100",
                        grant.principal())) {
            try {
                result.add(get((UUID) row.get("id"), grant));
            } catch (NoSuchElementException ignored) {
            }
        }
        return result;
    }

    public Map<String, Object> get(UUID id, ConsumerAccess.Grant grant) {
        var row = feedback(id);
        if (!grant.principal().equals(row.get("principal")))
            throw new NoSuchElementException("Feedback not found");
        // 曾经看过不代表现在仍有权看；沿原操作重新检查当前策略，载荷不复制进反馈。
        evidence(UUID.fromString((String) row.get("observation_id")), grant);
        return row;
    }

    @Transactional
    public Map<String, Object> review(UUID id, JsonNode request, String actor) {
        KnowledgeJson.fields(request, "classification", "ownerTeam", "externalIssueRef", "note");
        feedback(id);
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "feedback:" + id);
        String classification = KnowledgeJson.enumeration(request, "classification", CLASSES),
                owner = KnowledgeJson.text(request, "ownerTeam", 200);
        String external = KnowledgeJson.optional(request, "externalIssueRef", 500),
                note = KnowledgeJson.text(request, "note", 2000);
        db.update(
                "INSERT INTO awe_feedback_review(id,feedback_id,classification,owner_team,external_issue_ref,note,reviewed_by) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                id,
                classification,
                owner,
                external,
                note,
                actor);
        return feedback(id);
    }

    public Map<String, Object> proposeCase(JsonNode request, ConsumerAccess.Grant grant) {
        KnowledgeJson.fields(
                request,
                "observationId",
                "expectedState",
                "expectedFingerprint",
                "referenceBasis",
                "referenceEvidenceRef");
        Evidence anchor = evidence(KnowledgeJson.uuid(request, "observationId"), grant);
        if ("RUNNING".equals(anchor.document().path("status").asText()))
            throw new IllegalArgumentException("A regression anchor needs terminal evidence");
        String state = KnowledgeJson.text(request, "expectedState", 50),
                fingerprint = KnowledgeJson.hash(request, "expectedFingerprint");
        String basis = KnowledgeJson.text(request, "referenceBasis", 2000),
                reference = KnowledgeJson.text(request, "referenceEvidenceRef", 500);
        if (anchor.kind().equals("DATA")
                        && !Set.of("RECORDS", "NO_RECORD", "ZERO", "NULL").contains(state)
                || anchor.kind().equals("RESOLUTION")
                        && !Set.of("RESOLVED", "AMBIGUOUS", "UNRESOLVED").contains(state))
            throw new IllegalArgumentException(
                    "Regression reference must express a supported scoped result state");
        UUID id = UUID.randomUUID();
        var manifest =
                Map.of(
                        "kind",
                        anchor.kind(),
                        "request",
                        anchor.request(),
                        "scopeHash",
                        anchor.scopeHash(),
                        "expectedState",
                        state,
                        "expectedFingerprint",
                        fingerprint,
                        "referenceBasis",
                        basis,
                        "referenceEvidenceRef",
                        reference);
        String hash = json.sha256(json.parse(json.write(manifest)));
        db.update(
                "INSERT INTO awe_regression_case(id,principal,anchor_id,observation_kind,request_doc,scope_hash,case_hash,expected_state,expected_fingerprint,reference_basis,reference_evidence_ref,proposed_by) VALUES(?,?,?,?,?::jsonb,?,?,?,?,?,?,?)",
                id,
                grant.principal(),
                anchor.id(),
                anchor.kind(),
                json.canonical(anchor.request()),
                anchor.scopeHash(),
                hash,
                state,
                fingerprint,
                basis,
                reference,
                grant.principal());
        return regressionCase(id, grant);
    }

    public Map<String, Object> regressionCase(UUID id, ConsumerAccess.Grant grant) {
        var row = caseRow(id);
        if (!grant.principal().equals(row.get("principal")))
            throw new NoSuchElementException("Regression case not found");
        evidence(UUID.fromString((String) row.get("anchor_id")), grant);
        return row;
    }

    @Transactional
    public Map<String, Object> approveCase(UUID id, String actor) {
        var row = caseRow(id);
        if (actor.equals(row.get("proposed_by")))
            throw new IllegalArgumentException("Reference case requires an independent reviewer");
        db.update(
                "INSERT INTO awe_regression_case_approval(case_id,case_hash,reviewed_by) VALUES(?,?,?) ON CONFLICT(case_id) DO NOTHING",
                id,
                row.get("case_hash"),
                actor);
        return caseRow(id);
    }

    public Map<String, Object> replay(
            UUID id, JsonNode request, ConsumerAccess.Grant grant, Map<String, Object> identity) {
        KnowledgeJson.fields(request, "idempotencyKey");
        String key = KnowledgeJson.text(request, "idempotencyKey", 120);
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid regression idempotency key");
        var reference = regressionCase(id, grant);
        if (reference.get("reviewed_by") == null)
            throw new IllegalArgumentException("Only reviewed reference cases may run");
        var previous =
                db.queryForList(
                        "SELECT id FROM awe_regression_replay WHERE principal=? AND case_id=? AND idempotency_key=?",
                        grant.principal(),
                        id,
                        key);
        if (!previous.isEmpty()) return replayRow((UUID) previous.getFirst().get("id"), grant);
        JsonNode original = (JsonNode) reference.get("request_doc");
        JsonNode actual;
        if ("DATA".equals(reference.get("observation_kind"))) {
            ObjectNode replay = original.deepCopy();
            replay.put(
                    "idempotencyKey",
                    "regression."
                            + json.sha256(
                                    json.parse(
                                            json.write(
                                                    Map.of("caseId", id.toString(), "key", key)))));
            actual = json.parse(json.write(operations.create(replay, grant, identity)));
        } else actual = json.parse(json.write(knowledge.resolve(original, grant)));
        UUID operation = UUID.fromString(actual.path("id").asText());
        Evidence observed = evidence(operation, grant);
        boolean completed =
                "DATA".equals(observed.kind())
                        ? Set.of("QUERIED", "EXTRACTED").contains(actual.path("status").asText())
                        : true;
        boolean passed =
                completed
                        && !operation.toString().equals(reference.get("anchor_id"))
                        && reference.get("scope_hash").equals(observed.scopeHash())
                        && reference.get("expected_state").equals(observed.state())
                        && reference.get("expected_fingerprint").equals(observed.fingerprint());
        var proof = new LinkedHashMap<String, Object>();
        proof.put("actualEvidence", sanitized(observed));
        proof.put("caseHash", reference.get("case_hash"));
        proof.put("referenceReviewedBy", reference.get("reviewed_by"));
        proof.put("policyHash", grant.policyHash());
        proof.put("referenceKind", "REVIEWED_REFERENCE_REGRESSION");
        proof.put(
                "limitations",
                List.of(
                        "This execution compares an exact reviewed reference; it does not by itself establish independent numeric reconciliation or business truth",
                        "Reference authority and actual operation scope are retained separately; no caller PASSED claim is accepted"));
        UUID replayId = UUID.randomUUID();
        db.update(
                "INSERT INTO awe_regression_replay(id,case_id,principal,idempotency_key,accepted_case_hash,scope_hash,actual_observation_id,actual_state,actual_fingerprint,passed,proof) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(principal,case_id,idempotency_key) DO NOTHING",
                replayId,
                id,
                grant.principal(),
                key,
                reference.get("case_hash"),
                observed.scopeHash(),
                operation,
                observed.state(),
                observed.fingerprint(),
                passed,
                json.write(proof));
        UUID stored =
                db.queryForObject(
                        "SELECT id FROM awe_regression_replay WHERE principal=? AND case_id=? AND idempotency_key=?",
                        UUID.class,
                        grant.principal(),
                        id,
                        key);
        return replayRow(stored, grant);
    }

    public Map<String, Object> replayRow(UUID id, ConsumerAccess.Grant grant) {
        var row = replayRow(id);
        if (!grant.principal().equals(row.get("principal")))
            throw new NoSuchElementException("Replay not found");
        regressionCase(UUID.fromString((String) row.get("case_id")), grant);
        evidence(UUID.fromString((String) row.get("actual_observation_id")), grant);
        return row;
    }

    @Transactional
    public Map<String, Object> close(UUID feedbackId, JsonNode request, String actor) {
        KnowledgeJson.fields(request, "replayId", "note");
        var feedback = feedback(feedbackId);
        db.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "feedback:" + feedbackId);
        var reviews =
                db.queryForList(
                        "SELECT id,classification FROM awe_feedback_review WHERE feedback_id=? ORDER BY reviewed_at DESC,id DESC LIMIT 1",
                        feedbackId);
        if (reviews.isEmpty() || "UNKNOWN".equals(reviews.getFirst().get("classification")))
            throw new IllegalArgumentException(
                    "Feedback requires a concrete reviewed classification before technical closure");
        if ("AGENT_USAGE".equals(reviews.getFirst().get("classification")))
            throw new IllegalArgumentException(
                    "Agent interpretation requires external acceptance; correct data replay cannot close that task");
        var replay = replayRow(KnowledgeJson.uuid(request, "replayId"));
        if (!Boolean.TRUE.equals(replay.get("passed"))
                || !feedback.get("principal").equals(replay.get("principal"))
                || !feedback.get("scope_hash").equals(replay.get("scope_hash")))
            throw new IllegalArgumentException(
                    "Closure requires a passing actual replay of the same principal and exact request/model scope");
        var reference = caseRow(UUID.fromString((String) replay.get("case_id")));
        if (reference.get("reviewed_by") == null
                || !reference.get("case_hash").equals(replay.get("accepted_case_hash")))
            throw new IllegalArgumentException(
                    "Replay must bind the immutable reviewed reference case");
        // 不能仅相信历史 scope_hash 列：从服务端不可改的接受上下文重算，旧的请求级摘要也不能借新代码升格。
        String principal = (String) feedback.get("principal");
        String feedbackScope =
                storedScope(
                        (String) feedback.get("observation_kind"),
                        UUID.fromString((String) feedback.get("observation_id")),
                        principal);
        String anchorScope =
                storedScope(
                        (String) reference.get("observation_kind"),
                        UUID.fromString((String) reference.get("anchor_id")),
                        principal);
        String actualScope =
                storedScope(
                        (String) reference.get("observation_kind"),
                        UUID.fromString((String) replay.get("actual_observation_id")),
                        principal);
        if (!feedbackScope.equals(feedback.get("scope_hash"))
                || !feedbackScope.equals(anchorScope)
                || !feedbackScope.equals(reference.get("scope_hash"))
                || !feedbackScope.equals(actualScope)
                || !feedbackScope.equals(replay.get("scope_hash")))
            throw new IllegalArgumentException(
                    "Closure requires the same immutable model, row policy and physical projection obligation; changed scope needs a separately reviewed migration");
        var occurrences =
                db.queryForList(
                        "SELECT created_at FROM awe_feedback_occurrence WHERE feedback_id=? ORDER BY created_at DESC LIMIT 1",
                        feedbackId);
        if (!occurrences.isEmpty()
                && ((java.sql.Timestamp) replay.get("created_at"))
                        .before((java.sql.Timestamp) occurrences.getFirst().get("created_at")))
            throw new IllegalArgumentException(
                    "A prior passing replay cannot close a later recurrence");
        String note = KnowledgeJson.text(request, "note", 2000);
        var existing =
                db.queryForList(
                        "SELECT id FROM awe_feedback_closure WHERE feedback_id=? AND replay_id=?",
                        feedbackId,
                        UUID.fromString((String) replay.get("id")));
        if (existing.isEmpty())
            db.update(
                    "INSERT INTO awe_feedback_closure(id,feedback_id,replay_id,review_id,classification,reviewed_by,note) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    feedbackId,
                    UUID.fromString((String) replay.get("id")),
                    reviews.getFirst().get("id"),
                    reviews.getFirst().get("classification"),
                    actor,
                    note);
        return feedback(feedbackId);
    }

    private Evidence evidence(UUID id, ConsumerAccess.Grant grant) {
        JsonNode doc;
        String kind;
        try {
            doc = json.parse(json.write(operations.get(id, grant)));
            kind = "DATA";
        } catch (NoSuchElementException notData) {
            doc = json.parse(json.write(knowledge.observation(id, grant)));
            kind = "RESOLUTION";
        }
        JsonNode request = doc.path("request");
        if (!request.isObject())
            throw new IllegalStateException("Operation did not preserve a normalized request");
        String scope = scopeHash(kind, request, doc);
        String state =
                kind.equals("DATA")
                        ? doc.path("result").path("state").asText()
                        : doc.path("resolution").asText();
        String fingerprint =
                kind.equals("DATA") ? doc.path("resultHash").asText() : resolutionFingerprint(doc);
        if (!fingerprint.matches("[a-f0-9]{64}"))
            fingerprint =
                    json.sha256(
                            json.parse(
                                    json.write(
                                            Map.of(
                                                    "state",
                                                    state,
                                                    "status",
                                                    doc.path("status").asText(),
                                                    "resolved",
                                                    doc.path("resolved")))));
        return new Evidence(kind, id, request, doc, scope, state, fingerprint);
    }

    private String scopeHash(String kind, JsonNode request, JsonNode accepted) {
        var scoped = (ObjectNode) request.deepCopy();
        scoped.remove(List.of("idempotencyKey", "externalTaskRef"));
        String policy = accepted.path("policyHash").asText();
        if (!policy.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException(
                    "Observation has no immutable server policy scope; an explicit reviewed scope migration is required");
        var obligation = new LinkedHashMap<String, Object>();
        obligation.put("scopeVersion", "AWE_OBLIGATION_SCOPE_V2");
        obligation.put("kind", kind);
        obligation.put("request", scoped);
        obligation.put("policyHash", policy);
        if (kind.equals("DATA")) {
            if (!accepted.path("resolved").isObject())
                throw new IllegalArgumentException("Data observation has no fixed model manifest");
            JsonNode resolved = accepted.path("resolved");
            if (resolved.has("policyHash") && !policy.equals(resolved.path("policyHash").asText()))
                throw new IllegalArgumentException("Accepted policy and model manifest disagree");
            obligation.put("resolved", resolved);
            var projections = new TreeMap<String, String>();
            if (accepted.path("snapshots").isObject())
                accepted.path("snapshots")
                        .fields()
                        .forEachRemaining(
                                e ->
                                        projections.put(
                                                e.getKey(),
                                                physicalProjection(e.getValue().asText())));
            if (!accepted.path("data_snapshot_ref").asText().isBlank())
                projections.put(
                        "$single", physicalProjection(accepted.path("data_snapshot_ref").asText()));
            obligation.put("physicalProjections", projections);
            var provider = new LinkedHashMap<String, JsonNode>();
            for (String key :
                    List.of(
                            "gatewayConfigurationHash",
                            "implementationVersion",
                            "implementationHash",
                            "sourceProjectionRef"))
                if (accepted.path("executionEvidence").has(key))
                    provider.put(key, accepted.path("executionEvidence").get(key));
            obligation.put("provider", provider);
        }
        return json.sha256(json.parse(json.write(obligation)));
    }

    private String physicalProjection(String reference) {
        if (reference.matches("postgresql:binding:[a-f0-9]{64}:tx_snapshot_sha256:[a-f0-9]{64}"))
            return reference.substring(0, reference.indexOf(":tx_snapshot_sha256:"));
        // 未声明稳定投影身份的 opaque snapshot 不做猜测性截断；不同句柄需要明确受审适配。
        return reference;
    }

    private String storedScope(String kind, UUID id, String principal) {
        if (kind.equals("RESOLUTION")) {
            var rows =
                    db.queryForList(
                            "SELECT request_doc::text,policy_hash,report::text FROM awe_resolution_observation WHERE id=? AND principal=?",
                            id,
                            principal);
            if (rows.isEmpty())
                throw new IllegalArgumentException(
                        "Resolution proof is outside the closure principal scope");
            var document = (ObjectNode) json.parse((String) rows.getFirst().get("report"));
            document.put("policyHash", (String) rows.getFirst().get("policy_hash"));
            return scopeHash(
                    kind, json.parse((String) rows.getFirst().get("request_doc")), document);
        }
        var matches = new ArrayList<Map<String, Object>>();
        for (String table : List.of("awe_data_task", "awe_metric_task"))
            matches.addAll(
                    db.queryForList(
                            "SELECT request_doc::text,resolved::text,policy_hash,report::text FROM "
                                    + table
                                    + " WHERE id=? AND principal=?",
                            id,
                            principal));
        if (matches.size() != 1)
            throw new IllegalArgumentException(
                    "Data proof is missing or has an ambiguous immutable identity");
        var row = matches.getFirst();
        var document = (ObjectNode) json.parse((String) row.get("report"));
        document.put("policyHash", (String) row.get("policy_hash"));
        document.set("resolved", json.parse((String) row.get("resolved")));
        return scopeHash(kind, json.parse((String) row.get("request_doc")), document);
    }

    public String resolutionFingerprint(JsonNode result) {
        var candidates = new ArrayList<Map<String, String>>();
        for (var candidate : result.path("candidates"))
            candidates.add(
                    Map.of(
                            "releaseId",
                            candidate.path("releaseId").asText(),
                            "contentHash",
                            candidate.path("contentHash").asText()));
        candidates.sort(Comparator.comparing(c -> c.get("releaseId")));
        return json.sha256(
                json.parse(
                        json.write(
                                Map.of(
                                        "resolution",
                                        result.path("resolution").asText(),
                                        "candidates",
                                        candidates))));
    }

    private Map<String, Object> sanitized(Evidence evidence) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", evidence.id().toString());
        result.put("kind", evidence.kind());
        result.put("request", evidence.request());
        result.put("scopeHash", evidence.scopeHash());
        result.put("state", evidence.state());
        result.put("fingerprint", evidence.fingerprint());
        for (String key :
                List.of(
                        "status",
                        "requestHash",
                        "policyHash",
                        "resolved",
                        "decision",
                        "verification",
                        "gate_report",
                        "snapshots",
                        "data_snapshot_ref",
                        "executionEvidence",
                        "stages",
                        "indexHash"))
            if (evidence.document().has(key)) result.put(key, evidence.document().get(key));
        return result;
    }

    private Map<String, Object> feedback(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT f.id::text,f.principal,f.observation_id::text,f.observation_kind,f.scope_hash,f.issue_code,f.stage,f.reporter_doc::text,f.evidence_snapshot::text,f.created_at,c.replay_id::text AS closed_replay_id,c.reviewed_by AS closed_by,c.classification AS closed_classification,c.closed_at,o.created_at AS last_occurrence_at FROM awe_consumption_feedback f LEFT JOIN LATERAL(SELECT * FROM awe_feedback_closure WHERE feedback_id=f.id ORDER BY closed_at DESC,id DESC LIMIT 1)c ON true LEFT JOIN LATERAL(SELECT created_at FROM awe_feedback_occurrence WHERE feedback_id=f.id ORDER BY created_at DESC LIMIT 1)o ON true WHERE f.id=?",
                        id);
        if (rows.isEmpty()) throw new NoSuchElementException("Feedback not found");
        var result = new LinkedHashMap<String, Object>(rows.getFirst());
        boolean closed =
                result.get("closed_at") != null
                        && (result.get("last_occurrence_at") == null
                                || !((java.sql.Timestamp) result.get("closed_at"))
                                        .before(
                                                (java.sql.Timestamp)
                                                        result.get("last_occurrence_at")));
        result.put("status", closed ? "TECHNICALLY_CLOSED" : "OPEN");
        result.put("closureScope", "AWE_DATA_OR_RESOLUTION_REFERENCE_ONLY");
        result.put("reporter_doc", json.parse((String) result.get("reporter_doc")));
        result.put("evidence_snapshot", json.parse((String) result.get("evidence_snapshot")));
        result.put(
                "reviews",
                db.queryForList(
                        "SELECT id::text,classification,owner_team,external_issue_ref,note,reviewed_by,reviewed_at FROM awe_feedback_review WHERE feedback_id=? ORDER BY reviewed_at DESC,id DESC",
                        id));
        result.put(
                "occurrences",
                db.queryForList(
                        "SELECT id::text,observation_id::text,created_at FROM awe_feedback_occurrence WHERE feedback_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
                        id));
        return result;
    }

    private Map<String, Object> caseRow(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT c.id::text,c.principal,c.anchor_id::text,c.observation_kind,c.request_doc::text,c.scope_hash,c.case_hash,c.expected_state,c.expected_fingerprint,c.reference_basis,c.reference_evidence_ref,c.proposed_by,c.created_at,a.reviewed_by FROM awe_regression_case c LEFT JOIN awe_regression_case_approval a ON a.case_id=c.id WHERE c.id=?",
                        id);
        if (rows.isEmpty()) throw new NoSuchElementException("Regression case not found");
        var result = new LinkedHashMap<String, Object>(rows.getFirst());
        result.put("request_doc", json.parse((String) result.get("request_doc")));
        return result;
    }

    private Map<String, Object> replayRow(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id::text,case_id::text,principal,accepted_case_hash,scope_hash,actual_observation_id::text,actual_state,actual_fingerprint,passed,proof::text,created_at FROM awe_regression_replay WHERE id=?",
                        id);
        if (rows.isEmpty()) throw new NoSuchElementException("Regression replay not found");
        var result = new LinkedHashMap<String, Object>(rows.getFirst());
        result.put("proof", json.parse((String) result.get("proof")));
        return result;
    }
}
