package ai.awe.registry.production;

import ai.awe.kernel.contract.DatasetContract;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.model.PhysicalContractChecks;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.source.SourceService;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 部署仍在原平台完成；这里只接收可信观测并独立检查真实来源，追加逻辑目标的实现绑定。 */
@Service
public final class ProductionRealizations implements ai.awe.registry.model.LogicalDatasetBindings {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final ProductionAdmissionService admissions;
    private final ProductionModels models;
    private final ProductionTargets targets;
    private final ProductionPlatformGateway gateway;
    private final PlatformAccess access;
    private final SourceService sources;
    private final TransactionTemplate transactions;
    private final TypedModels typed = new TypedModels();
    private final PhysicalContractChecks physical = new PhysicalContractChecks();
    private final ProductionWriteIntents writeIntents;

    public ProductionRealizations(
            JdbcTemplate db,
            JsonDocuments json,
            ProductionAdmissionService admissions,
            ProductionModels models,
            ProductionTargets targets,
            ProductionPlatformGateway gateway,
            PlatformAccess access,
            SourceService sources,
            ProductionWriteIntents writeIntents,
            PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.admissions = admissions;
        this.models = models;
        this.targets = targets;
        this.gateway = gateway;
        this.access = access;
        this.sources = sources;
        this.writeIntents = writeIntents;
        transactions = new TransactionTemplate(manager);
    }

    public Map<String, Object> realize(UUID proposalId, JsonNode body, PlatformAccess.Grant grant) {
        ProductionJson.fields(body, "consumptionId", "deploymentRef", "idempotencyKey");
        UUID consumptionId = ProductionJson.uuid(body, "consumptionId");
        String deployment = ProductionJson.text(body, "deploymentRef", 240),
                key = ProductionJson.key(body, "idempotencyKey"),
                requestHash = json.sha256(body);
        var proposal = admissions.platformProposal(proposalId, grant);
        access.authorize(
                grant,
                (String) proposal.get("pipeline_entity_id"),
                (String) proposal.get("environment"));
        var consumptions =
                db.queryForList(
                        "SELECT id::text,receipt_id::text,platform_actor,authorization_doc::text,consumed_at::text FROM awe_production_consumption WHERE id=? AND proposal_id=? AND platform_actor=?",
                        consumptionId,
                        proposalId,
                        grant.principal());
        if (consumptions.isEmpty())
            throw new NoSuchElementException(
                    "Consumed production authorization not found in platform scope");
        var consumption = consumptions.getFirst();
        JsonNode authorization = json.parse((String) consumption.get("authorization_doc"));
        var previous =
                db.queryForList(
                        "SELECT request_hash,report_doc::text FROM awe_production_realization_attempt WHERE consumption_id=? AND idempotency_key=?",
                        consumptionId,
                        key);
        if (!previous.isEmpty()) {
            if (!requestHash.equals(previous.getFirst().get("request_hash")))
                throw new IllegalStateException(
                        "Realization idempotency key belongs to a different observation request");
            var answer = json.object((String) previous.getFirst().get("report_doc"));
            answer.put("replayed", true);
            return map(answer);
        }
        var plan =
                models.plan(
                        UUID.fromString((String) proposal.get("pipeline_release_id")),
                        (String) proposal.get("pipeline_content_hash"));
        var physicalKeys = new TreeSet<String>();
        for (var target : plan.targets())
            targets.find(target.contract().targetBindingRef().orElseThrow())
                    .ifPresent(binding -> physicalKeys.add(physicalKey(binding)));
        UUID attemptId = UUID.randomUUID();
        String status = "PASSED";
        var limits = new ArrayList<String>();
        var verified = new ArrayList<Verified>();
        JsonNode deploymentProof = json.object("{}");
        try {
            if (targets.environment().isEmpty())
                throw new Failure(
                        "NOT_CONFIGURED",
                        "The instance physical production environment is not configured");
            if (!targets.environment().equals(proposal.get("environment")))
                throw new Failure(
                        "BLOCKED",
                        "Deployment environment differs from the current target environment");
            var request =
                    json.parse(
                            json.write(
                                    Map.of(
                                            "protocolVersion",
                                            2,
                                            "requestId",
                                            attemptId.toString(),
                                            "consumptionId",
                                            consumptionId.toString(),
                                            "deploymentRef",
                                            deployment,
                                            "authorization",
                                            authorization)));
            var observation = gateway.realization(request);
            if (!"RECEIVED".equals(observation.status()))
                throw new Failure(
                        observation.status(),
                        "Actual deployment observation is unavailable: " + observation.status());
            deploymentProof = observation.document();
            validateDeployment(
                    deploymentProof, attemptId, consumptionId, deployment, authorization, plan);
            JsonNode outputs = deploymentProof.path("outputs");
            if (!outputs.isArray() || outputs.size() != plan.targets().size())
                throw new Failure(
                        "INCONCLUSIVE",
                        "Actual deployment output coverage differs from the Pipeline targets");
            var seen = new HashSet<String>();
            for (var target : plan.targets()) {
                String datasetId = (String) target.dataset().get("id"),
                        bindingRef = target.contract().targetBindingRef().orElseThrow();
                JsonNode output = null;
                for (JsonNode value : outputs) {
                    ProductionJson.fields(
                            value, "datasetReleaseId", "datasetContentHash", "targetBindingRef");
                    if (datasetId.equals(
                            ProductionJson.uuid(value, "datasetReleaseId").toString())) {
                        if (output != null)
                            throw new Failure(
                                    "INCONCLUSIVE", "Duplicate deployment target observation");
                        output = value;
                    }
                }
                if (output == null
                        || !seen.add(datasetId)
                        || !target.dataset()
                                .get("content_hash")
                                .equals(ProductionJson.hash(output, "datasetContentHash"))
                        || !bindingRef.equals(ProductionJson.text(output, "targetBindingRef", 240)))
                    throw new Failure(
                            "INCONCLUSIVE",
                            "Deployment output belongs to another Dataset, version or target binding");
                JsonNode configured;
                try {
                    configured = targets.require(bindingRef);
                } catch (IllegalStateException ex) {
                    throw new Failure(
                            "NOT_CONFIGURED", "Registered deployment target mapping is missing");
                }
                var observed =
                        sources.observe(
                                configured.path("sourceSystem").asText(),
                                configured.path("externalRef").asText(),
                                grant.principal());
                if (!"IMPORTED".equals(observed.status()))
                    throw new Failure(
                            observed.status().equals("NOT_CONFIGURED")
                                    ? "NOT_CONFIGURED"
                                    : "UNAVAILABLE",
                            "Actual output metadata could not be independently observed");
                var binding = ((ObjectNode) configured).deepCopy();
                binding.put("fingerprint", observed.fingerprint());
                var schema =
                        (ObjectType)
                                typed.decode(
                                        "ObjectType", (JsonNode) target.rowSchema().get("spec"));
                var dataset =
                        (SemanticModel.Dataset)
                                typed.decode("Dataset", (JsonNode) target.dataset().get("spec"));
                var errors =
                        new ArrayList<>(
                                physical.errors(
                                        schema,
                                        (JsonNode) target.rowSchema().get("spec"),
                                        binding,
                                        observed.metadata()));
                if (target.contract().readMode() == DatasetContract.ReadMode.EVENT_WINDOW)
                    errors.addAll(
                            physical.errors(
                                    dataset,
                                    (JsonNode) target.dataset().get("spec"),
                                    binding,
                                    observed.metadata()));
                else if (!binding.path("timeFields").isEmpty())
                    errors.add(
                            "Static snapshots cannot claim a fabricated physical event-time mapping");
                if (!errors.isEmpty())
                    throw new Failure(
                            "BLOCKED",
                            "Actual deployed target violates the fixed row Schema, grain or time contract");
                verified.add(new Verified(target, binding, observed.snapshotId(), bindingRef));
            }
            limits.add(
                    "Actual deployment manifest and output metadata match the consumed artifact/model authorization; no general business-success or full data-quality claim is made");
        } catch (Failure ex) {
            status = knownStatus(ex.status);
            limits.add(ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            status = "INCONCLUSIVE";
            limits.add(
                    "Deployment evidence is malformed, incomplete or outside the supported model/source scope");
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("attemptId", attemptId.toString());
        report.put("proposalId", proposalId.toString());
        report.put("consumptionId", consumptionId.toString());
        report.put("status", status);
        report.put("deployment", deploymentProof);
        report.put("limitations", limits);
        report.put("replayed", false);
        String finalStatus = status;
        JsonNode finalDeployment = deploymentProof;
        return transactions.execute(
                tx -> {
                    writeIntents.lock((String) proposal.get("environment"), physicalKeys);
                    db.queryForList(
                            "SELECT id FROM awe_production_consumption WHERE id=? FOR UPDATE",
                            consumptionId);
                    var raced =
                            db.queryForList(
                                    "SELECT request_hash,report_doc::text FROM awe_production_realization_attempt WHERE consumption_id=? AND idempotency_key=?",
                                    consumptionId,
                                    key);
                    if (!raced.isEmpty()) {
                        if (!requestHash.equals(raced.getFirst().get("request_hash")))
                            throw new IllegalStateException(
                                    "Realization idempotency key belongs to a different request");
                        var answer = json.object((String) raced.getFirst().get("report_doc"));
                        answer.put("replayed", true);
                        return map(answer);
                    }
                    String storedStatus = finalStatus;
                    if (storedStatus.equals("PASSED")
                            && !writeIntents.current(
                                    (String) proposal.get("environment"),
                                    consumptionId,
                                    authorization.path("targetWriteIntents"),
                                    physicalKeys)) {
                        storedStatus = "BLOCKED";
                        limits.add(
                                "A newer physical-target write intent superseded this observation before registration");
                        report.put("status", storedStatus);
                    }
                    var realizationRefs = new ArrayList<Map<String, Object>>();
                    if (storedStatus.equals("PASSED"))
                        for (var value : verified) {
                            UUID realizationId = UUID.randomUUID();
                            var existing =
                                    db.queryForList(
                                            "SELECT id::text,deployment_ref,binding_doc::text FROM awe_dataset_realization WHERE consumption_id=? AND dataset_release_id=?",
                                            consumptionId,
                                            UUID.fromString(
                                                    (String) value.target().dataset().get("id")));
                            if (!existing.isEmpty()) {
                                if (!deployment.equals(existing.getFirst().get("deployment_ref"))
                                        || !json.parse(
                                                        (String)
                                                                existing.getFirst()
                                                                        .get("binding_doc"))
                                                .equals(value.binding()))
                                    throw new IllegalStateException(
                                            "Consumed deployment already has a different immutable output realization");
                                realizationRefs.add(
                                        Map.of(
                                                "id",
                                                existing.getFirst().get("id"),
                                                "dataset",
                                                ProductionModels.pointer(value.target().dataset()),
                                                "binding",
                                                value.binding()));
                                continue;
                            }
                            db.update(
                                    "INSERT INTO awe_dataset_realization(id,proposal_id,consumption_id,dataset_release_id,dataset_content_hash,row_schema_release_id,row_schema_content_hash,deployment_ref,run_ref,attempt_ref,artifact_hash,target_binding_ref,binding_doc,source_snapshot_id,evidence_doc,observed_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?)",
                                    realizationId,
                                    proposalId,
                                    consumptionId,
                                    UUID.fromString((String) value.target().dataset().get("id")),
                                    value.target().dataset().get("content_hash"),
                                    UUID.fromString((String) value.target().rowSchema().get("id")),
                                    value.target().rowSchema().get("content_hash"),
                                    deployment,
                                    finalDeployment.path("runId").asText(),
                                    finalDeployment.path("attemptId").asText(),
                                    proposal.get("expected_artifact_hash"),
                                    value.bindingRef(),
                                    json.write(value.binding()),
                                    UUID.fromString(value.snapshot()),
                                    json.write(finalDeployment),
                                    grant.principal());
                            realizationRefs.add(
                                    Map.of(
                                            "id",
                                            realizationId.toString(),
                                            "dataset",
                                            ProductionModels.pointer(value.target().dataset()),
                                            "binding",
                                            value.binding()));
                        }
                    report.put("realizations", realizationRefs);
                    db.update(
                            "INSERT INTO awe_production_realization_attempt(id,proposal_id,consumption_id,platform_actor,idempotency_key,request_hash,physical_targets,status,report_doc) VALUES(?,?,?,?,?,?,?::jsonb,?,?::jsonb)",
                            attemptId,
                            proposalId,
                            consumptionId,
                            grant.principal(),
                            key,
                            requestHash,
                            json.write(physicalKeys),
                            storedStatus,
                            json.write(report));
                    return report;
                });
    }

    public Optional<JsonNode> effectiveBinding(UUID datasetReleaseId) {
        if (targets.environment().isEmpty()) return Optional.empty();
        var rows =
                db.queryForList(
                        "SELECT r.id::text,r.consumption_id::text,r.dataset_content_hash,r.target_binding_ref,r.binding_doc::text,r.deployment_ref,r.run_ref,r.attempt_ref,r.artifact_hash,d.content_hash FROM awe_dataset_realization r JOIN awe_release d ON d.id=r.dataset_release_id JOIN awe_production_proposal p ON p.id=r.proposal_id WHERE r.dataset_release_id=? AND p.environment=? ORDER BY r.observed_at DESC,r.id DESC LIMIT 1",
                        datasetReleaseId,
                        targets.environment());
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.getFirst();
        if (!row.get("dataset_content_hash").equals(row.get("content_hash")))
            return Optional.empty();
        JsonNode binding = json.parse((String) row.get("binding_doc"));
        var configured = targets.find((String) row.get("target_binding_ref"));
        var base = ((ObjectNode) binding).deepCopy();
        base.remove("fingerprint");
        if (configured.isEmpty() || !configured.get().equals(base)) return Optional.empty();
        var consumptionId = UUID.fromString((String) row.get("consumption_id"));
        var consumed =
                db.queryForList(
                        "SELECT authorization_doc::text FROM awe_production_consumption WHERE id=?",
                        consumptionId);
        if (consumed.isEmpty()) return Optional.empty();
        JsonNode authorization = json.parse((String) consumed.getFirst().get("authorization_doc"));
        if (authorization.path("protocolVersion").asInt() != 2
                || !targets.environment().equals(authorization.path("environment").asText()))
            return Optional.empty();
        var scope = ProductionTargetVersions.keys(authorization.path("physicalTargets"));
        if (!scope.contains(physicalKey(binding))
                || !writeIntents.current(
                        targets.environment(),
                        consumptionId,
                        authorization.path("targetWriteIntents"),
                        scope)) return Optional.empty();
        var latest =
                db.queryForList(
                        "SELECT status,consumption_id::text,report_doc::text FROM awe_production_realization_attempt WHERE consumption_id=? ORDER BY observed_at DESC,id DESC LIMIT 1",
                        consumptionId);
        if (latest.isEmpty()
                || !"PASSED".equals(latest.getFirst().get("status"))
                || !row.get("consumption_id").equals(latest.getFirst().get("consumption_id")))
            return Optional.empty();
        // 相同表/Schema 不能证明仍是同一语义实现；新模型接管物理目标后旧版本必须失效。
        JsonNode proof = json.parse((String) latest.getFirst().get("report_doc"));
        JsonNode deployment = proof.path("deployment");
        if (!row.get("deployment_ref").equals(deployment.path("deploymentRef").asText())
                || !row.get("artifact_hash").equals(deployment.path("artifactHash").asText())
                || !row.get("run_ref").equals(deployment.path("runId").asText())
                || !row.get("attempt_ref").equals(deployment.path("attemptId").asText()))
            return Optional.empty();
        boolean same = false;
        for (JsonNode realized : proof.path("realizations"))
            if (row.get("id").equals(realized.path("id").asText())
                    && datasetReleaseId
                            .toString()
                            .equals(realized.path("dataset").path("releaseId").asText())
                    && row.get("dataset_content_hash")
                            .equals(realized.path("dataset").path("contentHash").asText()))
                same = true;
        if (!same) return Optional.empty();
        // 新查询复核平台当前所有者；Schema 指纹及 Registry 的历史 PASSED 不能证明活动语义。
        try {
            var proposal =
                    admissions.proposal(UUID.fromString(authorization.path("proposalId").asText()));
            var plan =
                    models.plan(
                            UUID.fromString((String) proposal.get("pipeline_release_id")),
                            (String) proposal.get("pipeline_content_hash"));
            UUID observationId = UUID.randomUUID();
            var observation =
                    gateway.realization(
                            json.parse(
                                    json.write(
                                            Map.of(
                                                    "protocolVersion",
                                                    2,
                                                    "requestId",
                                                    observationId.toString(),
                                                    "consumptionId",
                                                    consumptionId.toString(),
                                                    "deploymentRef",
                                                    row.get("deployment_ref"),
                                                    "authorization",
                                                    authorization))));
            if (!"RECEIVED".equals(observation.status())) return Optional.empty();
            validateDeployment(
                    observation.document(),
                    observationId,
                    consumptionId,
                    (String) row.get("deployment_ref"),
                    authorization,
                    plan);
            JsonNode current = observation.document();
            if (!row.get("run_ref").equals(current.path("runId").asText())
                    || !row.get("attempt_ref").equals(current.path("attemptId").asText())
                    || !writeIntents.current(
                            targets.environment(),
                            consumptionId,
                            authorization.path("targetWriteIntents"),
                            scope)) return Optional.empty();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Optional.empty();
        }
        return Optional.of(binding.deepCopy());
    }

    @Override
    public Optional<JsonNode> binding(ai.awe.kernel.identity.EntityRef dataset) {
        var rows =
                db.queryForList(
                        "SELECT id::text FROM awe_release WHERE entity_id=? AND version=? AND entity_type='Dataset'",
                        dataset.id(),
                        dataset.modelVersion());
        return rows.isEmpty()
                ? Optional.empty()
                : effectiveBinding(UUID.fromString((String) rows.getFirst().get("id")));
    }

    @Override
    public Optional<ai.awe.registry.model.LogicalDatasetBindings.RealizationProof> realization(
            ai.awe.kernel.identity.EntityRef dataset) {
        var rows =
                db.queryForList(
                        "SELECT r.id::text,r.dataset_release_id::text,r.dataset_content_hash,r.row_schema_release_id::text,r.row_schema_content_hash,r.consumption_id::text,r.artifact_hash,r.deployment_ref,r.run_ref,r.attempt_ref,r.source_snapshot_id::text,r.binding_doc::text,s.entity_id AS schema_entity_id,s.version AS schema_version FROM awe_dataset_realization r JOIN awe_release d ON d.id=r.dataset_release_id JOIN awe_release s ON s.id=r.row_schema_release_id JOIN awe_production_proposal p ON p.id=r.proposal_id WHERE d.entity_id=? AND d.version=? AND p.environment=? ORDER BY r.observed_at DESC,r.id DESC LIMIT 1",
                        dataset.id(),
                        dataset.modelVersion(),
                        targets.environment());
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.getFirst();
        UUID id = UUID.fromString((String) row.get("dataset_release_id"));
        var effective = effectiveBinding(id);
        if (effective.isEmpty()
                || !effective.get().equals(json.parse((String) row.get("binding_doc"))))
            return Optional.empty();
        return Optional.of(
                new ai.awe.registry.model.LogicalDatasetBindings.RealizationProof(
                        dataset,
                        id,
                        (String) row.get("dataset_content_hash"),
                        new ai.awe.kernel.identity.EntityRef(
                                (String) row.get("schema_entity_id"),
                                (String) row.get("schema_version")),
                        UUID.fromString((String) row.get("row_schema_release_id")),
                        (String) row.get("row_schema_content_hash"),
                        UUID.fromString((String) row.get("id")),
                        UUID.fromString((String) row.get("consumption_id")),
                        (String) row.get("artifact_hash"),
                        (String) row.get("deployment_ref"),
                        (String) row.get("run_ref"),
                        (String) row.get("attempt_ref"),
                        UUID.fromString((String) row.get("source_snapshot_id")),
                        effective.get()));
    }

    public List<Map<String, Object>> list(UUID datasetReleaseId) {
        return db.queryForList(
                "SELECT id::text,proposal_id::text,consumption_id::text,dataset_release_id::text,dataset_content_hash,row_schema_release_id::text,row_schema_content_hash,deployment_ref,run_ref,attempt_ref,artifact_hash,target_binding_ref,source_snapshot_id::text,observed_by,observed_at FROM awe_dataset_realization WHERE dataset_release_id=? ORDER BY observed_at DESC LIMIT 100",
                datasetReleaseId);
    }

    private void validateDeployment(
            JsonNode doc,
            UUID requestId,
            UUID consumptionId,
            String deployment,
            JsonNode authorization,
            ProductionModels.Plan plan) {
        ProductionJson.fields(
                doc,
                "protocolVersion",
                "requestId",
                "bindingRevision",
                "consumptionId",
                "deploymentRef",
                "state",
                "receiptId",
                "receiptHash",
                "pipelineReleaseId",
                "pipelineContentHash",
                "artifactHash",
                "environment",
                "previousRevision",
                "revision",
                "activeRevision",
                "runId",
                "attemptId",
                "completedAt",
                "targetRevisions",
                "currentTargets",
                "outputs");
        if (ProductionJson.integer(doc, "protocolVersion", 2) != 2
                || !requestId.toString().equals(ProductionJson.text(doc, "requestId", 36))
                || !consumptionId
                        .toString()
                        .equals(ProductionJson.uuid(doc, "consumptionId").toString())
                || !deployment.equals(ProductionJson.text(doc, "deploymentRef", 240))
                || !"DEPLOYED".equals(ProductionJson.text(doc, "state", 30)))
            throw new Failure(
                    "INCONCLUSIVE",
                    "Deployment observation belongs to another request, consumed operation or state");
        for (String key :
                List.of(
                        "receiptId",
                        "receiptHash",
                        "artifactHash",
                        "environment",
                        "expectedBaselineRevision")) {
            String responseKey = key.equals("expectedBaselineRevision") ? "previousRevision" : key;
            if (!authorization
                    .path(key)
                    .asText()
                    .equals(ProductionJson.text(doc, responseKey, key.endsWith("Hash") ? 64 : 240)))
                throw new Failure(
                        "BLOCKED",
                        "Actual deployment does not match the consumed authorization or CAS baseline");
        }
        if (!plan.pipeline()
                        .get("id")
                        .equals(ProductionJson.uuid(doc, "pipelineReleaseId").toString())
                || !plan.pipeline()
                        .get("content_hash")
                        .equals(ProductionJson.hash(doc, "pipelineContentHash")))
            throw new Failure(
                    "BLOCKED", "Actual deployment model differs from the consumed Pipeline");
        String revision = ProductionJson.text(doc, "revision", 240);
        if (revision.equals(doc.path("previousRevision").asText())
                || !revision.equals(ProductionJson.text(doc, "activeRevision", 240)))
            throw new Failure(
                    "BLOCKED",
                    "Deployment did not commit a new active baseline revision through platform CAS");
        var expectedOutputs = new TreeSet<String>();
        for (var target : plan.targets())
            expectedOutputs.add(
                    target.dataset().get("id")
                            + ":"
                            + target.dataset().get("content_hash")
                            + ":"
                            + target.contract().targetBindingRef().orElseThrow());
        var actualOutputs = new TreeSet<String>();
        if (!doc.path("outputs").isArray() || doc.path("outputs").size() != expectedOutputs.size())
            throw new Failure("INCONCLUSIVE", "Current deployment output coverage is incomplete");
        for (JsonNode output : doc.path("outputs")) {
            ProductionJson.fields(
                    output, "datasetReleaseId", "datasetContentHash", "targetBindingRef");
            if (!actualOutputs.add(
                    ProductionJson.uuid(output, "datasetReleaseId")
                            + ":"
                            + ProductionJson.hash(output, "datasetContentHash")
                            + ":"
                            + ProductionJson.text(output, "targetBindingRef", 240)))
                throw new Failure("INCONCLUSIVE", "Duplicate current deployment output");
        }
        if (!actualOutputs.equals(expectedOutputs))
            throw new Failure(
                    "BLOCKED",
                    "Current deployment output identity differs from the exact model scope");
        if (!ProductionTargetVersions.active(doc, authorization))
            throw new Failure(
                    "BLOCKED",
                    "Current physical target ownership or revision differs from the consumed deployment");
        ProductionJson.text(doc, "bindingRevision", 240);
        ProductionJson.text(doc, "runId", 240);
        ProductionJson.text(doc, "attemptId", 120);
        Instant completed = ProductionJson.instant(doc, "completedAt");
        if (completed.isAfter(Instant.now().plusSeconds(5)))
            throw new Failure("INCONCLUSIVE", "Deployment completion cannot be in the future");
    }

    private record Verified(
            ProductionModels.Target target, JsonNode binding, String snapshot, String bindingRef) {}

    private Map<String, Object> map(JsonNode node) {
        var result = new LinkedHashMap<String, Object>();
        node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static String physicalKey(JsonNode binding) {
        return binding.path("sourceSystem").asText() + ":" + binding.path("externalRef").asText();
    }

    private static String knownStatus(String status) {
        return Set.of("PASSED", "BLOCKED", "NOT_CONFIGURED", "UNAVAILABLE", "INCONCLUSIVE")
                        .contains(status)
                ? status
                : "INCONCLUSIVE";
    }

    private static final class Failure extends IllegalStateException {
        private final String status;

        Failure(String status, String message) {
            super(message);
            this.status = status;
        }
    }
}
