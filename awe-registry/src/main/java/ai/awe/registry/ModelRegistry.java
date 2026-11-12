package ai.awe.registry;

import ai.awe.kernel.*;
import ai.awe.registry.source.SourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** The single typed authoring/release boundary. Live verification never mutates release manifests. */
@Service
public class ModelRegistry {
  private final JdbcTemplate db;
  private final JsonDocuments json;
  private final ModelValidation validator;
  private final SourceService sources;
  private final ModelCheckLog checkLog;
  private final TypedModels typed = new TypedModels();
  private final ModelCompatibility compatibility = new ModelCompatibility();
  private final PhysicalContractChecks physical = new PhysicalContractChecks();
  public ModelRegistry(JdbcTemplate db, JsonDocuments json, ModelValidation validator, SourceService sources, ModelCheckLog checkLog) {
    this.db=db; this.json=json; this.validator=validator; this.sources=sources; this.checkLog=checkLog;
  }
  public List<Map<String,Object>> domains() {
    return db.queryForList("SELECT code,display_name,owner_team,created_at FROM awe_domain ORDER BY code");
  }
  @Transactional
  public Map<String,Object> createDomain(JsonNode req,String actor) {
    String code=text(req,"code"), name=text(req,"displayName"), owner=text(req,"ownerTeam");
    validator.domain(code,owner);
    db.update("INSERT INTO awe_domain(code,display_name,owner_team) VALUES(?,?,?)",code,name,owner);
    audit(null,null,"DOMAIN_CREATED",actor,Map.of("domain",code));
    return Map.of("code",code,"displayName",name,"ownerTeam",owner);
  }
  public List<Map<String,Object>> drafts() {
    return db.queryForList("SELECT id::text AS id,domain,entity_id,entity_type,state,revision,author_actor,updated_at FROM awe_draft ORDER BY updated_at DESC LIMIT 200");
  }
  public Map<String,Object> draft(UUID id) {
    var row=one("SELECT id::text AS id,domain,entity_id,entity_type,state,revision,author_actor,reviewed_by,spec::text AS spec FROM awe_draft WHERE id=?",id);
    row.put("spec",json.parse((String)row.get("spec"))); return row;
  }
  @Transactional
  public Map<String,Object> createDraft(JsonNode req,String actor) {
    String domain=text(req,"domain"), entity=text(req,"entityId"), type=text(req,"entityType");
    JsonNode spec=req.path("spec");
    if(!spec.isObject()) throw new IllegalArgumentException("spec must be object");
    if(db.queryForObject("SELECT count(*) FROM awe_domain WHERE code=?",Integer.class,domain)==0)
      throw new IllegalArgumentException("Unknown domain");
    UUID id=UUID.randomUUID();
    db.update("INSERT INTO awe_draft(id,domain,entity_id,entity_type,spec,author_actor) VALUES(?,?,?,?,?::jsonb,?)",id,domain,entity,type,json.canonical(spec),actor);
    audit(entity,id,"DRAFT_CREATED",actor,Map.of("domain",domain)); return draft(id);
  }
  @Transactional
  public Map<String,Object> edit(UUID id,JsonNode req,String actor) {
    lockDraft(id); var current=draft(id);
    if(!"DRAFT".equals(current.get("state"))) throw new IllegalArgumentException("Only DRAFT can be edited");
    var expected=req.path("expectedRevision");
    if(!expected.isIntegralNumber() || !expected.canConvertToInt()) throw new IllegalArgumentException("expectedRevision is required for draft edits");
    if(expected.intValue()!=((Number)current.get("revision")).intValue()) throw new IllegalStateException("Stale draft revision; reload before editing");
    JsonNode spec=req.path("spec");
    if(!spec.isObject()) throw new IllegalArgumentException("spec must be object");
    db.update("UPDATE awe_draft SET spec=?::jsonb,revision=revision+1,updated_at=NOW() WHERE id=?",json.canonical(spec),id);
    audit((String)current.get("entity_id"),id,"DRAFT_UPDATED",actor,Map.of()); return draft(id);
  }
  @Transactional
  public Map<String,Object> submit(UUID id,String actor) {
    lockDraft(id); var current=draft(id);
    if(!"DRAFT".equals(current.get("state"))) throw new IllegalArgumentException("Draft not editable");
    JsonNode spec=canonical(current); AweDefinition model=typed.decode((String)current.get("entity_type"),spec);
    checkReferences(model,spec); checkDeclarations(model,spec);
    // Re-validate when publishing too: approvals never certify stale physical data.
    db.update("UPDATE awe_draft SET spec=?::jsonb,state='IN_REVIEW',updated_at=NOW() WHERE id=?",json.canonical(spec),id);
    audit((String)current.get("entity_id"),id,"SUBMITTED",actor,Map.of("schemaVersion",2)); return draft(id);
  }
  @Transactional
  public Map<String,Object> approve(UUID id,String actor) {
    lockDraft(id); var current=draft(id);
    if(!"IN_REVIEW".equals(current.get("state"))) throw new IllegalArgumentException("Draft is not in review");
    if(actor.equals(current.get("author_actor"))) throw new IllegalArgumentException("Self review forbidden");
    db.update("UPDATE awe_draft SET state='APPROVED',reviewed_by=?,reviewed_at=NOW(),updated_at=NOW() WHERE id=?",actor,id);
    audit((String)current.get("entity_id"),id,"APPROVED",actor,Map.of()); return draft(id);
  }
  /** Reports failures without changing workflow state; physical checks remain durable observations. */
  public Map<String,Object> validateDraft(UUID id,String actor) {
    var current=draft(id); var errors=new ArrayList<String>();
    var checks=new ArrayList<Map<String,Object>>();
    Map<String,Object> report=Map.of("compatible",false,"breaking",false,"reasons",List.of("Model not valid"));
    try {
      JsonNode spec=canonical(current); var model=typed.decode((String)current.get("entity_type"),spec);
      checkReferences(model,spec); checkDeclarations(model,spec);
      report=compatibilityReport(current,spec,model.ref().modelVersion());
      if(Boolean.FALSE.equals(report.get("versionAllowed"))) errors.addAll((List<String>)report.get("versionErrors"));
      checks.addAll(sourceChecks(spec,model,actor));
      for(var check:checks) if(!"VERIFIED".equals(check.get("status"))) errors.add("Source gate: "+check.get("status"));
    } catch(IllegalArgumentException | IllegalStateException ex) { errors.add(ex.getMessage()); }
    return Map.of("valid",errors.isEmpty(),"errors",errors,"compatibility",report,"sources",checks);
  }
  @Transactional
  public Map<String,Object> publish(UUID id,JsonNode req,String actor) {
    lockDraft(id); var current=draft(id);
    if(!"APPROVED".equals(current.get("state"))) throw new IllegalArgumentException("Only APPROVED drafts can be published");
    String entity=(String)current.get("entity_id"), type=(String)current.get("entity_type"), version=text(req,"version");
    ModelCompatibility.Version.parse(version);
    // Serialize competing drafts of the same entity as well as double-publish of one draft.
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",entity);
    JsonNode spec=canonical(current); var model=typed.decode(type,spec);
    if(!version.equals(model.ref().modelVersion())) throw new IllegalArgumentException("Publish version must equal typed model.ref.modelVersion");
    var refs=checkReferences(model,spec); checkDeclarations(model,spec);
    var compatibilityResult=compatibilityReport(current,spec,version);
    if(Boolean.FALSE.equals(compatibilityResult.get("versionAllowed"))) throw new IllegalArgumentException(String.join("; ",(List<String>)compatibilityResult.get("versionErrors")));
    var checks=sourceChecks(spec,model,actor);
    for(var check:checks) if(!"VERIFIED".equals(check.get("status")))
      throw new IllegalArgumentException("Source publication gate: "+check.get("status")+" for "+check.get("externalRef"));
    var dependencyManifest=new ArrayList<Map<String,Object>>();
    for(var ref:refs.values().stream().sorted(Comparator.comparing(EntityRef::id)).toList()) {
      var target=target(ref);
      dependencyManifest.add(Map.of("entityId",ref.id(),"version",ref.modelVersion(),"releaseId",target.get("id"),"contentHash",target.get("content_hash")));
    }
    var sourceManifest=new ArrayList<Map<String,Object>>();
    for(var check:checks) sourceManifest.add(Map.of("sourceSystem",check.get("sourceSystem"),"externalRef",check.get("externalRef"),"fingerprint",check.get("fingerprint"),"snapshotId",check.get("snapshotId"),"sourceVersion",check.get("sourceVersion"),"direct",check.get("direct")));
    sourceManifest.sort(Comparator.comparing(m->json.canonical(json.parse(json.write(m)))));
    var document=json.object(json.write(Map.of("schemaVersion",2,"domain",current.get("domain"),"entityId",entity,"entityType",type,"version",version,"spec",spec,"dependencyManifest",dependencyManifest,"sourceManifest",sourceManifest)));
    UUID release=UUID.randomUUID(); String hash=json.sha256(document);
    db.update("INSERT INTO awe_release(id,draft_id,domain,entity_id,entity_type,version,content_hash,spec,published_by,schema_version,canonical_document) VALUES(?,?,?,?,?,?,?,?::jsonb,?,2,?::jsonb)",
      release,id,current.get("domain"),entity,type,version,hash,json.canonical(spec),actor,json.canonical(document));
    for(var ref:refs.values()) {
      var target=target(ref);
      db.update("INSERT INTO awe_release_relation(release_id,target_entity_id,relation_type,target_version,target_release_id,target_content_hash) VALUES(?,?,'DEPENDS_ON',?,?,?)",release,ref.id(),ref.modelVersion(),UUID.fromString((String)target.get("id")),target.get("content_hash"));
    }
    for(JsonNode binding:spec.path("bindings")) {
      var check=checks.stream().filter(c->Objects.equals(c.get("sourceSystem"),text(binding,"sourceSystem")) && Objects.equals(c.get("externalRef"),text(binding,"externalRef")) && Boolean.TRUE.equals(c.get("direct"))).findFirst().orElseThrow();
      db.update("INSERT INTO awe_source_binding(release_id,source_system,external_ref,source_fingerprint,verification_status,source_snapshot_id,verified_at) VALUES(?,?,?,?,'VERIFIED',?,clock_timestamp())",release,text(binding,"sourceSystem"),text(binding,"externalRef"),text(binding,"fingerprint"),UUID.fromString((String)check.get("snapshotId")));
    }
    db.update("UPDATE awe_draft SET state='PUBLISHED',updated_at=NOW() WHERE id=?",id);
    audit(entity,id,"PUBLISHED",actor,Map.of("version",version,"contentHash",hash,"schemaVersion",2)); return release(release);
  }
  public List<Map<String,Object>> releases() {
    return db.queryForList("SELECT id::text AS id,entity_id,entity_type,domain,version,content_hash,schema_version,published_at FROM awe_release ORDER BY published_at DESC LIMIT 200");
  }
  public Map<String,Object> release(UUID id) {
    var r=one("SELECT id::text AS id,entity_id,entity_type,domain,version,content_hash,schema_version,spec::text AS spec,canonical_document::text AS canonical_document,published_at FROM awe_release WHERE id=?",id);
    r.put("spec",json.parse((String)r.get("spec")));
    if(r.get("canonical_document")!=null) r.put("canonical_document",json.parse((String)r.get("canonical_document")));
    r.put("relations",db.queryForList("SELECT target_entity_id,relation_type,target_version,target_release_id::text,target_content_hash FROM awe_release_relation WHERE release_id=?",id));
    r.put("bindings",db.queryForList("SELECT source_system,external_ref,source_fingerprint,verification_status,source_snapshot_id::text,verified_at FROM awe_source_binding WHERE release_id=?",id));
    r.put("capability_status","DECLARED_ONLY");
    r.put("limitations",List.of("Release approval is governance, not proof of business truth", "Function/Action declarations do not authorize or execute warehouse tasks", "Metadata checks do not establish row quality, uniqueness or numerical BI equivalence"));
    return r;
  }
  public List<Map<String,Object>> impact(String entityId) {
    return db.queryForList("SELECT r.entity_id AS dependent,r.version,r.entity_type,rel.relation_type,rel.target_version,rel.target_content_hash FROM awe_release_relation rel JOIN awe_release r ON r.id=rel.release_id WHERE rel.target_entity_id=? ORDER BY r.published_at DESC",entityId);
  }
  public List<Map<String,Object>> auditTrail(String entityId) {
    return db.queryForList("SELECT event_type,actor,details::text AS details,created_at FROM awe_audit WHERE entity_id=? ORDER BY created_at DESC LIMIT 100",entityId);
  }
  public List<Map<String,Object>> validationChecks(String entityId) { return checkLog.history(entityId); }
  public List<Map<String,Object>> gaps() {
    var rows=db.queryForList("SELECT id::text,release_id::text,entity_id,source_system,external_ref,status,reason,details::text,occurrences,created_at,last_checked_at,resolved_at FROM awe_gap ORDER BY last_checked_at DESC LIMIT 200");
    rows.forEach(r->r.put("details",json.parse((String)r.get("details")))); return rows;
  }
  @Transactional
  public Map<String,Object> verifyRelease(UUID id,String actor) {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","verify:"+id);
    var release=release(id); var spec=(JsonNode)release.get("spec");
    if(!Integer.valueOf(2).equals(release.get("schema_version")))
      return Map.of("status","LEGACY_UNVERIFIED","checks",List.of(),"impact",impact((String)release.get("entity_id")),"gaps",gaps());
    var model=typed.decode((String)release.get("entity_type"),spec);
    var checks=sourceChecks(spec,model,actor); String status=checks.isEmpty()?"NOT_APPLICABLE":"VERIFIED";
    for(var check:checks) {
      boolean verified="VERIFIED".equals(check.get("status"));
      if(!verified) {
        if("VERIFIED".equals(status) || "DRIFTED".equals(check.get("status"))) status=(String)check.get("status");
        if(Set.of("DRIFTED","CONTRACT_MISMATCH").contains(check.get("status"))) db.update("INSERT INTO awe_gap(id,release_id,entity_id,source_system,external_ref,reason,details) VALUES(?,?,?,?,?,?,?::jsonb) ON CONFLICT(release_id,source_system,external_ref) DO UPDATE SET status='OPEN',reason=EXCLUDED.reason,details=EXCLUDED.details,occurrences=awe_gap.occurrences+1,last_checked_at=clock_timestamp(),resolved_at=NULL",
          UUID.randomUUID(),id,release.get("entity_id"),check.get("sourceSystem"),check.get("externalRef"),check.get("status"),json.write(check));
      } else db.update("UPDATE awe_gap SET status='RESOLVED',details=?::jsonb,last_checked_at=clock_timestamp(),resolved_at=clock_timestamp() WHERE release_id=? AND source_system=? AND external_ref=? AND status='OPEN'",json.write(check),id,check.get("sourceSystem"),check.get("externalRef"));
    }
    audit((String)release.get("entity_id"),null,"SOURCE_RECHECKED",actor,Map.of("releaseId",id.toString(),"status",status,"checks",checks));
    return Map.of("status",status,"checks",checks,"impact",impact((String)release.get("entity_id")),"gaps",gaps());
  }
  private JsonNode canonical(Map<String,Object> current) {
    String type=(String)current.get("entity_type"); var spec=(JsonNode)current.get("spec");
    validator.validate((String)current.get("domain"),(String)current.get("entity_id"),type,spec);
    return typed.canonicalSpec(type,spec);
  }
  private Map<String,Object> compatibilityReport(Map<String,Object> current,JsonNode spec,String version) {
    var next=ModelCompatibility.Version.parse(version);
    var prior=db.queryForList("SELECT entity_type,version,schema_version,spec::text AS spec FROM awe_release WHERE entity_id=?",current.get("entity_id"));
    Map<String,Object> latest=null;
    for(var row:prior) if(latest==null || ModelCompatibility.Version.parse((String)row.get("version")).compareTo(ModelCompatibility.Version.parse((String)latest.get("version")))>0) latest=row;
    var report=new LinkedHashMap<String,Object>(compatibility.compare((String)current.get("entity_type"),latest==null?null:json.parse((String)latest.get("spec")),spec));
    var versionErrors=new ArrayList<String>();
    if(latest!=null) {
      var before=ModelCompatibility.Version.parse((String)latest.get("version"));
      if(next.compareTo(before)<=0) versionErrors.add("Version must increase beyond "+latest.get("version"));
      if(!latest.get("entity_type").equals(current.get("entity_type"))) {
        var legacyFamilies=Map.of("BusinessEntity","BUSINESS_ENTITY","BusinessEvent","BUSINESS_EVENT","Dimension","DIMENSION","Pipeline","PIPELINE","DataProduct","DATA_PRODUCT");
        boolean explicitLegacyMigration=Integer.valueOf(1).equals(latest.get("schema_version")) && "ObjectType".equals(current.get("entity_type"))
            && Objects.equals(legacyFamilies.get(latest.get("entity_type")),spec.path("model").path("family").asText())
            && next.major().compareTo(before.major())>0;
        if(!explicitLegacyMigration) versionErrors.add("Entity metamodel type is immutable except explicit major-version legacy family migration");
      }
      if(Boolean.TRUE.equals(report.get("breaking")) && next.major().compareTo(before.major())<=0)
        versionErrors.add("Breaking model change requires a major version: "+report.get("reasons"));
    }
    report.put("versionAllowed",versionErrors.isEmpty()); report.put("versionErrors",versionErrors);
    return report;
  }

  private Map<String,EntityRef> checkReferences(AweDefinition model,JsonNode spec) {
    var refs=new LinkedHashMap<String,EntityRef>();
    for(var ref:typed.references(model)) addRef(refs,ref);
    for(JsonNode ref:spec.path("dependsOn")) addRef(refs,new EntityRef(text(ref,"id"),text(ref,"modelVersion")));
    if(model instanceof FunctionDefinition f) for(String ref:f.requiredAssertions()) addRef(refs,assertionRef(ref));
    if(model instanceof ActionDefinition a) for(String ref:a.postconditionAssertions()) addRef(refs,assertionRef(ref));
    for(var ref:refs.values()) {
      if(ref.id().equals(model.ref().id())) throw new IllegalArgumentException("Self dependency forbidden");
      ModelCompatibility.Version.parse(ref.modelVersion()); target(ref);
    }
    if(model instanceof SemanticModel.Dataset m) { requireTarget(m.subjectType(),"ObjectType"); m.dimensions().forEach(this::requireDimension); }
    if(model instanceof SemanticModel.Metric m) { requireTarget(m.subjectType(),"ObjectType"); requireTarget(m.sourceDataset(),"Dataset"); m.allowedDimensions().forEach(this::requireDimension); }
    if(model instanceof SemanticModel.Feature m) { requireTarget(m.subjectType(),"ObjectType"); requireTarget(m.sourceDataset(),"Dataset"); }
    if(model instanceof LinkType l) { requireTarget(l.fromType(),"ObjectType","Dataset","Metric","Feature"); requireTarget(l.toType(),"ObjectType","Dataset","Metric","Feature"); }
    if(model instanceof FunctionDefinition f) for(String ref:f.requiredAssertions()) requireTarget(assertionRef(ref),"EvidenceAssertion");
    if(model instanceof ActionDefinition a) for(String ref:a.postconditionAssertions()) requireTarget(assertionRef(ref),"EvidenceAssertion");
    return refs;
  }
  private void requireDimension(EntityRef ref) {
    requireTarget(ref,"ObjectType");
    var row=target(ref);
    if(!"DIMENSION".equals(json.parse((String)row.get("spec")).path("model").path("family").asText()))
      throw new IllegalArgumentException("Dimension reference must target an ObjectType DIMENSION: "+ref.id());
  }
  /** Evidence may scope the actual pinned implementation, never an unrelated dependency snapshot. */
  private boolean snapshotInSourceScope(Map<String,Object> subject,UUID snapshot) {
    var pending=new ArrayDeque<Map<String,Object>>(); pending.add(subject);
    var seen=new HashSet<String>();
    while(!pending.isEmpty()) {
      var row=pending.removeFirst(); String id=(String)row.get("id");
      if(!seen.add(id)) continue;
      if(seen.size()>64) throw new IllegalArgumentException("Evidence implementation scope exceeds supported depth");
      Integer count=db.queryForObject("SELECT count(*) FROM awe_source_snapshot s JOIN awe_source_binding b ON b.source_system=s.source_system AND b.external_ref=s.external_ref AND b.source_fingerprint=s.fingerprint WHERE s.id=? AND b.release_id=?",Integer.class,snapshot,UUID.fromString(id));
      if(count!=null && count>0) return true;
      if(!Integer.valueOf(2).equals(row.get("schema_version"))) continue;
      var value=typed.decode((String)row.get("entity_type"),json.parse((String)row.get("spec")));
      EntityRef implementation=null;
      if(value instanceof SemanticModel.Metric metric) implementation=metric.sourceDataset();
      if(value instanceof SemanticModel.Feature feature) implementation=feature.sourceDataset();
      if(value instanceof ContractDefinition contract) implementation=contract.subject();
      if(value instanceof FunctionDefinition function) implementation=function.ownerType();
      if(value instanceof ActionDefinition action) implementation=action.ownerType();
      if(implementation!=null) pending.add(target(implementation));
    }
    return false;
  }
  private void addRef(Map<String,EntityRef> refs,EntityRef ref) {
    var old=refs.putIfAbsent(ref.id(),ref);
    if(old!=null && !old.equals(ref)) throw new IllegalArgumentException("Multiple versions of one dependency require an explicit compatibility adapter");
  }
  private EntityRef assertionRef(String value) {
    int at=value.lastIndexOf('@');
    if(at<1 || !value.startsWith("assertion.")) throw new IllegalArgumentException("Assertion references require canonical id@major.minor.patch");
    return new EntityRef(value.substring(0,at),value.substring(at+1));
  }
  private void requireTarget(EntityRef ref,String... types) {
    var row=target(ref);
    if(!Integer.valueOf(2).equals(row.get("schema_version")) || !Arrays.asList(types).contains(row.get("entity_type")))
      throw new IllegalArgumentException("Wrong or legacy dependency family for "+ref.id()+"; expected "+Arrays.toString(types));
  }
  private Map<String,Object> target(EntityRef ref) {
    var rows=db.queryForList("SELECT id::text,entity_id,entity_type,version,content_hash,schema_version,spec::text AS spec FROM awe_release WHERE entity_id=? AND version=?",ref.id(),ref.modelVersion());
    if(rows.isEmpty()) throw new IllegalArgumentException("Unpublished pinned dependency: "+ref.id()+"@"+ref.modelVersion());
    return rows.getFirst();
  }
  private void checkDeclarations(AweDefinition model,JsonNode spec) {
    if(model instanceof ContractDefinition c) for(var obligation:c.obligations()) if(obligation.blocking()) {
      boolean supported=obligation.ruleId().equals("check.model.static") && Set.of(ContractDefinition.Enforcement.AUTHORING,ContractDefinition.Enforcement.PUBLISH).contains(obligation.at());
      supported|=obligation.ruleId().equals("check.source.consistency") && obligation.at()==ContractDefinition.Enforcement.PUBLISH;
      if(!supported) throw new IllegalArgumentException("Unsupported blocking rule/stage: "+obligation.ruleId()+" at "+obligation.at());
      var subject=target(c.subject());
      validator.validate(domainOf(c.subject()),c.subject().id(),(String)subject.get("entity_type"),json.parse((String)subject.get("spec")));
    }
    if(model instanceof EvidenceAssertion e) {
      var subject=target(e.subject());
      if(!e.modelReleaseHash().equals(subject.get("content_hash"))) throw new IllegalArgumentException("Evidence modelReleaseHash does not match pinned subject");
      if(e.checkedAt().isAfter(Instant.now().plusSeconds(60))) throw new IllegalArgumentException("Evidence checkedAt cannot be in the future");
      UUID snapshot;
      try { snapshot=UUID.fromString(e.sourceSnapshotRef()); } catch(Exception ex) { throw new IllegalArgumentException("Evidence requires a persisted source snapshot UUID"); }
      if(!snapshotInSourceScope(subject,snapshot)) throw new IllegalArgumentException("Evidence snapshot is outside the pinned subject source scope");
      // This is a reviewed declaration. Only SourceService observations can pass a live source gate.
    }
  }
  private String domainOf(EntityRef ref) { return ref.id().split("\\.")[1]; }
  private List<Map<String,Object>> sourceChecks(JsonNode spec,AweDefinition model,String actor) {
    return sourceChecks(spec,model,actor,0);
  }
  private List<Map<String,Object>> sourceChecks(JsonNode spec,AweDefinition model,String actor,int depth) {
    if(depth>32) throw new IllegalArgumentException("Source implementation scope exceeds supported depth");
    var checks=new ArrayList<Map<String,Object>>();
    if(model instanceof SemanticModel.Dataset && spec.path("bindings").isEmpty())
      throw new IllegalArgumentException("Dataset publication requires an observed authoritative source binding");
    checkBindings(spec,model,actor,true,checks);
    EntityRef source=null;
    if(model instanceof SemanticModel.Metric m) source=m.sourceDataset();
    if(model instanceof SemanticModel.Feature f) source=f.sourceDataset();
    if(model instanceof ContractDefinition c && c.obligations().stream().anyMatch(o->o.blocking() && o.ruleId().equals("check.source.consistency"))) source=c.subject();
    if(source!=null) {
      var target=target(source); var targetSpec=json.parse((String)target.get("spec"));
      var sourceModel=typed.decode((String)target.get("entity_type"),targetSpec);
      var nested=sourceChecks(targetSpec,sourceModel,actor,depth+1);
      if(nested.isEmpty()) throw new IllegalArgumentException("Source consistency subject has no checkable source binding");
      for(var check:nested) {
        check.put("direct",false);
        if(sourceModel instanceof SemanticModel.Dataset dataset && model instanceof SemanticModel && "VERIFIED".equals(check.get("status"))) {
          JsonNode binding=null;
          for(var b:targetSpec.path("bindings")) if(text(b,"sourceSystem").equals(check.get("sourceSystem")) && text(b,"externalRef").equals(check.get("externalRef"))) binding=b;
          JsonNode metadata=(JsonNode)sources.snapshot(UUID.fromString((String)check.get("snapshotId"))).get("metadata");
          var errors=physical.errorsAgainstDataset(model,dataset,Objects.requireNonNull(binding),metadata);
          if(!errors.isEmpty()) { check.put("status","CONTRACT_MISMATCH"); check.put("errors",errors); }
        }
        checkLog.append(model,spec,check,actor);
        checks.add(check);
      }
    }
    return checks;
  }
  private void checkBindings(JsonNode spec,AweDefinition model,String actor,boolean direct,List<Map<String,Object>> checks) {
    for(JsonNode binding:spec.path("bindings")) {
      var observation=sources.verify(text(binding,"sourceSystem"),text(binding,"externalRef"),text(binding,"fingerprint"),actor);
      var check=new LinkedHashMap<String,Object>();
      check.put("status",observation.status()); check.put("sourceSystem",observation.system()); check.put("externalRef",observation.externalRef());
      check.put("fingerprint",observation.fingerprint()); check.put("snapshotId",observation.snapshotId()); check.put("sourceVersion",observation.sourceVersion());
      check.put("limitations",observation.limitations()); check.put("direct",direct);
      if("VERIFIED".equals(observation.status())) {
        var errors=physical.errors(model,spec,binding,observation.metadata());
        if(!errors.isEmpty()) { check.put("status","CONTRACT_MISMATCH"); check.put("errors",errors); }
      }
      checkLog.append(model,spec,check,actor);
      checks.add(check);
    }
  }
  private void lockDraft(UUID id) {
    if(db.queryForList("SELECT id FROM awe_draft WHERE id=? FOR UPDATE",id).isEmpty()) throw new NoSuchElementException("Model not found");
  }
  private Map<String,Object> one(String sql,Object... params) {
    var rows=db.queryForList(sql,params); if(rows.isEmpty()) throw new NoSuchElementException("Model not found"); return new LinkedHashMap<>(rows.getFirst());
  }
  private String text(JsonNode n,String key) {
    var v=n.path(key); if(!v.isTextual()||v.asText().isBlank()) throw new IllegalArgumentException("Missing "+key); return v.asText();
  }
  private void audit(String entity,UUID draftId,String event,String actor,Object details) {
    db.update("INSERT INTO awe_audit(entity_id,draft_id,event_type,actor,details,created_at) VALUES(?,?,?,?,?::jsonb,clock_timestamp())",entity,draftId,event,actor,json.write(details));
  }
}
