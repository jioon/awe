package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.*;
import ai.awe.registry.authoring.ModelRegistry;
import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.model.*;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 正式指标定义决定计算；Agent 只能请求受支持的能力，不能替换计划、权限或验证结论。 */
@Service
public final class MetricQueryService {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final ModelRegistry registry;
    private final ConsumerAccess access;
    private final PostgresMetricExecutor postgres;
    private final BiMetricAdapter bi;
    private final MetricEvidenceVerifier verifier;
    private final ObjectProvider<LogicalDatasetBindings> realizations;
    private final TransactionTemplate transactions;
    private final TypedModels typed = new TypedModels();
    private final Semaphore slots = new Semaphore(4);
    private final Map<String, Semaphore> actorSlots = new ConcurrentHashMap<>();
    private final int ttl;

    public MetricQueryService(
            JdbcTemplate db,
            JsonDocuments json,
            ModelRegistry registry,
            ConsumerAccess access,
            PostgresMetricExecutor postgres,
            BiMetricAdapter bi,
            MetricEvidenceVerifier verifier,
            ObjectProvider<LogicalDatasetBindings> realizations,
            PlatformTransactionManager manager,
            Environment env) {
        this.db = db;
        this.json = json;
        this.registry = registry;
        this.access = access;
        this.postgres = postgres;
        this.bi = bi;
        this.verifier = verifier;
        this.realizations = realizations;
        this.transactions = new TransactionTemplate(manager);
        String value = env.getProperty("AWE_CONSUMPTION_RESULT_TTL_SECONDS", "900");
        if (!value.matches("[1-9][0-9]{1,3}")
                || Integer.parseInt(value) < 60
                || Integer.parseInt(value) > 3600)
            throw new IllegalArgumentException("Result TTL must be between 60 and 3600 seconds");
        ttl = Integer.parseInt(value);
    }

    private record Resolved(
            Map<String, Object> functionRelease,
            Map<String, Object> metricRelease,
            Map<String, Object> subjectRelease,
            FunctionDefinition function,
            SemanticModel.Metric metric,
            MetricContract contract,
            Map<String, BoundQuerySource> sources,
            List<Map<String, Object>> sourceManifest,
            Map<EntityRef, LinkType> links) {}

    public List<Map<String, Object>> capabilities(ConsumerAccess.Grant grant) {
        var result = new ArrayList<Map<String, Object>>();
        for (UUID id : permittedFunctions(grant))
            try {
                Resolved r = resolve(id, null, grant);
                if (!grant.fields()
                        .containsAll(
                                r.contract().outputs().stream()
                                        .map(MetricContract.Output::name)
                                        .toList())) continue;
                MetricAccessAnalysis.authorize(r.contract(), r.sources(), access, grant);
                var capability = new LinkedHashMap<String, Object>();
                capability.put("releaseId", r.functionRelease().get("id"));
                capability.put("contentHash", r.functionRelease().get("content_hash"));
                capability.put("functionId", r.function().ref().id());
                capability.put("intent", "QUERY_METRIC");
                capability.put("protocolVersion", 2);
                capability.put(
                        "name", ((JsonNode) r.metricRelease().get("spec")).path("name").asText());
                capability.put(
                        "definition",
                        ((JsonNode) r.metricRelease().get("spec")).path("definition").asText());
                capability.put("metric", pointer(r.metricRelease()));
                capability.put("statisticalSubject", pointer(r.subjectRelease()));
                capability.put(
                        "fields",
                        r.contract().outputs().stream()
                                .map(
                                        o ->
                                                Map.<String, Object>of(
                                                        "name",
                                                        o.name(),
                                                        "type",
                                                        o.type().name(),
                                                        "unit",
                                                        o.unit(),
                                                        "required",
                                                        !o.nullable(),
                                                        "sensitive",
                                                        false))
                                .toList());
                capability.put("contract", typed.encodeValue(r.contract()));
                capability.put(
                        "time",
                        Map.of(
                                "businessZone",
                                r.contract().businessZone().getId(),
                                "role",
                                r.contract().timeRole().name()));
                capability.put("supply", supply(r));
                capability.put("readiness", "UNKNOWN");
                capability.put("views", List.of("OBSERVED_SNAPSHOT", "PERIOD_COMPLETE"));
                capability.put("purposes", grant.purposes().stream().sorted().toList());
                capability.put(
                        "limits",
                        Map.of(
                                "maxRows",
                                grant.maxRows(),
                                "maxBytes",
                                grant.maxBytes(),
                                "maxWindowSeconds",
                                grant.maxWindowSeconds()));
                capability.put(
                        "limitations",
                        List.of(
                                "Discovery does not establish current source readiness or independently verified numerical results"));
                result.add(capability);
            } catch (IllegalArgumentException
                    | NoSuchElementException
                    | ResponseStatusException ignored) {
                /* 仅暴露可见且已注册的正式能力。 */
            }
        return result;
    }

    public Map<String, Object> create(
            JsonNode body, ConsumerAccess.Grant grant, Map<String, Object> trustedIdentity) {
        MetricQueryRequest request = MetricQueryRequest.decode(body);
        Resolved resolved =
                resolve(
                        request.proposedFunction().releaseId(),
                        request.proposedFunction().contentHash(),
                        grant);
        Map<String, RelationalIR.Predicate> scopes = authorize(request, resolved, grant);
        var accepted = request.normalized(json);
        String requestHash = json.sha256(accepted);
        var manifest = manifest(resolved, request, grant, scopes);
        UUID id = UUID.randomUUID();
        int inserted =
                db.update(
                        "INSERT INTO awe_metric_task(id,principal,idempotency_key,request_hash,request_doc,function_release_id,function_content_hash,policy_hash,resolved,status) VALUES(?,?,?,?,?::jsonb,?,?,?,?::jsonb,'RUNNING') ON CONFLICT(principal,idempotency_key) DO NOTHING",
                        id,
                        grant.principal(),
                        request.idempotencyKey(),
                        requestHash,
                        json.canonical(accepted),
                        request.proposedFunction().releaseId(),
                        request.proposedFunction().contentHash(),
                        grant.policyHash(),
                        json.write(manifest));
        if (inserted == 0) {
            var old =
                    db.queryForMap(
                            "SELECT id::text,request_hash FROM awe_metric_task WHERE principal=? AND idempotency_key=?",
                            grant.principal(),
                            request.idempotencyKey());
            if (!requestHash.equals(old.get("request_hash")))
                throw new IllegalStateException(
                        "Idempotency key belongs to a different metric request");
            return get(UUID.fromString((String) old.get("id")), grant);
        }
        var stages = new ArrayList<Map<String, Object>>();
        stage(
                stages,
                "RESOLUTION",
                "RESOLVED",
                "Exact approved Function, Metric, input and relation releases were fixed");
        stage(
                stages,
                "AUTHORIZATION",
                "ALLOWED",
                "Server identity, purpose, output/input columns, mandatory row policies and expanded scan windows were checked");
        stage(
                stages,
                "PLANNING",
                "VALIDATED",
                "Only the published typed plan is accepted; client plan substitution is not supported");
        var limitations = new ArrayList<String>();
        limitations.add(
                "Evidence is limited to the exact published semantics, implementation, requested window and observed source snapshots; it does not certify the Agent's overall task or every explanation");
        limitations.add(
                "PostgreSQL transaction snapshot hashes do not restore historical rows; opaque sources require independent source-side snapshot support");
        String status = "BLOCKED", readiness = "UNKNOWN", reconciliation = "NOT_RUN";
        List<Map<String, Object>> rows = List.of();
        Map<String, String> snapshots = Map.of();
        var sourceGates = new ArrayList<Map<String, Object>>();
        Map<String, Object> engineEvidence = Map.of(),
                referenceEvidence = Map.of(),
                readyEvidence = Map.of();
        boolean own = false, global = false;
        Semaphore ownSlot = actorSlots.computeIfAbsent(grant.principal(), key -> new Semaphore(1));
        try {
            own = ownSlot.tryAcquire();
            if (own) global = slots.tryAcquire();
            if (!own || !global)
                limitations.add(
                        "Concurrent metric query budget exceeded; no data computation was started");
            else if (resolved.sources().values().stream()
                    .anyMatch(s -> s.binding().path("fingerprint").asText().isBlank())) {
                status = "NOT_READY";
                readiness = "NOT_READY";
                limitations.add("A logical input has no verified physical realization");
            } else if (request.verification() == MetricQueryRequest.Verification.REQUIRE_INDEPENDENT
                    && !verifier.verificationConfigured()) {
                status = "NOT_CONFIGURED";
                limitations.add(
                        "Independent verification was required but no registered checker is configured");
            } else if (request.view() == MetricQueryRequest.View.PERIOD_COMPLETE
                    && isPostgres(resolved)
                    && !verifier.readinessConfigured()) {
                status = "NOT_CONFIGURED";
                limitations.add(
                        "Accounting-period completeness requires a configured trusted source-readiness observer");
            } else {
                boolean gatePassed = true;
                for (var source : resolved.sources().values()) {
                    var gate = registry.verifyRelease(source.releaseId(), grant.principal());
                    sourceGates.add(
                            Map.of(
                                    "alias",
                                    source.alias(),
                                    "datasetReleaseId",
                                    source.releaseId().toString(),
                                    "status",
                                    gate.getOrDefault("status", "UNKNOWN")));
                    if (!"VERIFIED".equals(gate.get("status"))) {
                        gatePassed = false;
                        status =
                                switch (gate.get("status").toString()) {
                                    case "NOT_CONFIGURED" -> "NOT_CONFIGURED";
                                    case "UNMATERIALIZED" -> "NOT_READY";
                                    case "UNAVAILABLE" -> "UNAVAILABLE";
                                    default -> "BLOCKED";
                                };
                        limitations.add(
                                "Current source gate for "
                                        + source.alias()
                                        + " did not establish its fixed implementation: "
                                        + gate.get("status"));
                        break;
                    }
                }
                stage(
                        stages,
                        "SOURCE_GATE",
                        gatePassed ? "VERIFIED" : status,
                        "Current authoritative source observations are separate from historical publication approval");
                if (gatePassed) {
                    // 来源检查可能耗时；OIDC 身份在开始新计算前再次检查有效期。
                    if (expired(trustedIdentity))
                        throw new ResponseStatusException(
                                HttpStatus.UNAUTHORIZED,
                                "Authenticated identity expired before execution");
                    if (isPostgres(resolved)) {
                        var result =
                                postgres.execute(
                                        new PostgresMetricExecutor.Plan(
                                                resolved.contract(),
                                                resolved.sources(),
                                                resolved.links(),
                                                request.from(),
                                                request.to(),
                                                request.maxRows(),
                                                request.maxBytes(),
                                                scopes));
                        status = result.status();
                        rows = result.rows();
                        snapshots = result.snapshots();
                        limitations.addAll(result.limitations());
                    } else {
                        var result =
                                bi.execute(
                                        new BiMetricAdapter.Plan(
                                                id,
                                                UUID.fromString(
                                                        (String)
                                                                resolved.metricRelease().get("id")),
                                                (String)
                                                        resolved.metricRelease()
                                                                .get("content_hash"),
                                                (String) manifest.get("planHash"),
                                                resolved.contract(),
                                                request.from(),
                                                request.to(),
                                                grant.principal(),
                                                request.purpose(),
                                                grant.policyHash(),
                                                request.maxRows(),
                                                request.maxBytes(),
                                                request.view()
                                                        == MetricQueryRequest.View.PERIOD_COMPLETE,
                                                scopes,
                                                resolved.sources()));
                        status = result.status();
                        rows = result.rows();
                        snapshots = result.snapshots();
                        readiness = result.readiness();
                        engineEvidence = result.evidence();
                        limitations.addAll(result.limitations());
                    }
                    stage(
                            stages,
                            "EXECUTION",
                            status,
                            "Execution is a scoped data operation, not global business completion");
                    if ("QUERIED".equals(status)) {
                        rows =
                                MetricResults.normalize(
                                        resolved.contract(),
                                        rows,
                                        request.maxRows(),
                                        request.maxBytes(),
                                        json);
                        Map<String, Object> context =
                                evidenceContext(id, resolved, manifest, grant, request);
                        if (request.view() == MetricQueryRequest.View.PERIOD_COMPLETE
                                && isPostgres(resolved)) {
                            var ready = verifier.readiness(context, request, snapshots);
                            readyEvidence = ready.evidence();
                            limitations.addAll(ready.limitations());
                            readiness =
                                    Set.of("READY", "NOT_READY").contains(ready.status())
                                            ? ready.status()
                                            : "UNKNOWN";
                            if (!"READY".equals(ready.status()))
                                status =
                                        "NOT_READY".equals(ready.status())
                                                ? "NOT_READY"
                                                : Set.of("NOT_CONFIGURED", "UNAVAILABLE")
                                                                .contains(ready.status())
                                                        ? ready.status()
                                                        : "INCONCLUSIVE";
                        }
                        if ("QUERIED".equals(status)) {
                            var comparison =
                                    verifier.verify(
                                            context,
                                            request,
                                            resolved.contract(),
                                            snapshots,
                                            rows,
                                            engineEvidence);
                            reconciliation = comparison.status();
                            referenceEvidence = comparison.evidence();
                            limitations.addAll(comparison.limitations());
                            if ("FAILED".equals(reconciliation)) status = "FAILED_VERIFICATION";
                            else if (!Set.of("PASSED", "NOT_RUN").contains(reconciliation))
                                status = "INCONCLUSIVE";
                            else if (request.verification()
                                            == MetricQueryRequest.Verification.REQUIRE_INDEPENDENT
                                    && !"PASSED".equals(reconciliation)) status = "NOT_CONFIGURED";
                        }
                    }
                }
            }
        } catch (ResponseStatusException ex) {
            status = "BLOCKED";
            limitations.add("Current authenticated identity could not authorize a new execution");
        } catch (IllegalArgumentException ex) {
            status = "BLOCKED";
            limitations.add(
                    "The actual plan, source or result did not satisfy its formal typed contract");
        } catch (RuntimeException ex) {
            status = "UNAVAILABLE";
            limitations.add(
                    "The registered data capability did not complete; transport credentials, row values and raw exceptions are withheld");
        } finally {
            if (global) slots.release();
            if (own) ownSlot.release();
        }
        boolean succeeded = "QUERIED".equals(status);
        if (!succeeded) rows = List.of();
        stage(
                stages,
                "READINESS",
                readiness,
                "Period readiness is independent of metadata conformance and SQL completion");
        stage(
                stages,
                "VERIFICATION",
                reconciliation,
                "Independent comparison is separate from result shape and governance certification");
        String resultState =
                succeeded ? MetricResults.state(resolved.contract(), rows) : "UNAVAILABLE";
        if (succeeded && "NOT_APPLICABLE".equals(engineEvidence.get("valueState")))
            resultState = "NOT_APPLICABLE";
        var report = new LinkedHashMap<String, Object>();
        report.put(
                "decision",
                Map.of(
                        "resolution",
                        "RESOLVED",
                        "supply",
                        supply(resolved),
                        "authorization",
                        "ALLOWED",
                        "readiness",
                        readiness));
        report.put("execution", succeeded ? "COMPLETED" : "NOT_COMPLETED");
        report.put(
                "result",
                Map.of(
                        "state",
                        resultState,
                        "rowCount",
                        rows.size(),
                        "scope",
                        request.view().name(),
                        "completeForObservedRange",
                        succeeded,
                        "periodCompleteness",
                        readiness,
                        "unit",
                        resolved.contract().unit()));
        report.put("resultHash", json.sha256(json.parse(json.write(rows))));
        report.put("snapshots", snapshots);
        report.put(
                "data_snapshot_ref",
                snapshots.size() == 1 ? snapshots.values().iterator().next() : "");
        report.put("gate_report", sourceGates);
        report.put("executionEvidence", engineEvidence);
        report.put("readinessEvidence", readyEvidence);
        report.put(
                "verification",
                Map.of(
                        "structural",
                        succeeded ? "PASSED" : "NOT_ESTABLISHED",
                        "independentReconciliation",
                        reconciliation,
                        "businessAcceptance",
                        "EXTERNAL",
                        "evidence",
                        referenceEvidence));
        report.put("authenticatedIdentity", trustedIdentity);
        report.put("stages", stages);
        report.put("limitations", limitations);
        String terminal = status;
        List<Map<String, Object>> payload = rows;
        transactions.executeWithoutResult(
                tx -> {
                    if (succeeded) {
                        String expires =
                                db.queryForObject(
                                        "INSERT INTO awe_metric_task_result(operation_id,rows_doc,expires_at) VALUES(?,?::jsonb,clock_timestamp()+(? * interval '1 second')) RETURNING expires_at::text",
                                        String.class,
                                        id,
                                        json.write(payload),
                                        ttl);
                        report.put("resultExpiresAt", expires);
                    }
                    db.update(
                            "UPDATE awe_metric_task SET status=?,report=?::jsonb,completed_at=clock_timestamp() WHERE id=? AND status='RUNNING'",
                            terminal,
                            json.write(report),
                            id);
                });
        return get(id, grant);
    }

    public Map<String, Object> get(UUID id, ConsumerAccess.Grant grant) {
        var rows =
                db.queryForList(
                        "SELECT id::text,principal,status,policy_hash,request_hash,request_doc::text,resolved::text,report::text,created_at,completed_at FROM awe_metric_task WHERE id=? AND principal=?",
                        id,
                        grant.principal());
        if (rows.isEmpty()) throw new NoSuchElementException("Metric operation not found");
        var stored = rows.getFirst();
        // 新请求在新策略下合法，不代表旧的更宽范围结果仍可披露；保守要求策略版本相同。
        if (!grant.policyHash().equals(stored.get("policy_hash")))
            throw new NoSuchElementException(
                    "Metric operation not found in current authorized scope");
        JsonNode manifest = json.parse((String) stored.get("resolved"));
        var request = MetricQueryRequest.decode(json.parse((String) stored.get("request_doc")));
        try {
            authorize(
                    request,
                    resolve(
                            request.proposedFunction().releaseId(),
                            request.proposedFunction().contentHash(),
                            grant),
                    grant);
        } catch (IllegalArgumentException
                | NoSuchElementException
                | ResponseStatusException denied) {
            throw new NoSuchElementException(
                    "Metric operation not found in current authorized scope");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("id", stored.get("id"));
        result.put("principal", stored.get("principal"));
        result.put("status", stored.get("status"));
        result.put("policyHash", stored.get("policy_hash"));
        result.put("requestHash", stored.get("request_hash"));
        result.put("request", json.parse((String) stored.get("request_doc")));
        result.put("resolved", manifest);
        result.put("createdAt", stored.get("created_at"));
        result.put("completedAt", stored.get("completed_at"));
        JsonNode report = json.parse((String) stored.get("report"));
        report.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
        if (report.path("result").isObject()) {
            var output = (ObjectNode) report.path("result").deepCopy();
            db.update(
                    "DELETE FROM awe_metric_task_result WHERE operation_id=? AND expires_at<=clock_timestamp()",
                    id);
            var payload =
                    db.queryForList(
                            "SELECT rows_doc::text FROM awe_metric_task_result WHERE operation_id=? AND expires_at>clock_timestamp()",
                            id);
            JsonNode data =
                    payload.isEmpty()
                            ? json.parse("[]")
                            : json.parse((String) payload.getFirst().get("rows_doc"));
            boolean valid =
                    !payload.isEmpty()
                            && json.sha256(data).equals(report.path("resultHash").asText());
            result.put("payloadAvailable", valid);
            output.set("rows", valid ? data : json.parse("[]"));
            if (!valid && "QUERIED".equals(stored.get("status"))) {
                output.put("recordedState", output.path("state").asText());
                output.put("state", payload.isEmpty() ? "EXPIRED" : "INVALID");
            }
            result.put("result", output);
        }
        return result;
    }

    public boolean contains(UUID id) {
        return !db.queryForList("SELECT id FROM awe_metric_task WHERE id=?", id).isEmpty();
    }

    public List<Map<String, Object>> list(ConsumerAccess.Grant grant) {
        var result = new ArrayList<Map<String, Object>>();
        for (var row :
                db.queryForList(
                        "SELECT id::text FROM awe_metric_task WHERE principal=? ORDER BY created_at DESC LIMIT 100",
                        grant.principal()))
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
                result.add(summary);
            } catch (NoSuchElementException denied) {
                /* 当前授权撤回后不暴露历史载荷或模型。 */
            }
        return result;
    }

    public Map<String, Object> validatePlan(JsonNode body, ConsumerAccess.Grant grant) {
        // 验证也固定正式指标，不以用户自拟计划偷偷更换业务口径。
        var request = MetricQueryRequest.decode(body);
        var resolved =
                resolve(
                        request.proposedFunction().releaseId(),
                        request.proposedFunction().contentHash(),
                        grant);
        var scopes = authorize(request, resolved, grant);
        SemanticPlanTypes.validate(resolved.contract(), schemas(resolved));
        return Map.of(
                "status",
                "VALIDATED",
                "scope",
                "PUBLISHED_SEMANTIC_PLAN",
                "resolved",
                manifest(resolved, request, grant, scopes),
                "limitations",
                List.of(
                        "Static model and policy validation is not execution permission or current source/period readiness evidence"));
    }

    private Map<String, RelationalIR.Predicate> authorize(
            MetricQueryRequest request, Resolved resolved, ConsumerAccess.Grant grant) {
        List<String> outputs =
                resolved.contract().outputs().stream().map(MetricContract.Output::name).toList();
        access.authorizeQuery(
                grant,
                resolved.function().ref().id(),
                request.purpose(),
                outputs,
                request.maxRows(),
                request.maxBytes(),
                request.from(),
                request.to());
        var windows =
                RelationalSqlCompiler.windows(resolved.contract(), request.from(), request.to())
                        .stream()
                        .filter(w -> !resolved.sources().get(w.source()).staticSnapshot())
                        .toList();
        if (!windows.isEmpty())
            access.authorizeQuery(
                    grant,
                    resolved.function().ref().id(),
                    request.purpose(),
                    outputs,
                    request.maxRows(),
                    request.maxBytes(),
                    windows.stream()
                            .map(RelationalSqlCompiler.ScanWindow::from)
                            .min(Comparator.naturalOrder())
                            .orElseThrow(),
                    windows.stream()
                            .map(RelationalSqlCompiler.ScanWindow::to)
                            .max(Comparator.naturalOrder())
                            .orElseThrow());
        MetricAccessAnalysis.authorize(resolved.contract(), resolved.sources(), access, grant);
        var scopes = new TreeMap<String, RelationalIR.Predicate>();
        for (var source : resolved.sources().values())
            access.scope(grant, source.dataset().ref().id(), source.alias(), source.rowSchema())
                    .ifPresent(p -> scopes.put(source.alias(), p));
        return Map.copyOf(scopes);
    }

    private Resolved resolve(UUID id, String hash, ConsumerAccess.Grant grant) {
        if (!permitted(id, grant))
            throw new NoSuchElementException("Metric capability not found in authorized scope");
        var functionRelease = registry.release(id);
        if (hash != null && !hash.equals(functionRelease.get("content_hash")))
            throw new IllegalArgumentException("Function Hash does not match pinned release");
        var function =
                (FunctionDefinition)
                        typed.decode("FunctionDefinition", (JsonNode) functionRelease.get("spec"));
        if (!MetricQueryProfile.matches(function))
            throw new IllegalArgumentException("Not a registered metric query profile");
        var metricRelease = dependency(functionRelease, function.ownerType(), "Metric");
        var metric =
                (SemanticModel.Metric) typed.decode("Metric", (JsonNode) metricRelease.get("spec"));
        var contract = typed.metricContract((JsonNode) metricRelease.get("spec"));
        MetricQueryProfile.validate(function, metric, contract);
        var subject = dependency(metricRelease, contract.statisticalSubject(), "ObjectType");
        var sources = new TreeMap<String, BoundQuerySource>();
        var sourceManifest = new ArrayList<Map<String, Object>>();
        for (var input : contract.sources()) {
            var sourceFunctionRelease =
                    dependency(metricRelease, input.readFunction(), "FunctionDefinition");
            var sourceFunction =
                    (FunctionDefinition)
                            typed.decode(
                                    "FunctionDefinition",
                                    (JsonNode) sourceFunctionRelease.get("spec"));
            var datasetRelease =
                    dependency(sourceFunctionRelease, sourceFunction.ownerType(), "Dataset");
            var dataset =
                    (SemanticModel.Dataset)
                            typed.decode("Dataset", (JsonNode) datasetRelease.get("spec"));
            var datasetSpec = (JsonNode) datasetRelease.get("spec");
            Optional<DatasetContract> datasetContract =
                    datasetSpec.path("schemaVersion").asInt() == 3
                            ? Optional.of(typed.datasetContract(datasetSpec))
                            : Optional.empty();
            EntityRef rowRef =
                    datasetContract
                            .map(DatasetContract::rowSchema)
                            .orElseGet(
                                    () ->
                                            DatasetExtractProfile.parseResultRef(
                                                    sourceFunction.resultSchemaRef()));
            var schemaRelease = dependency(sourceFunctionRelease, rowRef, "ObjectType");
            var schema =
                    (ObjectType) typed.decode("ObjectType", (JsonNode) schemaRelease.get("spec"));
            if (DatasetExtractProfile.matches(sourceFunction))
                DatasetExtractProfile.validate(sourceFunction, dataset, schema, datasetSpec);
            else DatasetReadProfile.validate(sourceFunction, dataset, schema, datasetSpec);
            var dataSubject = dependency(datasetRelease, dataset.subjectType(), "ObjectType");
            DatasetExtractProfile.validateSubject(
                    schema,
                    (ObjectType) typed.decode("ObjectType", (JsonNode) dataSubject.get("spec")));
            JsonNode binding =
                    datasetSpec.path("bindings").size() == 1
                            ? datasetSpec.path("bindings").get(0)
                            : json.object("{}");
            Optional<LogicalDatasetBindings.RealizationProof> realization = Optional.empty();
            if (datasetContract
                    .map(c -> c.mode() == DatasetContract.Mode.LOGICAL_TARGET)
                    .orElse(false)) {
                var observed =
                        realizations
                                .orderedStream()
                                .map(provider -> provider.realization(dataset.ref()))
                                .flatMap(Optional::stream)
                                .toList();
                if (observed.size() > 1)
                    throw new IllegalArgumentException(
                            "Multiple physical realizations require an explicit environment/source selection");
                if (!observed.isEmpty()) {
                    var proof = observed.getFirst();
                    if (!proof.dataset().equals(dataset.ref())
                            || !proof.datasetReleaseId().toString().equals(datasetRelease.get("id"))
                            || !proof.datasetContentHash()
                                    .equals(datasetRelease.get("content_hash"))
                            || !proof.rowSchema().equals(schema.ref())
                            || !proof.rowSchemaReleaseId()
                                    .toString()
                                    .equals(schemaRelease.get("id"))
                            || !proof.rowSchemaContentHash()
                                    .equals(schemaRelease.get("content_hash")))
                        throw new IllegalArgumentException(
                                "Physical realization proof does not match the fixed Dataset and row Schema releases");
                    binding = proof.binding();
                    realization = Optional.of(proof);
                }
            }
            sources.put(
                    input.alias(),
                    new BoundQuerySource(
                            input.alias(),
                            dataset,
                            schema,
                            binding,
                            UUID.fromString((String) datasetRelease.get("id")),
                            (String) datasetRelease.get("content_hash"),
                            datasetContract,
                            realization));
            var sourceEvidence = new LinkedHashMap<String, Object>();
            sourceEvidence.put("alias", input.alias());
            sourceEvidence.put("readFunction", pointer(sourceFunctionRelease));
            sourceEvidence.put("dataset", pointer(datasetRelease));
            sourceEvidence.put("schema", pointer(schemaRelease));
            sourceEvidence.put("binding", binding);
            realization.ifPresent(
                    proof ->
                            sourceEvidence.put(
                                    "realization",
                                    Map.of(
                                            "realizationId",
                                            proof.realizationId().toString(),
                                            "consumptionId",
                                            proof.consumptionId().toString(),
                                            "artifactHash",
                                            proof.artifactHash(),
                                            "deploymentRef",
                                            proof.deploymentRef(),
                                            "runRef",
                                            proof.runRef(),
                                            "attemptRef",
                                            proof.attemptRef(),
                                            "sourceSnapshotId",
                                            proof.sourceSnapshotId().toString())));
            sourceManifest.add(sourceEvidence);
        }
        var links = new LinkedHashMap<EntityRef, LinkType>();
        for (var ref : RelationalIR.links(contract.plan())) {
            var release = dependency(metricRelease, ref, "LinkType");
            links.put(ref, (LinkType) typed.decode("LinkType", (JsonNode) release.get("spec")));
        }
        var identityDatasets = new LinkedHashMap<String, SemanticModel.Dataset>();
        var identitySchemas = new LinkedHashMap<String, ObjectType>();
        sources.forEach(
                (alias, source) -> {
                    identityDatasets.put(alias, source.dataset());
                    identitySchemas.put(alias, source.rowSchema());
                });
        MetricIdentityChecks.validate(
                metric,
                contract,
                (ObjectType) typed.decode("ObjectType", (JsonNode) subject.get("spec")),
                identityDatasets,
                identitySchemas);
        return new Resolved(
                functionRelease,
                metricRelease,
                subject,
                function,
                metric,
                contract,
                Map.copyOf(sources),
                List.copyOf(sourceManifest),
                Map.copyOf(links));
    }

    private Map<String, Object> manifest(
            Resolved resolved,
            MetricQueryRequest request,
            ConsumerAccess.Grant grant,
            Map<String, RelationalIR.Predicate> scopes) {
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("function", pointer(resolved.functionRelease()));
        manifest.put("metric", pointer(resolved.metricRelease()));
        manifest.put("statisticalSubject", pointer(resolved.subjectRelease()));
        manifest.put("sources", resolved.sourceManifest());
        manifest.put("contract", typed.encodeValue(resolved.contract()));
        manifest.put("requirements", request.requirements());
        manifest.put("policyHash", grant.policyHash());
        manifest.put("rowScope", scopeDocument(scopes));
        manifest.put("outputs", typed.encodeValue(resolved.contract().outputs()));
        manifest.put("planHash", json.sha256(json.object(json.write(manifest))));
        return manifest;
    }

    private Map<String, Object> evidenceContext(
            UUID id,
            Resolved resolved,
            Map<String, Object> manifest,
            ConsumerAccess.Grant grant,
            MetricQueryRequest request) {
        var windows =
                RelationalSqlCompiler.windows(resolved.contract(), request.from(), request.to())
                        .stream()
                        .map(
                                window -> {
                                    var source = resolved.sources().get(window.source());
                                    return Map.<String, Object>of(
                                            "source",
                                            window.source(),
                                            "kind",
                                            source.staticSnapshot() ? "STATIC_SNAPSHOT" : "WINDOW",
                                            "from",
                                            source.staticSnapshot() ? "" : window.from().toString(),
                                            "to",
                                            source.staticSnapshot() ? "" : window.to().toString(),
                                            "rangeRole",
                                            source.rangeRole().name(),
                                            "datasetReleaseId",
                                            source.releaseId().toString(),
                                            "datasetContentHash",
                                            source.contentHash());
                                })
                        .toList();
        return Map.of(
                "operationId",
                id.toString(),
                "metric",
                pointer(resolved.metricRelease()),
                "planHash",
                manifest.get("planHash"),
                "principal",
                grant.principal(),
                "purpose",
                request.purpose(),
                "policyHash",
                grant.policyHash(),
                "scopeHash",
                json.sha256((JsonNode) manifest.get("rowScope")),
                "scanWindows",
                windows);
    }

    private ObjectNode scopeDocument(Map<String, RelationalIR.Predicate> scopes) {
        var node = json.object("{}");
        scopes.forEach((alias, predicate) -> node.set(alias, typed.encodeValue(predicate)));
        return node;
    }

    private Map<String, ObjectType> schemas(Resolved r) {
        var result = new TreeMap<String, ObjectType>();
        r.sources().forEach((alias, source) -> result.put(alias, source.rowSchema()));
        return result;
    }

    private boolean isPostgres(Resolved r) {
        return MetricQueryProfile.POSTGRES_EXECUTOR.equals(r.function().executorBindingRef());
    }

    private String supply(Resolved r) {
        return isPostgres(r) ? "DERIVABLE" : "DIRECT";
    }

    private boolean expired(Map<String, Object> identity) {
        Object value = identity.get("expiresAt");
        if (value == null) return false;
        try {
            return !Instant.parse(value.toString()).isAfter(Instant.now());
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    private void stage(List<Map<String, Object>> stages, String name, String status, String basis) {
        stages.add(
                Map.of(
                        "stage",
                        name,
                        "status",
                        status,
                        "basis",
                        basis,
                        "observedAt",
                        Instant.now().toString()));
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

    private boolean permitted(UUID id, ConsumerAccess.Grant grant) {
        if (grant.functionIds().isEmpty()) return false;
        var args = new ArrayList<Object>();
        args.add(id);
        args.addAll(grant.functionIds());
        return !db.queryForList(
                        "SELECT id FROM awe_release WHERE id=? AND entity_type='FunctionDefinition' AND entity_id IN ("
                                + String.join(
                                        ",", Collections.nCopies(grant.functionIds().size(), "?"))
                                + ")",
                        args.toArray())
                .isEmpty();
    }

    private List<UUID> permittedFunctions(ConsumerAccess.Grant grant) {
        if (grant.functionIds().isEmpty()) return List.of();
        return db
                .queryForList(
                        "SELECT id::text FROM awe_release WHERE entity_type='FunctionDefinition' AND entity_id IN ("
                                + String.join(
                                        ",", Collections.nCopies(grant.functionIds().size(), "?"))
                                + ") ORDER BY published_at DESC LIMIT 100",
                        grant.functionIds().toArray())
                .stream()
                .map(r -> UUID.fromString((String) r.get("id")))
                .toList();
    }

    private Map<String, Object> dependency(
            Map<String, Object> release, EntityRef ref, String type) {
        var relations = (List<Map<String, Object>>) release.get("relations");
        var fixed =
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
                                                "Missing exact dependency manifest"));
        var target = registry.release(UUID.fromString((String) fixed.get("target_release_id")));
        if (!type.equals(target.get("entity_type"))
                || !Set.of(2, 3).contains(target.get("schema_version"))
                || !fixed.get("target_content_hash").equals(target.get("content_hash"))
                || !ref.id().equals(target.get("entity_id"))
                || !ref.modelVersion().equals(target.get("version")))
            throw new IllegalArgumentException(
                    "Dependency type, version or Hash does not match its immutable manifest");
        return target;
    }
}
