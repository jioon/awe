package ai.awe.registry.authoring;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ActionDefinition;
import ai.awe.kernel.model.AweDefinition;
import ai.awe.kernel.model.ContractDefinition;
import ai.awe.kernel.model.EvidenceAssertion;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.LinkType;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.audit.ModelCheckLog;
import ai.awe.registry.model.DatasetExtractProfile;
import ai.awe.registry.model.DatasetReadProfile;
import ai.awe.registry.model.LogicalDatasetBindings;
import ai.awe.registry.model.MetricIdentityChecks;
import ai.awe.registry.model.MetricQueryProfile;
import ai.awe.registry.model.ModelCompatibility;
import ai.awe.registry.model.ModelValidation;
import ai.awe.registry.model.PhysicalContractChecks;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.runtime.RuntimeRules;
import ai.awe.registry.source.SourceService;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 建模与发布的唯一持久化边界；实时复检生成新观测，不能改写既有发布清单。 */
@Service
public class ModelRegistry {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final ModelValidation validator;
    private final SourceService sources;
    private final ModelCheckLog checkLog;
    private final ObjectProvider<LogicalDatasetBindings> realizations;
    private final TypedModels typed = new TypedModels();
    private final ModelCompatibility compatibility = new ModelCompatibility();
    private final PhysicalContractChecks physical = new PhysicalContractChecks();

    public ModelRegistry(
            JdbcTemplate db,
            JsonDocuments json,
            ModelValidation validator,
            SourceService sources,
            ModelCheckLog checkLog,
            ObjectProvider<LogicalDatasetBindings> realizations) {
        this.db = db;
        this.json = json;
        this.validator = validator;
        this.sources = sources;
        this.checkLog = checkLog;
        this.realizations = realizations;
    }

    public List<Map<String, Object>> domains() {
        return db.queryForList(
                "SELECT code,display_name,owner_team,created_at FROM awe_domain ORDER BY code");
    }

    @Transactional
    public Map<String, Object> createDomain(JsonNode req, String actor) {
        String code = text(req, "code"),
                name = text(req, "displayName"),
                owner = text(req, "ownerTeam");
        validator.domain(code, owner);
        db.update(
                "INSERT INTO awe_domain(code,display_name,owner_team) VALUES(?,?,?)",
                code,
                name,
                owner);
        audit(null, null, "DOMAIN_CREATED", actor, Map.of("domain", code));
        return Map.of("code", code, "displayName", name, "ownerTeam", owner);
    }

    public List<Map<String, Object>> drafts() {
        return db.queryForList(
                "SELECT id::text AS id,domain,entity_id,entity_type,state,revision,author_actor,updated_at FROM awe_draft ORDER BY updated_at DESC LIMIT 200");
    }

    public Map<String, Object> draft(UUID id) {
        var row =
                one(
                        "SELECT id::text AS id,domain,entity_id,entity_type,state,revision,author_actor,reviewed_by,spec::text AS spec FROM awe_draft WHERE id=?",
                        id);
        row.put("spec", json.parse((String) row.get("spec")));
        return row;
    }

    @Transactional
    public Map<String, Object> createDraft(JsonNode req, String actor) {
        String domain = text(req, "domain"),
                entity = text(req, "entityId"),
                type = text(req, "entityType");
        JsonNode spec = req.path("spec");
        if (!spec.isObject()) throw new IllegalArgumentException("spec must be object");
        if (db.queryForObject("SELECT count(*) FROM awe_domain WHERE code=?", Integer.class, domain)
                == 0) throw new IllegalArgumentException("Unknown domain");
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO awe_draft(id,domain,entity_id,entity_type,spec,author_actor) VALUES(?,?,?,?,?::jsonb,?)",
                id,
                domain,
                entity,
                type,
                json.canonical(spec),
                actor);
        audit(entity, id, "DRAFT_CREATED", actor, Map.of("domain", domain));
        return draft(id);
    }

    @Transactional
    public Map<String, Object> edit(UUID id, JsonNode req, String actor) {
        lockDraft(id);
        var current = draft(id);
        if (!"DRAFT".equals(current.get("state")))
            throw new IllegalArgumentException("Only DRAFT can be edited");
        var expected = req.path("expectedRevision");
        if (!expected.isIntegralNumber() || !expected.canConvertToInt())
            throw new IllegalArgumentException("expectedRevision is required for draft edits");
        if (expected.intValue() != ((Number) current.get("revision")).intValue())
            throw new IllegalStateException("Stale draft revision; reload before editing");
        JsonNode spec = req.path("spec");
        if (!spec.isObject()) throw new IllegalArgumentException("spec must be object");
        db.update(
                "UPDATE awe_draft SET spec=?::jsonb,revision=revision+1,updated_at=NOW() WHERE id=?",
                json.canonical(spec),
                id);
        audit((String) current.get("entity_id"), id, "DRAFT_UPDATED", actor, Map.of());
        return draft(id);
    }

    @Transactional
    public Map<String, Object> submit(UUID id, String actor) {
        lockDraft(id);
        var current = draft(id);
        if (!"DRAFT".equals(current.get("state")))
            throw new IllegalArgumentException("Draft not editable");
        JsonNode spec = canonical(current);
        AweDefinition model = typed.decode((String) current.get("entity_type"), spec);
        checkReferences(model, spec);
        checkDeclarations(model, spec);
        // 审批只确认候选模型；发布时仍须重查来源，避免把过期物理状态当成已验证。
        db.update(
                "UPDATE awe_draft SET spec=?::jsonb,state='IN_REVIEW',updated_at=NOW() WHERE id=?",
                json.canonical(spec),
                id);
        audit(
                (String) current.get("entity_id"),
                id,
                "SUBMITTED",
                actor,
                Map.of("schemaVersion", spec.path("schemaVersion").asInt()));
        return draft(id);
    }

    @Transactional
    public Map<String, Object> approve(UUID id, String actor) {
        lockDraft(id);
        var current = draft(id);
        if (!"IN_REVIEW".equals(current.get("state")))
            throw new IllegalArgumentException("Draft is not in review");
        if (actor.equals(current.get("author_actor")))
            throw new IllegalArgumentException("Self review forbidden");
        db.update(
                "UPDATE awe_draft SET state='APPROVED',reviewed_by=?,reviewed_at=NOW(),updated_at=NOW() WHERE id=?",
                actor,
                id);
        audit((String) current.get("entity_id"), id, "APPROVED", actor, Map.of());
        return draft(id);
    }

    /** 预检不改变审批状态；来源与契约检查保留为独立证据，失败也可追溯。 */
    public Map<String, Object> validateDraft(UUID id, String actor) {
        var current = draft(id);
        var errors = new ArrayList<String>();
        var checks = new ArrayList<Map<String, Object>>();
        Map<String, Object> report =
                Map.of(
                        "compatible",
                        false,
                        "breaking",
                        false,
                        "reasons",
                        List.of("Model not valid"));
        try {
            JsonNode spec = canonical(current);
            var model = typed.decode((String) current.get("entity_type"), spec);
            checkReferences(model, spec);
            checkDeclarations(model, spec);
            report = compatibilityReport(current, spec, model.ref().modelVersion());
            if (Boolean.FALSE.equals(report.get("versionAllowed")))
                errors.addAll((List<String>) report.get("versionErrors"));
            checks.addAll(sourceChecks(spec, model, actor));
            for (var check : checks)
                if (!"VERIFIED".equals(check.get("status")))
                    errors.add("Source gate: " + check.get("status"));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            errors.add(ex.getMessage());
        }
        return Map.of(
                "valid",
                errors.isEmpty(),
                "errors",
                errors,
                "compatibility",
                report,
                "sources",
                checks);
    }

    @Transactional
    public Map<String, Object> publish(UUID id, JsonNode req, String actor) {
        lockDraft(id);
        var current = draft(id);
        if (!"APPROVED".equals(current.get("state")))
            throw new IllegalArgumentException("Only APPROVED drafts can be published");
        String entity = (String) current.get("entity_id"),
                type = (String) current.get("entity_type"),
                version = text(req, "version");
        ModelCompatibility.Version.parse(version);
        // 同一语义身份的多个草稿串行发布，防止并发请求绕过递增版本和兼容性检查。
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", entity);
        JsonNode spec = canonical(current);
        var model = typed.decode(type, spec);
        if (!version.equals(model.ref().modelVersion()))
            throw new IllegalArgumentException(
                    "Publish version must equal typed model.ref.modelVersion");
        var refs = checkReferences(model, spec);
        checkDeclarations(model, spec);
        var compatibilityResult = compatibilityReport(current, spec, version);
        if (Boolean.FALSE.equals(compatibilityResult.get("versionAllowed")))
            throw new IllegalArgumentException(
                    String.join("; ", (List<String>) compatibilityResult.get("versionErrors")));
        var checks = sourceChecks(spec, model, actor);
        for (var check : checks)
            if (!"VERIFIED".equals(check.get("status")))
                throw new IllegalArgumentException(
                        "Source publication gate: "
                                + check.get("status")
                                + " for "
                                + check.get("externalRef"));
        var dependencyManifest = new ArrayList<Map<String, Object>>();
        for (var ref :
                refs.values().stream().sorted(Comparator.comparing(EntityRef::id)).toList()) {
            var target = target(ref);
            dependencyManifest.add(
                    Map.of(
                            "entityId",
                            ref.id(),
                            "version",
                            ref.modelVersion(),
                            "releaseId",
                            target.get("id"),
                            "contentHash",
                            target.get("content_hash")));
        }
        var sourceManifest = new ArrayList<Map<String, Object>>();
        for (var check : checks)
            sourceManifest.add(
                    Map.of(
                            "sourceSystem",
                            check.get("sourceSystem"),
                            "externalRef",
                            check.get("externalRef"),
                            "fingerprint",
                            check.get("fingerprint"),
                            "snapshotId",
                            check.get("snapshotId"),
                            "sourceVersion",
                            check.get("sourceVersion"),
                            "direct",
                            check.get("direct")));
        sourceManifest.sort(Comparator.comparing(m -> json.canonical(json.parse(json.write(m)))));
        var document =
                json.object(
                        json.write(
                                Map.of(
                                        "schemaVersion",
                                        spec.path("schemaVersion").asInt(),
                                        "domain",
                                        current.get("domain"),
                                        "entityId",
                                        entity,
                                        "entityType",
                                        type,
                                        "version",
                                        version,
                                        "spec",
                                        spec,
                                        "dependencyManifest",
                                        dependencyManifest,
                                        "sourceManifest",
                                        sourceManifest)));
        // Hash 覆盖完整发布身份、类型、精确依赖和来源快照；相同业务名称不代表同一实现。
        UUID release = UUID.randomUUID();
        String hash = json.sha256(document);
        db.update(
                "INSERT INTO awe_release(id,draft_id,domain,entity_id,entity_type,version,content_hash,spec,published_by,schema_version,canonical_document) VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb)",
                release,
                id,
                current.get("domain"),
                entity,
                type,
                version,
                hash,
                json.canonical(spec),
                actor,
                spec.path("schemaVersion").asInt(),
                json.canonical(document));
        for (var ref : refs.values()) {
            var target = target(ref);
            db.update(
                    "INSERT INTO awe_release_relation(release_id,target_entity_id,relation_type,target_version,target_release_id,target_content_hash) VALUES(?,?,'DEPENDS_ON',?,?,?)",
                    release,
                    ref.id(),
                    ref.modelVersion(),
                    UUID.fromString((String) target.get("id")),
                    target.get("content_hash"));
        }
        for (JsonNode binding : spec.path("bindings")) {
            var check =
                    checks.stream()
                            .filter(
                                    c ->
                                            Objects.equals(
                                                            c.get("sourceSystem"),
                                                            text(binding, "sourceSystem"))
                                                    && Objects.equals(
                                                            c.get("externalRef"),
                                                            text(binding, "externalRef"))
                                                    && Boolean.TRUE.equals(c.get("direct")))
                            .findFirst()
                            .orElseThrow();
            db.update(
                    "INSERT INTO awe_source_binding(release_id,source_system,external_ref,source_fingerprint,verification_status,source_snapshot_id,verified_at) VALUES(?,?,?,?,'VERIFIED',?,clock_timestamp())",
                    release,
                    text(binding, "sourceSystem"),
                    text(binding, "externalRef"),
                    text(binding, "fingerprint"),
                    UUID.fromString((String) check.get("snapshotId")));
        }
        db.update("UPDATE awe_draft SET state='PUBLISHED',updated_at=NOW() WHERE id=?", id);
        audit(
                entity,
                id,
                "PUBLISHED",
                actor,
                Map.of(
                        "version",
                        version,
                        "contentHash",
                        hash,
                        "schemaVersion",
                        spec.path("schemaVersion").asInt()));
        return release(release);
    }

    public List<Map<String, Object>> releases() {
        return db.queryForList(
                "SELECT id::text AS id,entity_id,entity_type,domain,version,content_hash,schema_version,published_at FROM awe_release ORDER BY published_at DESC LIMIT 200");
    }

    public Map<String, Object> release(UUID id) {
        var r =
                one(
                        "SELECT id::text AS id,entity_id,entity_type,domain,version,content_hash,schema_version,spec::text AS spec,canonical_document::text AS canonical_document,published_at FROM awe_release WHERE id=?",
                        id);
        r.put("spec", json.parse((String) r.get("spec")));
        if (r.get("canonical_document") != null)
            r.put("canonical_document", json.parse((String) r.get("canonical_document")));
        r.put(
                "relations",
                db.queryForList(
                        "SELECT target_entity_id,relation_type,target_version,target_release_id::text,target_content_hash FROM awe_release_relation WHERE release_id=?",
                        id));
        r.put(
                "bindings",
                db.queryForList(
                        "SELECT source_system,external_ref,source_fingerprint,verification_status,source_snapshot_id::text,verified_at FROM awe_source_binding WHERE release_id=?",
                        id));
        r.put("capability_status", "DECLARED_ONLY");
        r.put(
                "limitations",
                List.of(
                        "Release approval is governance, not proof of business truth",
                        "Function/Action declarations do not authorize or execute warehouse tasks",
                        "Metadata checks do not establish row quality, uniqueness or numerical BI equivalence"));
        return r;
    }

    public List<Map<String, Object>> impact(String entityId) {
        return db.queryForList(
                "SELECT r.entity_id AS dependent,r.version,r.entity_type,rel.relation_type,rel.target_version,rel.target_content_hash FROM awe_release_relation rel JOIN awe_release r ON r.id=rel.release_id WHERE rel.target_entity_id=? ORDER BY r.published_at DESC",
                entityId);
    }

    public List<Map<String, Object>> auditTrail(String entityId) {
        return db.queryForList(
                "SELECT event_type,actor,details::text AS details,created_at FROM awe_audit WHERE entity_id=? ORDER BY created_at DESC LIMIT 100",
                entityId);
    }

    public List<Map<String, Object>> validationChecks(String entityId) {
        return checkLog.history(entityId);
    }

    public List<Map<String, Object>> gaps() {
        var rows =
                db.queryForList(
                        "SELECT id::text,release_id::text,entity_id,source_system,external_ref,status,reason,details::text,occurrences,created_at,last_checked_at,resolved_at FROM awe_gap ORDER BY last_checked_at DESC LIMIT 200");
        rows.forEach(r -> r.put("details", json.parse((String) r.get("details"))));
        return rows;
    }

    @Transactional
    public Map<String, Object> verifyRelease(UUID id, String actor) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "verify:" + id);
        var release = release(id);
        var spec = (JsonNode) release.get("spec");
        if (!Set.of(2, 3).contains(release.get("schema_version")))
            return Map.of(
                    "status",
                    "LEGACY_UNVERIFIED",
                    "checks",
                    List.of(),
                    "impact",
                    impact((String) release.get("entity_id")),
                    "gaps",
                    gaps());
        var model = typed.decode((String) release.get("entity_type"), spec);
        var checks = sourceChecks(spec, model, actor);
        String status =
                unmaterialized(model, spec, 0)
                        ? "UNMATERIALIZED"
                        : checks.isEmpty() ? "NOT_APPLICABLE" : "VERIFIED";
        for (var check : checks) {
            boolean verified = "VERIFIED".equals(check.get("status"));
            if (!verified) {
                if ("VERIFIED".equals(status) || "DRIFTED".equals(check.get("status")))
                    status = (String) check.get("status");
                if (Set.of("DRIFTED", "CONTRACT_MISMATCH").contains(check.get("status")))
                    db.update(
                            "INSERT INTO awe_gap(id,release_id,entity_id,source_system,external_ref,reason,details) VALUES(?,?,?,?,?,?,?::jsonb) ON CONFLICT(release_id,source_system,external_ref) DO UPDATE SET status='OPEN',reason=EXCLUDED.reason,details=EXCLUDED.details,occurrences=awe_gap.occurrences+1,last_checked_at=clock_timestamp(),resolved_at=NULL",
                            UUID.randomUUID(),
                            id,
                            release.get("entity_id"),
                            check.get("sourceSystem"),
                            check.get("externalRef"),
                            check.get("status"),
                            json.write(check));
            } else
                db.update(
                        "UPDATE awe_gap SET status='RESOLVED',details=?::jsonb,last_checked_at=clock_timestamp(),resolved_at=clock_timestamp() WHERE release_id=? AND source_system=? AND external_ref=? AND status='OPEN'",
                        json.write(check),
                        id,
                        check.get("sourceSystem"),
                        check.get("externalRef"));
        }
        audit(
                (String) release.get("entity_id"),
                null,
                "SOURCE_RECHECKED",
                actor,
                Map.of("releaseId", id.toString(), "status", status, "checks", checks));
        return Map.of(
                "status",
                status,
                "checks",
                checks,
                "impact",
                impact((String) release.get("entity_id")),
                "gaps",
                gaps());
    }

    private JsonNode canonical(Map<String, Object> current) {
        String type = (String) current.get("entity_type");
        var spec = (JsonNode) current.get("spec");
        validator.validate(
                (String) current.get("domain"), (String) current.get("entity_id"), type, spec);
        return typed.canonicalSpec(type, spec);
    }

    /** 以数值版本选择最新基线；破坏性变更和旧模型迁移必须显式升级主版本。 */
    private Map<String, Object> compatibilityReport(
            Map<String, Object> current, JsonNode spec, String version) {
        var next = ModelCompatibility.Version.parse(version);
        var prior =
                db.queryForList(
                        "SELECT entity_type,version,schema_version,spec::text AS spec FROM awe_release WHERE entity_id=?",
                        current.get("entity_id"));
        Map<String, Object> latest = null;
        for (var row : prior)
            if (latest == null
                    || ModelCompatibility.Version.parse((String) row.get("version"))
                                    .compareTo(
                                            ModelCompatibility.Version.parse(
                                                    (String) latest.get("version")))
                            > 0) latest = row;
        var report =
                new LinkedHashMap<String, Object>(
                        compatibility.compare(
                                (String) current.get("entity_type"),
                                latest == null ? null : json.parse((String) latest.get("spec")),
                                spec));
        var versionErrors = new ArrayList<String>();
        if (latest != null) {
            var before = ModelCompatibility.Version.parse((String) latest.get("version"));
            if (next.compareTo(before) <= 0)
                versionErrors.add("Version must increase beyond " + latest.get("version"));
            if (!latest.get("entity_type").equals(current.get("entity_type"))) {
                var legacyFamilies =
                        Map.of(
                                "BusinessEntity",
                                "BUSINESS_ENTITY",
                                "BusinessEvent",
                                "BUSINESS_EVENT",
                                "Dimension",
                                "DIMENSION",
                                "Pipeline",
                                "PIPELINE",
                                "DataProduct",
                                "DATA_PRODUCT");
                boolean explicitLegacyMigration =
                        Integer.valueOf(1).equals(latest.get("schema_version"))
                                && "ObjectType".equals(current.get("entity_type"))
                                && Objects.equals(
                                        legacyFamilies.get(latest.get("entity_type")),
                                        spec.path("model").path("family").asText())
                                && next.major().compareTo(before.major()) > 0;
                if (!explicitLegacyMigration)
                    versionErrors.add(
                            "Entity metamodel type is immutable except explicit major-version legacy family migration");
            }
            if (Boolean.TRUE.equals(report.get("breaking"))
                    && next.major().compareTo(before.major()) <= 0)
                versionErrors.add(
                        "Breaking model change requires a major version: " + report.get("reasons"));
        }
        report.put("versionAllowed", versionErrors.isEmpty());
        report.put("versionErrors", versionErrors);
        return report;
    }

    /** 将类型内引用、显式依赖和能力断言统一锁定到已发布版本，禁止隐式采用 latest。 */
    private Map<String, EntityRef> checkReferences(AweDefinition model, JsonNode spec) {
        var refs = new LinkedHashMap<String, EntityRef>();
        for (var ref : typed.references(model.getClass().getSimpleName(), spec)) addRef(refs, ref);
        for (JsonNode ref : spec.path("dependsOn"))
            addRef(refs, new EntityRef(text(ref, "id"), text(ref, "modelVersion")));
        if (model instanceof FunctionDefinition f) {
            for (String ref : f.requiredAssertions()) addRef(refs, assertionRef(ref));
            // 执行 Profile 的返回 Schema 是正式版本依赖，必须进入发布 Hash 与依赖清单。
            if (DatasetExtractProfile.matches(f))
                addRef(refs, DatasetExtractProfile.parseResultRef(f.resultSchemaRef()));
            if (DatasetReadProfile.matches(f))
                addRef(refs, DatasetReadProfile.parseResultRef(f.resultSchemaRef()));
            if (MetricQueryProfile.matches(f))
                addRef(refs, DatasetExtractProfile.parseResultRef(f.resultSchemaRef()));
        }
        if (model instanceof ActionDefinition a)
            for (String ref : a.postconditionAssertions()) addRef(refs, assertionRef(ref));
        for (var ref : refs.values()) {
            if (ref.id().equals(model.ref().id()))
                throw new IllegalArgumentException("Self dependency forbidden");
            ModelCompatibility.Version.parse(ref.modelVersion());
            target(ref);
        }
        if (model instanceof SemanticModel.Dataset m) {
            requireTarget(m.subjectType(), "ObjectType");
            m.dimensions().forEach(this::requireDimension);
            if (spec.path("schemaVersion").asInt() == 3) {
                var facet = typed.datasetContract(spec);
                requireTarget(facet.rowSchema(), "ObjectType");
                var schema =
                        (ObjectType)
                                typed.decode(
                                        "ObjectType",
                                        json.parse((String) target(facet.rowSchema()).get("spec")));
                if (schema.family() != ObjectType.Family.DATASET
                        || !schema.identityKeys().equals(m.grain().keys()))
                    throw new IllegalArgumentException(
                            "Dataset row Schema must match its explicit grain");
                DatasetExtractProfile.validateSubject(
                        schema,
                        (ObjectType)
                                typed.decode(
                                        "ObjectType",
                                        json.parse((String) target(m.subjectType()).get("spec"))));
            }
        }
        if (model instanceof SemanticModel.Metric m) {
            requireTarget(m.subjectType(), "ObjectType");
            requireTarget(m.sourceDataset(), "Dataset");
            m.allowedDimensions().forEach(this::requireDimension);
            if (spec.path("schemaVersion").asInt() == 3)
                validateMetricFacet(m, typed.metricContract(spec));
        }
        if (model instanceof SemanticModel.Feature m) {
            requireTarget(m.subjectType(), "ObjectType");
            requireTarget(m.sourceDataset(), "Dataset");
        }
        if (model instanceof LinkType l) {
            requireTarget(l.fromType(), "ObjectType", "Dataset", "Metric", "Feature");
            requireTarget(l.toType(), "ObjectType", "Dataset", "Metric", "Feature");
        }
        if (model instanceof FunctionDefinition f) {
            for (String ref : f.requiredAssertions())
                requireTarget(assertionRef(ref), "EvidenceAssertion");
            if (DatasetExtractProfile.matches(f)) {
                requireTarget(f.ownerType(), "Dataset");
                EntityRef resultRef = DatasetExtractProfile.parseResultRef(f.resultSchemaRef());
                requireTarget(resultRef, "ObjectType");
                var owner = target(f.ownerType());
                var ownerSpec = json.parse((String) owner.get("spec"));
                var row = target(resultRef);
                var dataset = (SemanticModel.Dataset) typed.decode("Dataset", ownerSpec);
                var rowSchema =
                        (ai.awe.kernel.model.ObjectType)
                                typed.decode("ObjectType", json.parse((String) row.get("spec")));
                DatasetExtractProfile.validate(f, dataset, rowSchema, ownerSpec);
                requireTarget(dataset.subjectType(), "ObjectType");
                var subject = target(dataset.subjectType());
                DatasetExtractProfile.validateSubject(
                        rowSchema,
                        (ai.awe.kernel.model.ObjectType)
                                typed.decode(
                                        "ObjectType", json.parse((String) subject.get("spec"))));
            }
            if (DatasetReadProfile.matches(f)) {
                requireTarget(f.ownerType(), "Dataset");
                EntityRef resultRef = DatasetReadProfile.parseResultRef(f.resultSchemaRef());
                requireTarget(resultRef, "ObjectType");
                var ownerSpec = json.parse((String) target(f.ownerType()).get("spec"));
                var dataset = (SemanticModel.Dataset) typed.decode("Dataset", ownerSpec);
                var rowSchema =
                        (ObjectType)
                                typed.decode(
                                        "ObjectType",
                                        json.parse((String) target(resultRef).get("spec")));
                DatasetReadProfile.validate(f, dataset, rowSchema, ownerSpec);
                DatasetExtractProfile.validateSubject(
                        rowSchema,
                        (ObjectType)
                                typed.decode(
                                        "ObjectType",
                                        json.parse(
                                                (String)
                                                        target(dataset.subjectType())
                                                                .get("spec"))));
            }
            if (MetricQueryProfile.matches(f)) {
                requireTarget(f.ownerType(), "Metric");
                var ownerSpec = json.parse((String) target(f.ownerType()).get("spec"));
                MetricQueryProfile.validate(
                        f, (SemanticModel.Metric) typed.decode("Metric", ownerSpec), ownerSpec);
            }
        }
        if (model instanceof ActionDefinition a)
            for (String ref : a.postconditionAssertions())
                requireTarget(assertionRef(ref), "EvidenceAssertion");
        if (model instanceof ObjectType object
                && object.family() == ObjectType.Family.PIPELINE
                && spec.path("schemaVersion").asInt() == 3)
            validatePipelineFacet(typed.pipelineContract(spec));
        return refs;
    }

    private void requireDimension(EntityRef ref) {
        requireTarget(ref, "ObjectType");
        var row = target(ref);
        if (!"DIMENSION"
                .equals(json.parse((String) row.get("spec")).path("model").path("family").asText()))
            throw new IllegalArgumentException(
                    "Dimension reference must target an ObjectType DIMENSION: " + ref.id());
    }

    private record ReadSource(SemanticModel.Dataset dataset, ObjectType schema) {}

    /** 正式计算只能从注册读取签名获得字段，不能借普通 Function 字符串注入计算。 */
    private Map<String, ReadSource> declaredSources(List<MetricContract.Source> declarations) {
        var result = new LinkedHashMap<String, ReadSource>();
        for (var source : declarations) {
            requireTarget(source.readFunction(), "FunctionDefinition");
            var function =
                    (FunctionDefinition)
                            typed.decode(
                                    "FunctionDefinition",
                                    json.parse((String) target(source.readFunction()).get("spec")));
            if (!DatasetExtractProfile.matches(function) && !DatasetReadProfile.matches(function))
                throw new IllegalArgumentException(
                        "Semantic source requires a registered read Function");
            requireTarget(function.ownerType(), "Dataset");
            var datasetSpec = json.parse((String) target(function.ownerType()).get("spec"));
            var dataset = (SemanticModel.Dataset) typed.decode("Dataset", datasetSpec);
            var schemaRef = DatasetExtractProfile.parseResultRef(function.resultSchemaRef());
            requireTarget(schemaRef, "ObjectType");
            var schema =
                    (ObjectType)
                            typed.decode(
                                    "ObjectType",
                                    json.parse((String) target(schemaRef).get("spec")));
            if (DatasetExtractProfile.matches(function))
                DatasetExtractProfile.validate(function, dataset, schema, datasetSpec);
            else DatasetReadProfile.validate(function, dataset, schema, datasetSpec);
            result.put(source.alias(), new ReadSource(dataset, schema));
        }
        return result;
    }

    private void validateMetricFacet(SemanticModel.Metric metric, MetricContract contract) {
        var sources = declaredSources(contract.sources());
        if (!sources.get(contract.sources().getFirst().alias())
                .dataset()
                .ref()
                .equals(metric.sourceDataset()))
            throw new IllegalArgumentException(
                    "Metric primary source must equal its formal sourceDataset");
        var schemas = new LinkedHashMap<String, ObjectType>();
        sources.forEach((alias, source) -> schemas.put(alias, source.schema()));
        SemanticPlanTypes.validate(contract, schemas);
        var datasets = new LinkedHashMap<String, SemanticModel.Dataset>();
        sources.forEach((alias, source) -> datasets.put(alias, source.dataset()));
        var subject =
                (ObjectType)
                        typed.decode(
                                "ObjectType",
                                json.parse(
                                        (String)
                                                target(contract.statisticalSubject()).get("spec")));
        MetricIdentityChecks.validate(metric, contract, subject, datasets, schemas);
        validateJoins(contract.plan(), sources);
        var kinds = new HashSet<RelationalIR.AggregateKind>();
        var arithmetic = new HashSet<RelationalIR.ArithmeticOp>();
        collectOperations(contract.plan(), kinds, arithmetic);
        boolean compatible =
                switch (metric.aggregation().kind()) {
                    case COUNT ->
                            kinds.contains(RelationalIR.AggregateKind.COUNT)
                                    || kinds.contains(RelationalIR.AggregateKind.COUNT_ROWS);
                    case COUNT_DISTINCT ->
                            kinds.contains(RelationalIR.AggregateKind.COUNT_DISTINCT);
                    case SUM -> kinds.contains(RelationalIR.AggregateKind.SUM);
                    case AVG -> kinds.contains(RelationalIR.AggregateKind.AVG);
                    case RATIO -> arithmetic.contains(RelationalIR.ArithmeticOp.DIVIDE);
                    case CUSTOM -> !kinds.isEmpty() || !arithmetic.isEmpty();
                    case LAST -> false;
                };
        if (!compatible)
            throw new IllegalArgumentException(
                    "Metric aggregation kind is not implemented by its formal plan");
    }

    private void validatePipelineFacet(PipelineContract contract) {
        var sources = declaredSources(contract.sources());
        var schemas = new LinkedHashMap<String, ObjectType>();
        sources.forEach((alias, source) -> schemas.put(alias, source.schema()));
        var subjects = new HashSet<EntityRef>();
        sources.values().forEach(s -> subjects.add(s.dataset().ref()));
        for (var target : contract.targets()) {
            requireTarget(target.dataset(), "Dataset");
            requireTarget(target.rowSchema(), "ObjectType");
            var targetSpec = json.parse((String) target(target.dataset()).get("spec"));
            var dataset = (SemanticModel.Dataset) typed.decode("Dataset", targetSpec);
            var schema =
                    (ObjectType)
                            typed.decode(
                                    "ObjectType",
                                    json.parse((String) target(target.rowSchema()).get("spec")));
            if (schema.family() != ObjectType.Family.DATASET
                    || !schema.identityKeys().equals(dataset.grain().keys()))
                throw new IllegalArgumentException(
                        "Pipeline output Dataset/Schema grain is inconsistent");
            if (targetSpec.path("schemaVersion").asInt() == 3
                    && !typed.datasetContract(targetSpec).rowSchema().equals(target.rowSchema()))
                throw new IllegalArgumentException(
                        "Pipeline target must use the Dataset declared row Schema");
            var output =
                    new SemanticPlanTypes(schemas, MetricContract.ZeroDivisionPolicy.NULL)
                            .analyze(target.plan());
            if (!output.keySet()
                    .equals(
                            new HashSet<>(
                                    schema.properties().stream()
                                            .map(ObjectType.Property::name)
                                            .toList())))
                throw new IllegalArgumentException(
                        "Pipeline plan output names differ from target Schema");
            for (var property : schema.properties()) {
                var actual = output.get(property.name());
                if (actual.type() != property.type() || property.required() && actual.nullable())
                    throw new IllegalArgumentException(
                            "Pipeline plan output type/nullability differs from target Schema");
            }
            validateJoins(target.plan(), sources);
            subjects.add(target.dataset());
        }
        for (var ref : contract.requiredContracts()) {
            requireTarget(ref, "ContractDefinition");
            var validation =
                    (ContractDefinition)
                            typed.decode(
                                    "ContractDefinition",
                                    json.parse((String) target(ref).get("spec")));
            if (!subjects.contains(validation.subject()))
                throw new IllegalArgumentException(
                        "Pipeline validation contract is outside its exact input/output scope");
            if (validation.obligations().stream()
                    .noneMatch(
                            o -> o.at() == ContractDefinition.Enforcement.EXECUTE && o.blocking()))
                throw new IllegalArgumentException(
                        "Pipeline requires real blocking EXECUTE obligations, not only publication declarations");
        }
    }

    private void validateJoins(RelationalIR.Node node, Map<String, ReadSource> sources) {
        switch (node) {
            case RelationalIR.Scan ignored -> {}
            case RelationalIR.Filter filter -> validateJoins(filter.input(), sources);
            case RelationalIR.Project project -> validateJoins(project.input(), sources);
            case RelationalIR.Aggregate aggregate -> validateJoins(aggregate.input(), sources);
            case RelationalIR.SetOperation set -> {
                validateJoins(set.left(), sources);
                validateJoins(set.right(), sources);
            }
            case RelationalIR.Join join -> {
                requireTarget(join.link(), "LinkType");
                var link =
                        (LinkType)
                                typed.decode(
                                        "LinkType",
                                        json.parse((String) target(join.link()).get("spec")));
                if (!link.certified()
                        || !Set.of(
                                        RelationContract.Cardinality.ONE_TO_ONE,
                                        RelationContract.Cardinality.MANY_TO_ONE)
                                .contains(link.cardinality())
                        || !Set.of(
                                        RelationContract.TemporalPolicy.TIME_INDEPENDENT,
                                        RelationContract.TemporalPolicy.SAME_SNAPSHOT)
                                .contains(link.temporalPolicy())
                        || !"columns.equal.v1".equals(link.identityMappingRef()))
                    throw new IllegalArgumentException(
                            "Join requires a certified bounded equality mapping with supported cardinality/time semantics");
                var rightFields = new LinkedHashSet<String>();
                var leftFields = new LinkedHashSet<String>();
                String leftAlias = null, rightAlias = null;
                for (var key : join.keys()) {
                    String[] left = key.leftField().split("\\."),
                            right = key.rightField().split("\\.");
                    if (left.length != 2
                            || right.length != 2
                            || !RelationalIR.sources(join.left()).contains(left[0])
                            || !RelationalIR.sources(join.right()).contains(right[0]))
                        throw new IllegalArgumentException(
                                "Join keys require exact source-qualified fields on the correct side");
                    if (leftAlias != null && !leftAlias.equals(left[0])
                            || rightAlias != null && !rightAlias.equals(right[0]))
                        throw new IllegalArgumentException(
                                "One Link cannot silently combine unrelated source mappings");
                    leftAlias = left[0];
                    rightAlias = right[0];
                    rightFields.add(right[1]);
                    leftFields.add(left[1]);
                }
                var leftSource = sources.get(leftAlias);
                var rightSource = sources.get(rightAlias);
                if (leftSource == null
                        || rightSource == null
                        || !link.fromType().equals(leftSource.dataset().ref())
                        || !link.toType().equals(rightSource.dataset().ref()))
                    throw new IllegalArgumentException(
                            "Join Link endpoints differ from exact source Datasets");
                if (!rightFields.equals(new LinkedHashSet<>(rightSource.dataset().grain().keys())))
                    throw new IllegalArgumentException(
                            "Many-to-one join keys must cover the complete right Dataset grain");
                if (!keyPreservingScan(join.right()))
                    throw new IllegalArgumentException(
                            "Join right-side primary-key proof currently supports only scans and filters");
                if (link.cardinality() == RelationContract.Cardinality.ONE_TO_ONE
                        && (!leftFields.equals(
                                        new LinkedHashSet<>(leftSource.dataset().grain().keys()))
                                || !keyPreservingScan(join.left())))
                    throw new IllegalArgumentException(
                            "One-to-one join requires complete keys on both scan/filter inputs");
                validateJoins(join.left(), sources);
                validateJoins(join.right(), sources);
            }
        }
    }

    private boolean keyPreservingScan(RelationalIR.Node node) {
        return node instanceof RelationalIR.Scan
                || node instanceof RelationalIR.Filter filter && keyPreservingScan(filter.input());
    }

    private void collectOperations(
            Object value,
            Set<RelationalIR.AggregateKind> kinds,
            Set<RelationalIR.ArithmeticOp> arithmetic) {
        if (value instanceof RelationalIR.Measure measure) kinds.add(measure.kind());
        if (value instanceof RelationalIR.Arithmetic expression)
            arithmetic.add(expression.operation());
        if (value instanceof Collection<?> list) {
            list.forEach(item -> collectOperations(item, kinds, arithmetic));
            return;
        }
        if (value != null && value.getClass().isRecord())
            for (var component : value.getClass().getRecordComponents())
                try {
                    Object child = component.getAccessor().invoke(value);
                    if (child instanceof RelationalIR.Node
                            || child instanceof RelationalIR.Expr
                            || child instanceof RelationalIR.Predicate
                            || child instanceof Collection<?>)
                        collectOperations(child, kinds, arithmetic);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
    }

    private boolean logicalTarget(AweDefinition definition, JsonNode spec) {
        return definition instanceof SemanticModel.Dataset
                && spec.path("schemaVersion").asInt() == 3
                && typed.datasetContract(spec).mode() == DatasetContract.Mode.LOGICAL_TARGET;
    }

    private Optional<JsonNode> effectiveBinding(EntityRef dataset, JsonNode spec) {
        var resolver = realizations.getIfAvailable();
        if (resolver == null) return Optional.empty();
        var binding = resolver.binding(dataset);
        if (binding.isEmpty()) return binding;
        var value = binding.get();
        var fields = new HashSet<String>();
        value.fieldNames().forEachRemaining(fields::add);
        if (!value.isObject()
                || !fields.equals(
                        Set.of("sourceSystem", "externalRef", "fingerprint", "timeFields"))
                || !value.path("sourceSystem").isTextual()
                || !value.path("externalRef").isTextual()
                || !value.path("fingerprint").isTextual()
                || !value.path("fingerprint").asText().matches("[a-f0-9]{64}")
                || !value.path("timeFields").isObject())
            throw new IllegalArgumentException(
                    "Trusted realization returned an unsupported physical binding");
        var model = (SemanticModel.Dataset) typed.decode("Dataset", spec);
        var roles = new HashSet<String>();
        value.path("timeFields").fieldNames().forEachRemaining(roles::add);
        var facet = typed.datasetContract(spec);
        Set<String> expected =
                facet.readMode() == DatasetContract.ReadMode.STATIC_SNAPSHOT
                        ? Set.of()
                        : model.time().roles().stream()
                                .map(Enum::name)
                                .collect(java.util.stream.Collectors.toSet());
        if (!roles.equals(expected))
            throw new IllegalArgumentException(
                    "Realization time-role mapping differs from the immutable Dataset definition");
        for (var entry : value.path("timeFields").properties())
            if (!entry.getValue().isTextual())
                throw new IllegalArgumentException(
                        "Realization time mappings must be explicit columns");
        return binding;
    }

    private boolean unmaterialized(AweDefinition model, JsonNode spec, int depth) {
        if (depth > 32)
            throw new IllegalArgumentException("Materialization scope exceeds the supported bound");
        if (logicalTarget(model, spec))
            return effectiveBinding(((SemanticModel.Dataset) model).ref(), spec).isEmpty();
        if (model instanceof FunctionDefinition f
                && (DatasetReadProfile.matches(f) || MetricQueryProfile.matches(f))) {
            var owner = target(f.ownerType());
            var ownerSpec = json.parse((String) owner.get("spec"));
            return unmaterialized(
                    typed.decode((String) owner.get("entity_type"), ownerSpec),
                    ownerSpec,
                    depth + 1);
        }
        if (spec.path("schemaVersion").asInt() == 3
                && (model instanceof SemanticModel.Metric
                        || model instanceof ObjectType object
                                && object.family() == ObjectType.Family.PIPELINE)) {
            var declarations =
                    model instanceof SemanticModel.Metric
                            ? typed.metricContract(spec).sources()
                            : typed.pipelineContract(spec).sources();
            for (var declaration : declarations) {
                var source = target(declaration.readFunction());
                var sourceSpec = json.parse((String) source.get("spec"));
                if (unmaterialized(
                        typed.decode((String) source.get("entity_type"), sourceSpec),
                        sourceSpec,
                        depth + 1)) return true;
            }
        }
        return false;
    }

    /** 证据快照只能属于被声明模型的实际实现链，不能借用任意依赖的成功观测。 */
    private boolean snapshotInSourceScope(Map<String, Object> subject, UUID snapshot) {
        var pending = new ArrayDeque<Map<String, Object>>();
        pending.add(subject);
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            var row = pending.removeFirst();
            String id = (String) row.get("id");
            if (!seen.add(id)) continue;
            // 有界遍历并去重；范围过大时拒绝声明，不能截断后误报证据有效。
            if (seen.size() > 64)
                throw new IllegalArgumentException(
                        "Evidence implementation scope exceeds supported depth");
            Integer count =
                    db.queryForObject(
                            "SELECT count(*) FROM awe_source_snapshot s JOIN awe_source_binding b ON b.source_system=s.source_system AND b.external_ref=s.external_ref AND b.source_fingerprint=s.fingerprint WHERE s.id=? AND b.release_id=?",
                            Integer.class,
                            snapshot,
                            UUID.fromString(id));
            if (count != null && count > 0) return true;
            if (!Set.of(2, 3).contains(row.get("schema_version"))) continue;
            var rowSpec = json.parse((String) row.get("spec"));
            var value = typed.decode((String) row.get("entity_type"), rowSpec);
            EntityRef implementation = null;
            if (value instanceof SemanticModel.Metric metric) {
                if (rowSpec.path("schemaVersion").asInt() == 3)
                    typed.metricContract(rowSpec)
                            .sources()
                            .forEach(source -> pending.add(target(source.readFunction())));
                else implementation = metric.sourceDataset();
            }
            if (value instanceof SemanticModel.Feature feature)
                implementation = feature.sourceDataset();
            if (value instanceof ContractDefinition contract) implementation = contract.subject();
            if (value instanceof FunctionDefinition function) implementation = function.ownerType();
            if (value instanceof ActionDefinition action) implementation = action.ownerType();
            if (implementation != null) pending.add(target(implementation));
        }
        return false;
    }

    private void addRef(Map<String, EntityRef> refs, EntityRef ref) {
        var old = refs.putIfAbsent(ref.id(), ref);
        if (old != null && !old.equals(ref))
            throw new IllegalArgumentException(
                    "Multiple versions of one dependency require an explicit compatibility adapter");
    }

    private EntityRef assertionRef(String value) {
        int at = value.lastIndexOf('@');
        if (at < 1 || !value.startsWith("assertion."))
            throw new IllegalArgumentException(
                    "Assertion references require canonical id@major.minor.patch");
        return new EntityRef(value.substring(0, at), value.substring(at + 1));
    }

    private void requireTarget(EntityRef ref, String... types) {
        var row = target(ref);
        if (!Set.of(2, 3).contains(row.get("schema_version"))
                || !Arrays.asList(types).contains(row.get("entity_type")))
            throw new IllegalArgumentException(
                    "Wrong or legacy dependency family for "
                            + ref.id()
                            + "; expected "
                            + Arrays.toString(types));
    }

    private Map<String, Object> target(EntityRef ref) {
        var rows =
                db.queryForList(
                        "SELECT id::text,entity_id,entity_type,version,content_hash,schema_version,spec::text AS spec FROM awe_release WHERE entity_id=? AND version=?",
                        ref.id(),
                        ref.modelVersion());
        if (rows.isEmpty())
            throw new IllegalArgumentException(
                    "Unpublished pinned dependency: " + ref.id() + "@" + ref.modelVersion());
        return rows.getFirst();
    }

    private void checkDeclarations(AweDefinition model, JsonNode spec) {
        if (model instanceof ContractDefinition c)
            for (var obligation : c.obligations())
                if (obligation.blocking()) {
                    boolean supported =
                            obligation.ruleId().equals("check.model.static")
                                    && Set.of(
                                                    ContractDefinition.Enforcement.AUTHORING,
                                                    ContractDefinition.Enforcement.PUBLISH)
                                            .contains(obligation.at());
                    supported |=
                            obligation.ruleId().equals("check.source.consistency")
                                    && obligation.at() == ContractDefinition.Enforcement.PUBLISH;
                    supported |=
                            RuntimeRules.DATA_CHECKS.contains(obligation.ruleId())
                                    && obligation.at() == ContractDefinition.Enforcement.EXECUTE;
                    supported |=
                            Set.of(
                                                    "check.pipeline.output.schema",
                                                    "check.pipeline.output.grain",
                                                    "check.pipeline.result.equivalence",
                                                    "check.pipeline.consumer.regression")
                                            .contains(obligation.ruleId())
                                    && obligation.at() == ContractDefinition.Enforcement.EXECUTE;
                    if (!supported)
                        throw new IllegalArgumentException(
                                "Unsupported blocking rule/stage: "
                                        + obligation.ruleId()
                                        + " at "
                                        + obligation.at());
                    var subject = target(c.subject());
                    validator.validate(
                            domainOf(c.subject()),
                            c.subject().id(),
                            (String) subject.get("entity_type"),
                            json.parse((String) subject.get("spec")));
                }
        if (model instanceof EvidenceAssertion e) {
            var subject = target(e.subject());
            if (!e.modelReleaseHash().equals(subject.get("content_hash")))
                throw new IllegalArgumentException(
                        "Evidence modelReleaseHash does not match pinned subject");
            if (e.checkedAt().isAfter(Instant.now().plusSeconds(60)))
                throw new IllegalArgumentException("Evidence checkedAt cannot be in the future");
            UUID snapshot;
            try {
                snapshot = UUID.fromString(e.sourceSnapshotRef());
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "Evidence requires a persisted source snapshot UUID");
            }
            if (!snapshotInSourceScope(subject, snapshot))
                throw new IllegalArgumentException(
                        "Evidence snapshot is outside the pinned subject source scope");
            // 人工审核的 Evidence 仍是声明；只有来源适配器生成的实际观测可通过实时来源 Gate。
        }
    }

    private String domainOf(EntityRef ref) {
        return ref.id().split("\\.")[1];
    }

    private List<Map<String, Object>> sourceChecks(
            JsonNode spec, AweDefinition model, String actor) {
        return sourceChecks(spec, model, actor, 0);
    }

    /** 沿绑定的实现链重新观测，派生模型不能以没有直接绑定为由跳过来源一致性。 */
    private List<Map<String, Object>> sourceChecks(
            JsonNode spec, AweDefinition model, String actor, int depth) {
        // 深度超过能力边界即失败，不把部分检查包装成整条实现链通过。
        if (depth > 32)
            throw new IllegalArgumentException(
                    "Source implementation scope exceeds supported depth");
        var checks = new ArrayList<Map<String, Object>>();
        if (logicalTarget(model, spec)) {
            var effective = effectiveBinding(((SemanticModel.Dataset) model).ref(), spec);
            if (effective.isEmpty()) return checks;
            var physicalSpec = ((com.fasterxml.jackson.databind.node.ObjectNode) spec).deepCopy();
            physicalSpec.putArray("bindings").add(effective.get());
            checkBindings(physicalSpec, spec, model, actor, true, checks);
            return checks;
        }
        if (spec.path("schemaVersion").asInt() == 3
                && (model instanceof SemanticModel.Metric
                        || model instanceof ObjectType object
                                && object.family() == ObjectType.Family.PIPELINE)) {
            checkBindings(spec, model, actor, true, checks);
            List<MetricContract.Source> declarations =
                    model instanceof SemanticModel.Metric
                            ? typed.metricContract(spec).sources()
                            : typed.pipelineContract(spec).sources();
            for (var declaration : declarations) {
                var row = target(declaration.readFunction());
                var sourceSpec = json.parse((String) row.get("spec"));
                var sourceModel = typed.decode((String) row.get("entity_type"), sourceSpec);
                var nested = sourceChecks(sourceSpec, sourceModel, actor, depth + 1);
                if (nested.isEmpty() && !unmaterialized(sourceModel, sourceSpec, depth + 1))
                    throw new IllegalArgumentException(
                            "Executable plan input is not an observed source");
                for (var entry : nested) {
                    var check = new LinkedHashMap<String, Object>(entry);
                    check.put("direct", false);
                    check.put("sourceAlias", declaration.alias());
                    checkLog.append(model, spec, check, actor);
                    checks.add(check);
                }
            }
            return checks;
        }
        if (model instanceof SemanticModel.Dataset && spec.path("bindings").isEmpty())
            throw new IllegalArgumentException(
                    "Dataset publication requires an observed authoritative source binding");
        checkBindings(spec, model, actor, true, checks);
        EntityRef source = null;
        if (model instanceof SemanticModel.Metric m) source = m.sourceDataset();
        if (model instanceof SemanticModel.Feature f) source = f.sourceDataset();
        // 仅已注册的取数 Profile 继承 Dataset 的真实来源 Gate，普通 Function 仍只是声明。
        if (model instanceof FunctionDefinition f
                && (DatasetExtractProfile.matches(f)
                        || DatasetReadProfile.matches(f)
                        || MetricQueryProfile.matches(f))) source = f.ownerType();
        if (model instanceof ContractDefinition c
                && c.obligations().stream()
                        .anyMatch(
                                o -> o.blocking() && o.ruleId().equals("check.source.consistency")))
            source = c.subject();
        if (source != null) {
            var target = target(source);
            var targetSpec = json.parse((String) target.get("spec"));
            var sourceModel = typed.decode((String) target.get("entity_type"), targetSpec);
            var nested = sourceChecks(targetSpec, sourceModel, actor, depth + 1);
            if (nested.isEmpty() && !unmaterialized(sourceModel, targetSpec, depth + 1))
                throw new IllegalArgumentException(
                        "Source consistency subject has no checkable source binding");
            for (var check : nested) {
                check.put("direct", false);
                if (sourceModel instanceof SemanticModel.Dataset dataset
                        && model instanceof SemanticModel
                        && "VERIFIED".equals(check.get("status"))) {
                    JsonNode binding = null;
                    for (var b : targetSpec.path("bindings"))
                        if (text(b, "sourceSystem").equals(check.get("sourceSystem"))
                                && text(b, "externalRef").equals(check.get("externalRef")))
                            binding = b;
                    JsonNode metadata =
                            (JsonNode)
                                    sources.snapshot(
                                                    UUID.fromString(
                                                            (String) check.get("snapshotId")))
                                            .get("metadata");
                    var errors =
                            physical.errorsAgainstDataset(
                                    model, dataset, Objects.requireNonNull(binding), metadata);
                    if (!errors.isEmpty()) {
                        check.put("status", "CONTRACT_MISMATCH");
                        check.put("errors", errors);
                    }
                }
                checkLog.append(model, spec, check, actor);
                checks.add(check);
            }
        }
        return checks;
    }

    private void checkBindings(
            JsonNode spec,
            AweDefinition model,
            String actor,
            boolean direct,
            List<Map<String, Object>> checks) {
        checkBindings(spec, spec, model, actor, direct, checks);
    }

    private void checkBindings(
            JsonNode spec,
            JsonNode evidenceSpec,
            AweDefinition model,
            String actor,
            boolean direct,
            List<Map<String, Object>> checks) {
        for (JsonNode binding : spec.path("bindings")) {
            // 先取得真实来源观测，再比较指纹和模型契约；未配置、不可达或漂移都不能宣告成功。
            var observation =
                    sources.verify(
                            text(binding, "sourceSystem"),
                            text(binding, "externalRef"),
                            text(binding, "fingerprint"),
                            actor);
            var check = new LinkedHashMap<String, Object>();
            check.put("status", observation.status());
            check.put("sourceSystem", observation.system());
            check.put("externalRef", observation.externalRef());
            check.put("fingerprint", observation.fingerprint());
            check.put("snapshotId", observation.snapshotId());
            check.put("sourceVersion", observation.sourceVersion());
            check.put("limitations", observation.limitations());
            check.put("direct", direct);
            if ("VERIFIED".equals(observation.status())) {
                var errors =
                        new ArrayList<>(
                                physical.errors(model, spec, binding, observation.metadata()));
                if (model instanceof SemanticModel.Dataset
                        && spec.path("schemaVersion").asInt() == 3) {
                    var row = target(typed.datasetContract(spec).rowSchema());
                    var rowSpec = json.parse((String) row.get("spec"));
                    errors.addAll(
                            physical.errors(
                                    typed.decode("ObjectType", rowSpec),
                                    rowSpec,
                                    binding,
                                    observation.metadata()));
                }
                if (!errors.isEmpty()) {
                    check.put("status", "CONTRACT_MISMATCH");
                    check.put("errors", errors);
                }
            }
            if (spec != evidenceSpec) check.put("realizationBindingHash", json.sha256(binding));
            checkLog.append(model, evidenceSpec, check, actor);
            checks.add(check);
        }
    }

    private void lockDraft(UUID id) {
        if (db.queryForList("SELECT id FROM awe_draft WHERE id=? FOR UPDATE", id).isEmpty())
            throw new NoSuchElementException("Model not found");
    }

    private Map<String, Object> one(String sql, Object... params) {
        var rows = db.queryForList(sql, params);
        if (rows.isEmpty()) throw new NoSuchElementException("Model not found");
        return new LinkedHashMap<>(rows.getFirst());
    }

    private String text(JsonNode n, String key) {
        var v = n.path(key);
        if (!v.isTextual() || v.asText().isBlank())
            throw new IllegalArgumentException("Missing " + key);
        return v.asText();
    }

    private void audit(String entity, UUID draftId, String event, String actor, Object details) {
        db.update(
                "INSERT INTO awe_audit(entity_id,draft_id,event_type,actor,details,created_at) VALUES(?,?,?,?,?::jsonb,clock_timestamp())",
                entity,
                draftId,
                event,
                actor,
                json.write(details));
    }
}
