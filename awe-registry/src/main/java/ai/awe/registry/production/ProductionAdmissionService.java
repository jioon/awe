package ai.awe.registry.production;

import ai.awe.registry.source.SourceService;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 模型、制品、完整影响与实际回归证据共同约束部署许可；不执行部署或管理工程工作流。 */
@Service
public final class ProductionAdmissionService {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final ProductionModels models;
    private final ProductionTargets targets;
    private final ProductionRegressionSuites suites;
    private final ProductionImpactGraph impacts;
    private final ProductionObligations obligations;
    private final ProductionPlatformGateway gateway;
    private final PlatformAccess access;
    private final ProductionWriteIntents writeIntents;
    private final SourceService sources;
    private final TransactionTemplate transactions;
    private final Semaphore observationSlots = new Semaphore(2);
    private final long maxEvidenceAge;

    private record Observed(JsonNode context, JsonNode regression, JsonNode report) {}

    private record Evaluation(String status, JsonNode report) {}

    public ProductionAdmissionService(
            JdbcTemplate db,
            JsonDocuments json,
            ProductionModels models,
            ProductionTargets targets,
            ProductionRegressionSuites suites,
            ProductionImpactGraph impacts,
            ProductionObligations obligations,
            ProductionPlatformGateway gateway,
            PlatformAccess access,
            SourceService sources,
            ProductionWriteIntents writeIntents,
            PlatformTransactionManager manager,
            Environment env) {
        this.db = db;
        this.json = json;
        this.models = models;
        this.targets = targets;
        this.suites = suites;
        this.impacts = impacts;
        this.obligations = obligations;
        this.gateway = gateway;
        this.access = access;
        this.sources = sources;
        this.writeIntents = writeIntents;
        transactions = new TransactionTemplate(manager);
        String age = env.getProperty("AWE_PRODUCTION_PROOF_MAX_AGE_SECONDS", "900");
        if (!age.matches("[1-9][0-9]{1,3}")
                || Long.parseLong(age) < 60
                || Long.parseLong(age) > 3600)
            throw new IllegalArgumentException(
                    "Production proof age must be between 60 and 3600 seconds");
        maxEvidenceAge = Long.parseLong(age);
    }

    public Map<String, Object> plan(UUID id, String hash) {
        var plan = models.plan(id, hash);
        return Map.of(
                "pipeline",
                ProductionModels.pointer(plan.pipeline()),
                "format",
                ProductionModels.ARTIFACT_FORMAT,
                "artifact",
                plan.artifact(),
                "content",
                json.canonical(plan.artifact()),
                "contentHash",
                plan.artifactHash(),
                "limitations",
                List.of(
                        "This is a typed data-transformation artifact, not an arbitrary SQL/code workspace or deployment workflow",
                        "Actual platform execution and independent regression remain required"));
    }

    public Map<String, Object> prepare(JsonNode body, String actor) {
        ProductionJson.fields(
                body,
                "pipelineReleaseId",
                "pipelineContentHash",
                "artifactId",
                "environment",
                "idempotencyKey");
        UUID pipelineId = ProductionJson.uuid(body, "pipelineReleaseId");
        String pipelineHash = ProductionJson.hash(body, "pipelineContentHash");
        String artifact = ProductionJson.text(body, "artifactId", 240),
                environment = ProductionJson.text(body, "environment", 120),
                key = ProductionJson.key(body, "idempotencyKey");
        if (!environment.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid production environment");
        String requestHash = json.sha256(body);
        var prior =
                db.queryForList(
                        "SELECT id::text,request_hash FROM awe_production_proposal WHERE created_by=? AND idempotency_key=?",
                        actor,
                        key);
        if (!prior.isEmpty()) {
            if (!requestHash.equals(prior.getFirst().get("request_hash")))
                throw new IllegalStateException(
                        "Production idempotency key belongs to a different request");
            return get(UUID.fromString((String) prior.getFirst().get("id")));
        }
        var plan = models.plan(pipelineId, pipelineHash);
        UUID id = UUID.randomUUID();
        var impact = impact(plan);
        var suite = suites.find((String) plan.pipeline().get("entity_id"));
        List<Map<String, Object>> required =
                suite.map(s -> obligations.derive(plan, impact, s)).orElse(List.of());
        JsonNode verification =
                json.parse(
                        json.write(
                                Map.of(
                                        "releases",
                                        impact.releases(),
                                        "sharedAssets",
                                        impact.sharedAssets(),
                                        "suite",
                                        suite.map(ProductionRegressionSuites.Suite::descriptor)
                                                .orElse(Map.of()),
                                        "obligations",
                                        required)));
        var limits = new ArrayList<String>();
        String status = "PREPARED";
        JsonNode baseline = json.object("{}");
        try {
            if (suite.isEmpty())
                throw new Failure(
                        "NOT_CONFIGURED",
                        "A versioned platform regression suite and independent checkers must be registered");
            if (targets.environment().isEmpty())
                throw new Failure(
                        "NOT_CONFIGURED",
                        "The instance production environment must be explicitly registered");
            if (!targets.environment().equals(environment))
                throw new Failure(
                        "BLOCKED",
                        "Requested deployment environment differs from this instance's physical target environment");
            for (var target : plan.targets())
                targets.require(target.contract().targetBindingRef().orElseThrow());
            var response =
                    observe(
                            "PREPARE",
                            id,
                            plan,
                            artifact,
                            environment,
                            impact,
                            suite.get(),
                            required);
            baseline =
                    json.parse(
                            json.write(
                                    Map.of(
                                            "deployment",
                                            response.context().path("baseline"),
                                            "bindingRevision",
                                            response.context().path("bindingRevision"),
                                            "targetBaselines",
                                            response.context().path("targetBaselines"))));
            limits.add(
                    "Preparation records actual artifact/baseline observations; no regression or deployment has been certified");
        } catch (Failure ex) {
            status = proposalStatus(ex.status);
            limits.add(ex.getMessage());
        } catch (IllegalStateException ex) {
            status =
                    ex.getMessage() != null && ex.getMessage().startsWith("NOT_CONFIGURED:")
                            ? "NOT_CONFIGURED"
                            : "BLOCKED";
            limits.add("Registered production targets or complete scope are unavailable");
        } catch (IllegalArgumentException ex) {
            status = "BLOCKED";
            limits.add(ex.getMessage());
        }
        limits.add(
                "AWE authorizes only the named data-contract checks; overall business acceptance and actual deployment remain external");
        int inserted =
                db.update(
                        "INSERT INTO awe_production_proposal(id,created_by,idempotency_key,request_hash,request_doc,pipeline_release_id,pipeline_content_hash,pipeline_entity_id,environment,artifact_id,expected_artifact_hash,artifact_doc,impact_doc,impact_hash,baseline_doc,platform_config_hash,target_config_hash,status,limitations) VALUES(?,?,?,?,?::jsonb,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?,?,?,?::jsonb) ON CONFLICT(created_by,idempotency_key) DO NOTHING",
                        id,
                        actor,
                        key,
                        requestHash,
                        json.canonical(body),
                        pipelineId,
                        pipelineHash,
                        plan.pipeline().get("entity_id"),
                        environment,
                        artifact,
                        plan.artifactHash(),
                        json.canonical(plan.artifact()),
                        json.write(verification),
                        impact.hash(),
                        json.write(baseline),
                        configurationHash(),
                        targets.configurationHash(),
                        status,
                        json.write(limits));
        if (inserted == 0) {
            var existing =
                    db.queryForMap(
                            "SELECT id::text,request_hash FROM awe_production_proposal WHERE created_by=? AND idempotency_key=?",
                            actor,
                            key);
            if (!requestHash.equals(existing.get("request_hash")))
                throw new IllegalStateException(
                        "Production idempotency key belongs to a different request");
            id = UUID.fromString((String) existing.get("id"));
        }
        return get(id);
    }

    public Map<String, Object> verify(UUID id, JsonNode body, String actor) {
        ProductionJson.fields(body);
        var proposal = proposal(id);
        UUID checkId = UUID.randomUUID();
        Evaluation evaluation;
        if (!"PREPARED".equals(proposal.get("status")))
            evaluation =
                    failed(
                            (String) proposal.get("status"),
                            "The proposal has no accepted preparation context; create a new proposal after configuration or artifact repair");
        else {
            try {
                Current current = current(proposal, "VERIFY", checkId);
                evaluation = evaluate(current, proposal);
            } catch (Failure ex) {
                evaluation = failed(ex.status, ex.getMessage());
            } catch (IllegalArgumentException | IllegalStateException ex) {
                evaluation =
                        failed(
                                "STALE",
                                "The candidate, target mapping or complete verification scope changed or is unsupported");
            }
        }
        JsonNode context = evaluation.report().path("context");
        String contextHash = json.sha256(context.isMissingNode() ? json.object("{}") : context);
        db.update(
                "INSERT INTO awe_production_check(id,proposal_id,checked_by,status,context_hash,evidence_doc) VALUES(?,?,?,?,?,?::jsonb)",
                checkId,
                id,
                actor,
                checkStatus(evaluation.status()),
                contextHash,
                json.write(evaluation.report()));
        return get(id);
    }

    public Map<String, Object> approve(UUID id, JsonNode body, String actor) {
        ProductionJson.fields(body, "checkId", "expiresInSeconds");
        UUID checkId = ProductionJson.uuid(body, "checkId");
        long ttl = ProductionJson.integer(body, "expiresInSeconds", 900);
        if (ttl < 30)
            throw new IllegalArgumentException(
                    "Production receipt lifetime must be between 30 and 900 seconds");
        return transactions.execute(
                tx -> {
                    lock(id);
                    var proposal = proposal(id);
                    if (actor == null || actor.equals(proposal.get("created_by")))
                        throw new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "An independent production reviewer is required");
                    if (!db.queryForList(
                                    "SELECT id FROM awe_production_consumption WHERE proposal_id=?",
                                    id)
                            .isEmpty())
                        throw new IllegalStateException(
                                "A consumed proposal cannot issue another deployment receipt");
                    var check = latestCheck(id);
                    if (check == null
                            || !checkId.toString().equals(check.get("id"))
                            || !"PASSED".equals(check.get("status")))
                        throw new IllegalStateException(
                                "Approval requires the current complete successful regression check");
                    var previous =
                            db.queryForList(
                                    "SELECT id FROM awe_production_receipt WHERE check_id=?",
                                    checkId);
                    if (!previous.isEmpty()) return get(id);
                    Current current = current(proposal, "APPROVE", UUID.randomUUID());
                    requireUnchanged(current, proposal, check);
                    Instant now = databaseNow(), expires = now.plusSeconds(ttl);
                    UUID receiptId = UUID.randomUUID();
                    var receipt = new LinkedHashMap<String, Object>();
                    receipt.put("receiptId", receiptId.toString());
                    receipt.put("proposalId", id.toString());
                    receipt.put("checkId", checkId.toString());
                    receipt.put("pipeline", ProductionModels.pointer(current.plan().pipeline()));
                    receipt.put("artifactId", proposal.get("artifact_id"));
                    receipt.put("artifactHash", proposal.get("expected_artifact_hash"));
                    receipt.put("environment", proposal.get("environment"));
                    receipt.put(
                            "expectedBaselineRevision",
                            current.observed()
                                    .context()
                                    .path("baseline")
                                    .path("revision")
                                    .asText());
                    receipt.put("contextHash", check.get("context_hash"));
                    receipt.put("impactHash", proposal.get("impact_hash"));
                    receipt.put("suite", current.suite().descriptor());
                    receipt.put("approvedBy", actor);
                    receipt.put("approvedAt", now.toString());
                    receipt.put("expiresAt", expires.toString());
                    receipt.put(
                            "proofScope",
                            "REGISTERED_MODEL_COMPILED_ARTIFACT_AND_PINNED_REGRESSION_SUITE");
                    receipt.put(
                            "deploymentRequirement",
                            "Online one-time consume followed by platform CAS on expectedBaselineRevision; receipt hash alone is not authority");
                    String receiptHash = json.sha256(json.parse(json.write(receipt)));
                    db.update(
                            "INSERT INTO awe_production_receipt(id,proposal_id,check_id,approved_by,receipt_hash,receipt_doc,approved_at,expires_at) VALUES(?,?,?,?,?,?::jsonb,?::timestamptz,?::timestamptz)",
                            receiptId,
                            id,
                            checkId,
                            actor,
                            receiptHash,
                            json.write(receipt),
                            now.toString(),
                            expires.toString());
                    return get(id);
                });
    }

    public Map<String, Object> consume(UUID id, JsonNode body, PlatformAccess.Grant grant) {
        ProductionJson.fields(
                body,
                "receiptId",
                "receiptHash",
                "artifactHash",
                "pipelineReleaseId",
                "pipelineContentHash",
                "environment",
                "baselineRevision",
                "idempotencyKey");
        UUID receiptId = ProductionJson.uuid(body, "receiptId");
        String receiptHash = ProductionJson.hash(body, "receiptHash"),
                key = ProductionJson.key(body, "idempotencyKey");
        String requestHash = json.sha256(body);
        var initial = platformProposal(id, grant);
        access.authorize(
                grant,
                (String) initial.get("pipeline_entity_id"),
                (String) initial.get("environment"));
        return transactions.execute(
                tx -> {
                    lock(id);
                    var proposal = platformProposal(id, grant);
                    var previous =
                            db.queryForList(
                                    "SELECT * FROM awe_production_consumption WHERE proposal_id=?",
                                    id);
                    if (!previous.isEmpty()) {
                        var existing = previous.getFirst();
                        if (!grant.principal().equals(existing.get("platform_actor"))
                                || !key.equals(existing.get("idempotency_key"))
                                || !requestHash.equals(existing.get("request_hash"))
                                || !receiptId.equals(existing.get("receipt_id")))
                            throw new IllegalStateException(
                                    "The production proposal was already consumed by a different deployment request");
                        return consumption(existing, true);
                    }
                    var receipt = latestReceipt(id);
                    if (receipt == null
                            || !receiptId.toString().equals(receipt.get("id"))
                            || !receiptHash.equals(receipt.get("receipt_hash")))
                        throw new IllegalStateException(
                                "Production receipt is not the current approved receipt");
                    if (!Boolean.TRUE.equals(receipt.get("unexpired")))
                        throw new IllegalStateException("Production receipt expired");
                    if (!ProductionJson.hash(body, "artifactHash")
                                    .equals(proposal.get("expected_artifact_hash"))
                            || !ProductionJson.uuid(body, "pipelineReleaseId")
                                    .toString()
                                    .equals(proposal.get("pipeline_release_id"))
                            || !ProductionJson.hash(body, "pipelineContentHash")
                                    .equals(proposal.get("pipeline_content_hash"))
                            || !ProductionJson.text(body, "environment", 120)
                                    .equals(proposal.get("environment"))
                            || !ProductionJson.text(body, "baselineRevision", 240)
                                    .equals(
                                            ((JsonNode) proposal.get("baseline_doc"))
                                                    .path("deployment")
                                                    .path("revision")
                                                    .asText()))
                        throw new IllegalStateException(
                                "Receipt cannot authorize a different artifact, model, environment or deployment baseline");
                    var check = latestCheck(id);
                    if (check == null
                            || !receipt.get("check_id")
                                    .equals(UUID.fromString((String) check.get("id")))
                            || !"PASSED".equals(check.get("status")))
                        throw new IllegalStateException(
                                "The approved check is no longer the latest successful check");
                    var fixedPlan =
                            models.plan(
                                    UUID.fromString((String) proposal.get("pipeline_release_id")),
                                    (String) proposal.get("pipeline_content_hash"));
                    writeIntents.lock(
                            (String) proposal.get("environment"), physicalKeys(fixedPlan));
                    Current current = current(proposal, "CONSUME", UUID.randomUUID());
                    requireUnchanged(current, proposal, check);
                    if (!databaseNow()
                            .isBefore(
                                    Instant.parse(
                                            ((JsonNode) receipt.get("receipt_doc"))
                                                    .path("expiresAt")
                                                    .asText())))
                        throw new IllegalStateException(
                                "Production receipt expired during current-scope verification");
                    UUID consumptionId = UUID.randomUUID();
                    var authorization = new LinkedHashMap<String, Object>();
                    authorization.put("protocolVersion", 2);
                    authorization.put("consumptionId", consumptionId.toString());
                    authorization.put(
                            "targetBaselines",
                            current.observed().context().path("targetBaselines"));
                    authorization.put(
                            "targetWriteIntents",
                            writeIntents.append(
                                    (String) proposal.get("environment"),
                                    physicalKeys(current.plan()),
                                    consumptionId));
                    authorization.put("proposalId", id.toString());
                    authorization.put("receiptId", receiptId.toString());
                    authorization.put("receiptHash", receiptHash);
                    authorization.put(
                            "pipeline", ProductionModels.pointer(current.plan().pipeline()));
                    authorization.put("artifactId", proposal.get("artifact_id"));
                    authorization.put("artifactHash", proposal.get("expected_artifact_hash"));
                    authorization.put("environment", proposal.get("environment"));
                    authorization.put(
                            "expectedBaselineRevision",
                            ((JsonNode) proposal.get("baseline_doc"))
                                    .path("deployment")
                                    .path("revision")
                                    .asText());
                    authorization.put("contextHash", check.get("context_hash"));
                    authorization.put("platformPrincipal", grant.principal());
                    authorization.put("platformPolicyHash", grant.policyHash());
                    authorization.put("state", "CONSUMED");
                    authorization.put("deploymentState", "NOT_OBSERVED");
                    authorization.put(
                            "requirements",
                            List.of(
                                    "Platform must atomically CAS every physical target revision and monotonic write intent with the exact artifact digest",
                                    "Platform must use consumptionId as the deployment idempotency identity",
                                    "AWE has not performed deployment or accepted the overall business task"));
                    authorization.put(
                            "physicalTargets",
                            models.physicalTargets(current.plan()).stream()
                                    .map(k -> k.system() + ":" + k.externalRef())
                                    .sorted()
                                    .toList());
                    db.update(
                            "INSERT INTO awe_production_consumption(id,proposal_id,receipt_id,platform_actor,platform_policy_hash,idempotency_key,request_hash,authorization_doc) VALUES(?,?,?,?,?,?,?,?::jsonb)",
                            consumptionId,
                            id,
                            receiptId,
                            grant.principal(),
                            grant.policyHash(),
                            key,
                            requestHash,
                            json.write(authorization));
                    var row =
                            db.queryForMap(
                                    "SELECT * FROM awe_production_consumption WHERE id=?",
                                    consumptionId);
                    return consumption(row, false);
                });
    }

    public List<Map<String, Object>> list() {
        return db.queryForList(
                "SELECT id::text,pipeline_release_id::text,pipeline_content_hash,pipeline_entity_id,environment,artifact_id,expected_artifact_hash,status,created_by,created_at FROM awe_production_proposal ORDER BY created_at DESC LIMIT 100");
    }

    public Map<String, Object> get(UUID id) {
        var result = new LinkedHashMap<>(proposal(id));
        var check = latestCheck(id);
        var receipt = latestReceipt(id);
        result.put("latestCheck", check == null ? Map.of() : check);
        result.put("receipt", receipt == null ? Map.of() : receipt);
        var consumed =
                db.queryForList("SELECT * FROM awe_production_consumption WHERE proposal_id=?", id);
        if (!consumed.isEmpty()) result.put("consumption", consumption(consumed.getFirst(), true));
        result.put("limitations", json.parse((String) result.get("limitations")));
        return result;
    }

    public Map<String, Object> platformProposal(UUID id, PlatformAccess.Grant grant) {
        if (grant == null || grant.pipelineIds().isEmpty() || grant.environments().isEmpty())
            throw new NoSuchElementException("Production proposal not found in platform scope");
        String p = String.join(",", Collections.nCopies(grant.pipelineIds().size(), "?")),
                e = String.join(",", Collections.nCopies(grant.environments().size(), "?"));
        var args = new ArrayList<Object>();
        args.add(id);
        args.addAll(grant.pipelineIds());
        args.addAll(grant.environments());
        if (db.queryForList(
                        "SELECT id FROM awe_production_proposal WHERE id=? AND pipeline_entity_id IN ("
                                + p
                                + ") AND environment IN ("
                                + e
                                + ")",
                        args.toArray())
                .isEmpty())
            throw new NoSuchElementException("Production proposal not found in platform scope");
        var proposal = proposal(id);
        access.authorize(
                grant,
                (String) proposal.get("pipeline_entity_id"),
                (String) proposal.get("environment"));
        return proposal;
    }

    Map<String, Object> proposal(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id::text,created_by,request_hash,request_doc::text,pipeline_release_id::text,pipeline_content_hash,pipeline_entity_id,environment,artifact_id,expected_artifact_hash,artifact_doc::text,impact_doc::text,impact_hash,baseline_doc::text,platform_config_hash,target_config_hash,status,limitations::text,created_at FROM awe_production_proposal WHERE id=?",
                        id);
        if (rows.isEmpty()) throw new NoSuchElementException("Production proposal not found");
        var row = new LinkedHashMap<>(rows.getFirst());
        for (String field : List.of("request_doc", "artifact_doc", "impact_doc", "baseline_doc"))
            row.put(field, json.parse((String) row.get(field)));
        return row;
    }

    private record Current(
            ProductionModels.Plan plan,
            ProductionImpactGraph.Snapshot impact,
            ProductionRegressionSuites.Suite suite,
            List<Map<String, Object>> required,
            Observed observed) {}

    private Current current(Map<String, Object> proposal, String phase, UUID requestId) {
        if (!"PREPARED".equals(proposal.get("status")))
            throw new Failure("NOT_CONFIGURED", "The proposal has no accepted preparation context");
        if (!configurationHash().equals(proposal.get("platform_config_hash"))
                || !targets.configurationHash().equals(proposal.get("target_config_hash")))
            throw new Failure(
                    "STALE", "Production policy, suite or target binding configuration changed");
        var plan =
                models.plan(
                        UUID.fromString((String) proposal.get("pipeline_release_id")),
                        (String) proposal.get("pipeline_content_hash"));
        var impact = impact(plan);
        if (!impact.hash().equals(proposal.get("impact_hash"))
                || !plan.artifactHash().equals(proposal.get("expected_artifact_hash")))
            throw new Failure("STALE", "Candidate artifact or complete production impact changed");
        var suite =
                suites.find((String) proposal.get("pipeline_entity_id"))
                        .orElseThrow(
                                () ->
                                        new Failure(
                                                "NOT_CONFIGURED",
                                                "Registered production regression suite is missing"));
        var required = obligations.derive(plan, impact, suite);
        var observed =
                observe(
                        phase,
                        requestId,
                        plan,
                        (String) proposal.get("artifact_id"),
                        (String) proposal.get("environment"),
                        impact,
                        suite,
                        required);
        if (!observed.context()
                        .path("baseline")
                        .equals(((JsonNode) proposal.get("baseline_doc")).path("deployment"))
                || !observed.context()
                        .path("bindingRevision")
                        .equals(((JsonNode) proposal.get("baseline_doc")).path("bindingRevision")))
            throw new Failure(
                    "STALE", "The actual deployed baseline or platform binding revision changed");
        if (!observed.context()
                .path("targetBaselines")
                .equals(((JsonNode) proposal.get("baseline_doc")).path("targetBaselines")))
            throw new Failure("STALE", "A shared physical target changed after preparation");
        return new Current(plan, impact, suite, required, observed);
    }

    private Observed observe(
            String phase,
            UUID requestId,
            ProductionModels.Plan plan,
            String artifactId,
            String environment,
            ProductionImpactGraph.Snapshot impact,
            ProductionRegressionSuites.Suite suite,
            List<Map<String, Object>> required) {
        if (!observationSlots.tryAcquire())
            throw new Failure("INCONCLUSIVE", "Production observation concurrency budget exceeded");
        try {
            var request =
                    json.parse(
                            json.write(
                                    Map.ofEntries(
                                            Map.entry("protocolVersion", 2),
                                            Map.entry("physicalTargets", physicalKeys(plan)),
                                            Map.entry("requestId", requestId.toString()),
                                            Map.entry("phase", phase),
                                            Map.entry(
                                                    "pipeline",
                                                    ProductionModels.pointer(plan.pipeline())),
                                            Map.entry("artifactId", artifactId),
                                            Map.entry("expectedArtifactHash", plan.artifactHash()),
                                            Map.entry("environment", environment),
                                            Map.entry("impactHash", impact.hash()),
                                            Map.entry("suite", suite.descriptor()),
                                            Map.entry("obligations", required))));
            var fetched = gateway.observe(request);
            if (!"RECEIVED".equals(fetched.status()))
                throw new Failure(
                        fetched.status(),
                        "Trusted production platform observation could not complete: "
                                + fetched.status());
            JsonNode doc = fetched.document();
            ProductionJson.fields(
                    doc,
                    "protocolVersion",
                    "requestId",
                    "bindingRevision",
                    "environment",
                    "pipelineReleaseId",
                    "pipelineContentHash",
                    "artifact",
                    "baseline",
                    "targetBaselines",
                    "inputs",
                    "suite",
                    "regression");
            if (ProductionJson.integer(doc, "protocolVersion", 2) != 2
                    || !requestId.toString().equals(ProductionJson.text(doc, "requestId", 36))
                    || !environment.equals(ProductionJson.text(doc, "environment", 120))
                    || !plan.pipeline()
                            .get("id")
                            .equals(ProductionJson.text(doc, "pipelineReleaseId", 36))
                    || !plan.pipeline()
                            .get("content_hash")
                            .equals(ProductionJson.hash(doc, "pipelineContentHash")))
                throw new Failure(
                        "INCONCLUSIVE",
                        "Production observation belongs to another operation, environment or model");
            String bindingRevision = ProductionJson.text(doc, "bindingRevision", 240);
            validateArtifact(doc.path("artifact"), artifactId, plan);
            JsonNode baseline = baseline(doc.path("baseline"), plan);
            JsonNode targetBaselines =
                    ProductionTargetVersions.baselines(
                            doc.path("targetBaselines"), physicalKeys(plan));
            JsonNode inputs = inputs(doc.path("inputs"), plan);
            if (!doc.path("suite").equals(json.parse(json.write(suite.descriptor()))))
                throw new Failure(
                        "INCONCLUSIVE",
                        "Production observation did not use the registered regression suite version");
            var sourceProof = new ArrayList<Map<String, Object>>();
            for (JsonNode binding : models.sourceBindings(plan)) {
                var observation =
                        sources.verify(
                                binding.path("sourceSystem").asText(),
                                binding.path("externalRef").asText(),
                                binding.path("fingerprint").asText(),
                                "production-admission");
                if (!"VERIFIED".equals(observation.status()))
                    throw new Failure(
                            observation.status().equals("NOT_CONFIGURED")
                                    ? "NOT_CONFIGURED"
                                    : "STALE",
                            "Pipeline source is no longer consistent with the fixed input binding");
                sourceProof.add(
                        Map.of(
                                "sourceSystem",
                                observation.system(),
                                "externalRef",
                                observation.externalRef(),
                                "fingerprint",
                                observation.fingerprint()));
            }
            sourceProof.sort(Comparator.comparing(v -> json.canonical(json.parse(json.write(v)))));
            JsonNode context =
                    json.parse(
                            json.write(
                                    Map.of(
                                            "pipeline",
                                            ProductionModels.pointer(plan.pipeline()),
                                            "artifactHash",
                                            plan.artifactHash(),
                                            "environment",
                                            environment,
                                            "baseline",
                                            baseline,
                                            "targetBaselines",
                                            targetBaselines,
                                            "inputs",
                                            inputs,
                                            "bindingRevision",
                                            bindingRevision,
                                            "impactHash",
                                            impact.hash(),
                                            "suite",
                                            suite.descriptor(),
                                            "sourceBindings",
                                            sourceProof)));
            JsonNode report =
                    json.parse(
                            json.write(
                                    Map.of(
                                            "requestId",
                                            requestId.toString(),
                                            "phase",
                                            phase,
                                            "artifactVersion",
                                            ProductionJson.text(
                                                    doc.path("artifact"), "version", 240),
                                            "context",
                                            context)));
            return new Observed(context, doc.path("regression"), report);
        } finally {
            observationSlots.release();
        }
    }

    private void validateArtifact(
            JsonNode artifact, String artifactId, ProductionModels.Plan plan) {
        ProductionJson.fields(
                artifact, "artifactId", "version", "contentHash", "mediaType", "content");
        if (!artifactId.equals(ProductionJson.text(artifact, "artifactId", 240))
                || !"application/vnd.awe.pipeline-plan+json"
                        .equals(ProductionJson.text(artifact, "mediaType", 100)))
            throw new Failure(
                    "INCONCLUSIVE", "Actual platform artifact identity or media type differs");
        String content = ProductionJson.text(artifact, "content", 1_000_000);
        if (!sha256(content).equals(ProductionJson.hash(artifact, "contentHash"))
                || !plan.artifactHash().equals(ProductionJson.hash(artifact, "contentHash"))
                || !content.equals(json.canonical(plan.artifact())))
            throw new Failure(
                    "STALE",
                    "Actual artifact bytes differ from the independently constructed model-compiled artifact");
        ProductionJson.text(artifact, "version", 240);
    }

    private JsonNode baseline(JsonNode node, ProductionModels.Plan plan) {
        ProductionJson.fields(
                node,
                "revision",
                "state",
                "pipelineReleaseId",
                "pipelineContentHash",
                "artifactHash");
        ProductionJson.text(node, "revision", 240);
        String state = ProductionJson.text(node, "state", 20);
        if (state.equals("PRESENT")) {
            var release =
                    models.release(
                            ProductionJson.uuid(node, "pipelineReleaseId"),
                            ProductionJson.hash(node, "pipelineContentHash"));
            if (!plan.pipeline().get("entity_id").equals(release.get("entity_id")))
                throw new Failure(
                        "INCONCLUSIVE", "Actual deployment baseline belongs to another Pipeline");
            ProductionJson.hash(node, "artifactHash");
        } else if (!state.equals("ABSENT")
                || !node.path("pipelineReleaseId").isTextual()
                || !node.path("pipelineReleaseId").asText().isEmpty()
                || !node.path("pipelineContentHash").isTextual()
                || !node.path("pipelineContentHash").asText().isEmpty()
                || !node.path("artifactHash").isTextual()
                || !node.path("artifactHash").asText().isEmpty())
            throw new Failure(
                    "INCONCLUSIVE", "Production baseline state is incomplete or unsupported");
        return node.deepCopy();
    }

    private JsonNode inputs(JsonNode node, ProductionModels.Plan plan) {
        if (!node.isArray() || node.size() > 8)
            throw new Failure("INCONCLUSIVE", "Production input revision coverage is incomplete");
        var expected = new TreeMap<String, String>();
        for (var source : plan.contract().sources()) {
            var function =
                    models.dependency(plan.pipeline(), source.readFunction(), "FunctionDefinition");
            var typed = new ai.awe.registry.model.TypedModels();
            var definition =
                    (ai.awe.kernel.model.FunctionDefinition)
                            typed.decode("FunctionDefinition", (JsonNode) function.get("spec"));
            var dataset = models.dependency(function, definition.ownerType(), "Dataset");
            expected.put((String) dataset.get("id"), (String) dataset.get("content_hash"));
        }
        var actual = new TreeMap<String, JsonNode>();
        for (JsonNode input : node) {
            ProductionJson.fields(
                    input, "datasetReleaseId", "contentHash", "revision", "snapshotRef");
            String id = ProductionJson.uuid(input, "datasetReleaseId").toString();
            if (!Objects.equals(expected.get(id), ProductionJson.hash(input, "contentHash"))
                    || actual.put(id, input.deepCopy()) != null)
                throw new Failure(
                        "INCONCLUSIVE",
                        "Production input revision belongs to a different or duplicate source");
            ProductionJson.text(input, "revision", 240);
            ProductionJson.text(input, "snapshotRef", 1000);
        }
        if (!actual.keySet().equals(expected.keySet()))
            throw new Failure(
                    "INCONCLUSIVE", "Production input revisions do not cover every fixed Dataset");
        return json.parse(json.write(actual.values()));
    }

    private Evaluation evaluate(Current current, Map<String, Object> proposal) {
        JsonNode proof = current.observed().regression();
        if (!proof.isObject() || !"COMPLETE".equals(proof.path("state").asText()))
            return report(
                    "INCONCLUSIVE",
                    current,
                    "Actual platform regression is incomplete or unavailable",
                    json.object("{}"));
        ProductionJson.fields(
                proof,
                "state",
                "runId",
                "attemptId",
                "observedAt",
                "pipelineReleaseId",
                "pipelineContentHash",
                "artifactHash",
                "environment",
                "baselineRevision",
                "targetBaselinesHash",
                "impactHash",
                "inputRevisionsHash",
                "checks");
        ProductionJson.text(proof, "runId", 240);
        ProductionJson.text(proof, "attemptId", 120);
        Instant observed = ProductionJson.instant(proof, "observedAt");
        if (observed.isAfter(Instant.now().plusSeconds(5))
                || observed.isBefore(Instant.now().minusSeconds(maxEvidenceAge)))
            return report(
                    "STALE",
                    current,
                    "Actual regression observation is too old or in the future",
                    proof);
        JsonNode context = current.observed().context();
        if (!proposal.get("pipeline_release_id")
                        .equals(ProductionJson.uuid(proof, "pipelineReleaseId").toString())
                || !proposal.get("pipeline_content_hash")
                        .equals(ProductionJson.hash(proof, "pipelineContentHash"))
                || !proposal.get("expected_artifact_hash")
                        .equals(ProductionJson.hash(proof, "artifactHash"))
                || !proposal.get("environment")
                        .equals(ProductionJson.text(proof, "environment", 120))
                || !context.path("baseline")
                        .path("revision")
                        .asText()
                        .equals(ProductionJson.text(proof, "baselineRevision", 240))
                || !proposal.get("impact_hash").equals(ProductionJson.hash(proof, "impactHash"))
                || !json.sha256(context.path("inputs"))
                        .equals(ProductionJson.hash(proof, "inputRevisionsHash")))
            return report(
                    "INCONCLUSIVE",
                    current,
                    "Regression belongs to a different model, artifact, baseline, impact or data revision",
                    proof);
        if (!proof.path("checks").isArray()
                || proof.path("checks").size() != current.required().size())
            return report(
                    "INCONCLUSIVE",
                    current,
                    "Regression does not cover the complete required obligation set",
                    proof);
        if (!json.sha256(current.observed().context().path("targetBaselines"))
                .equals(ProductionJson.hash(proof, "targetBaselinesHash")))
            return report(
                    "INCONCLUSIVE",
                    current,
                    "Regression belongs to different physical target baselines",
                    proof);
        var expected = new HashMap<String, Map<String, Object>>();
        for (var obligation : current.required())
            expected.put((String) obligation.get("id"), obligation);
        var seen = new HashSet<String>();
        boolean failed = false, incomplete = false;
        for (JsonNode check : proof.path("checks")) {
            ProductionJson.fields(
                    check,
                    "obligationId",
                    "scopeHash",
                    "checkerRef",
                    "checkedItems",
                    "violationCount",
                    "mismatchCount",
                    "complete");
            String id = ProductionJson.text(check, "obligationId", 100);
            var obligation = expected.get(id);
            if (obligation == null
                    || !seen.add(id)
                    || !obligation.get("scopeHash").equals(ProductionJson.hash(check, "scopeHash")))
                return report(
                        "INCONCLUSIVE",
                        current,
                        "Regression contains an unknown, repeated or wrong-scope obligation",
                        proof);
            String checker = ProductionJson.text(check, "checkerRef", 240);
            long checked = ProductionJson.integer(check, "checkedItems", 1_000_000_000L),
                    violations = ProductionJson.integer(check, "violationCount", 1_000_000_000L),
                    mismatches = ProductionJson.integer(check, "mismatchCount", 1_000_000_000L);
            if (!check.path("complete").isBoolean()
                    || !check.path("complete").asBoolean()
                    || checked == 0) incomplete = true;
            if (Boolean.TRUE.equals(obligation.get("independentRequired"))
                    && !current.suite().independentCheckers().contains(checker)) incomplete = true;
            failed |= violations > 0 || mismatches > 0;
        }
        return report(
                failed ? "FAILED" : incomplete ? "INCONCLUSIVE" : "PASSED",
                current,
                failed
                        ? "Actual named regression measurements contain violations or mismatches"
                        : incomplete
                                ? "Independent checker or complete nonempty observations are missing"
                                : "All named obligations passed within the registered suite and fixed input scope",
                proof);
    }

    private Evaluation report(String status, Current current, String limitation, JsonNode proof) {
        return new Evaluation(
                status,
                json.parse(
                        json.write(
                                Map.of(
                                        "context",
                                        current.observed().context(),
                                        "regression",
                                        proof,
                                        "observation",
                                        current.observed().report(),
                                        "obligations",
                                        current.required(),
                                        "limitations",
                                        List.of(
                                                limitation,
                                                "This proves only the registered suite and named data obligations, not arbitrary code equivalence or overall business completion")))));
    }

    private Evaluation failed(String status, String message) {
        return new Evaluation(
                checkStatus(status),
                json.parse(
                        json.write(Map.of("context", Map.of(), "limitations", List.of(message)))));
    }

    private void requireUnchanged(
            Current current, Map<String, Object> proposal, Map<String, Object> check) {
        if (!json.sha256(current.observed().context()).equals(check.get("context_hash")))
            throw new IllegalStateException(
                    "Production artifact, source data revisions, baseline or verification context changed after regression");
        Evaluation evaluated = evaluate(current, proposal);
        JsonNode accepted = (JsonNode) check.get("evidence_doc"),
                now = evaluated.report().path("regression");
        if (!"PASSED".equals(evaluated.status())
                || !now.path("runId").equals(accepted.path("regression").path("runId"))
                || !now.path("attemptId").equals(accepted.path("regression").path("attemptId")))
            throw new IllegalStateException(
                    "Current platform run/attempt no longer matches the approved complete regression evidence");
    }

    private ProductionImpactGraph.Snapshot impact(ProductionModels.Plan plan) {
        return impacts.snapshot(
                models.changedEntities(plan),
                models.physicalTargets(plan),
                Set.of(UUID.fromString((String) plan.pipeline().get("id"))));
    }

    private List<String> physicalKeys(ProductionModels.Plan plan) {
        for (var target : plan.targets())
            targets.require(target.contract().targetBindingRef().orElseThrow());
        var keys =
                models.physicalTargets(plan).stream()
                        .map(k -> k.system() + ":" + k.externalRef())
                        .sorted()
                        .toList();
        if (keys.size() != plan.targets().size())
            throw new IllegalArgumentException(
                    "A Pipeline cannot declare multiple outputs for the same physical target");
        return keys;
    }

    private String configurationHash() {
        return json.sha256(
                json.parse(
                        json.write(
                                Map.of(
                                        "protocolVersion",
                                        2,
                                        "platform",
                                        gateway.configurationHash(),
                                        "regressionSuites",
                                        suites.configurationHash(),
                                        "maxEvidenceAgeSeconds",
                                        maxEvidenceAge))));
    }

    private Map<String, Object> latestCheck(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id::text,proposal_id::text,checked_by,status,context_hash,evidence_doc::text,checked_at FROM awe_production_check WHERE proposal_id=? ORDER BY checked_at DESC,id DESC LIMIT 1",
                        id);
        if (rows.isEmpty()) return null;
        var result = new LinkedHashMap<>(rows.getFirst());
        result.put("evidence_doc", json.parse((String) result.get("evidence_doc")));
        return result;
    }

    private Map<String, Object> latestReceipt(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id::text,proposal_id::text,check_id,approved_by,receipt_hash,receipt_doc::text,approved_at,expires_at,(expires_at>clock_timestamp()) AS unexpired FROM awe_production_receipt WHERE proposal_id=? ORDER BY approved_at DESC,id DESC LIMIT 1",
                        id);
        if (rows.isEmpty()) return null;
        var result = new LinkedHashMap<>(rows.getFirst());
        result.put("receipt_doc", json.parse((String) result.get("receipt_doc")));
        return result;
    }

    private Map<String, Object> consumption(Map<String, Object> row, boolean replayed) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id").toString());
        result.put("authorization", json.parse(row.get("authorization_doc").toString()));
        result.put("replayed", replayed);
        result.put("status", "CONSUMED");
        return result;
    }

    private void lock(UUID id) {
        if (db.queryForList("SELECT id FROM awe_production_proposal WHERE id=? FOR UPDATE", id)
                .isEmpty()) throw new NoSuchElementException("Production proposal not found");
    }

    private Instant databaseNow() {
        return Instant.parse(
                db.queryForObject(
                                        "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::text",
                                        String.class)
                                .replace(' ', 'T')
                        + "Z");
    }

    private static String proposalStatus(String status) {
        return status.equals("NOT_CONFIGURED")
                ? status
                : Set.of("UNAVAILABLE", "DENIED").contains(status) ? "UNAVAILABLE" : "BLOCKED";
    }

    private static String checkStatus(String status) {
        return Set.of("PASSED", "FAILED", "NOT_CONFIGURED", "UNAVAILABLE", "STALE", "INCONCLUSIVE")
                        .contains(status)
                ? status
                : "INCONCLUSIVE";
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static final class Failure extends IllegalStateException {
        private final String status;

        Failure(String status, String message) {
            super(message);
            this.status = status;
        }
    }
}
