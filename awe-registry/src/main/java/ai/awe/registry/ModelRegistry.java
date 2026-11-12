package ai.awe.registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;

/** Durable authoring and immutable release workflow. Source bindings are NOT claims of successful external validation. */
@Service
public class ModelRegistry {
  private static final Pattern SEMVER=Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
  private final JdbcTemplate db;
  private final JsonDocuments json;
  private final ModelValidation validator;
  public ModelRegistry(JdbcTemplate db,JsonDocuments json,ModelValidation validator) {
    this.db=db;this.json=json;this.validator=validator;
  }
  public List<Map<String,Object>> domains() {
    return db.queryForList("SELECT code,display_name,owner_team,created_at FROM awe_domain ORDER BY code");
  }
  @Transactional
  public Map<String,Object> createDomain(JsonNode req,String actor) {
    String code=text(req,"code"),name=text(req,"displayName"),owner=text(req,"ownerTeam");
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
    row.put("spec",json.parse((String)row.get("spec")));
    return row;
  }
  @Transactional
  public Map<String,Object> createDraft(JsonNode req,String actor) {
    String domain=text(req,"domain"),entity=text(req,"entityId"),type=text(req,"entityType");
    JsonNode spec=req.path("spec");
    if(!spec.isObject()) throw new IllegalArgumentException("spec must be object");
    if(db.queryForObject("SELECT count(*) FROM awe_domain WHERE code=?",Integer.class,domain)==0)
      throw new IllegalArgumentException("Unknown domain");
    UUID id=UUID.randomUUID();
    db.update("INSERT INTO awe_draft(id,domain,entity_id,entity_type,spec,author_actor) VALUES(?,?,?,?,?::jsonb,?)",
              id,domain,entity,type,json.canonical(spec),actor);
    audit(entity,id,"DRAFT_CREATED",actor,Map.of("domain",domain));
    return draft(id);
  }
  @Transactional
  public Map<String,Object> edit(UUID id,JsonNode req,String actor) {
    var current=draft(id);
    if(!"DRAFT".equals(current.get("state"))) throw new IllegalArgumentException("Only DRAFT can be edited");
    JsonNode spec=req.path("spec");
    if(!spec.isObject()) throw new IllegalArgumentException("spec must be object");
    int changed=db.update("UPDATE awe_draft SET spec=?::jsonb,revision=revision+1,updated_at=NOW() WHERE id=? AND state='DRAFT' AND revision=?",
       json.canonical(spec),id,current.get("revision"));
    if(changed!=1) throw new IllegalStateException("Concurrent draft update; reload");
    audit((String)current.get("entity_id"),id,"DRAFT_UPDATED",actor,Map.of());
    return draft(id);
  }
  @Transactional
  public Map<String,Object> submit(UUID id,String actor) {
    var current=draft(id);
    if(!"DRAFT".equals(current.get("state"))) throw new IllegalArgumentException("Draft not editable");
    validator.validate((String)current.get("domain"),(String)current.get("entity_id"),
                       (String)current.get("entity_type"),(JsonNode)current.get("spec"));
    int changed=db.update("UPDATE awe_draft SET state='IN_REVIEW',updated_at=NOW() WHERE id=? AND state='DRAFT'",id);
    if(changed!=1) throw new IllegalStateException("Draft changed concurrently");
    audit((String)current.get("entity_id"),id,"SUBMITTED",actor,Map.of());
    return draft(id);
  }
  @Transactional
  public Map<String,Object> approve(UUID id,String actor) {
    var current=draft(id);
    if(!"IN_REVIEW".equals(current.get("state"))) throw new IllegalArgumentException("Draft is not in review");
    if(actor.equals(current.get("author_actor"))) throw new IllegalArgumentException("Self review forbidden");
    int changed=db.update("UPDATE awe_draft SET state='APPROVED',reviewed_by=?,reviewed_at=NOW(),updated_at=NOW() WHERE id=? AND state='IN_REVIEW'",actor,id);
    if(changed!=1) throw new IllegalStateException("Draft changed concurrently");
    audit((String)current.get("entity_id"),id,"APPROVED",actor,Map.of());
    return draft(id);
  }
  @Transactional
  public Map<String,Object> publish(UUID id,JsonNode req,String actor) {
    // Explicit row lock avoids races between review and publish, and guards double publication.
    var states=db.queryForList("SELECT state FROM awe_draft WHERE id=? FOR UPDATE",id);
    if(states.isEmpty() || !"APPROVED".equals(states.get(0).get("state")))
      throw new IllegalArgumentException("Only APPROVED drafts can be published");
    var current=draft(id);
    String version=text(req,"version");
    if(!SEMVER.matcher(version).matches()) throw new IllegalArgumentException("version must be major.minor.patch");
    var spec=(JsonNode)current.get("spec");
    String entity=(String)current.get("entity_id"),type=(String)current.get("entity_type");
    validator.validate((String)current.get("domain"),entity,type,spec);
    var dependencies=dependencies(spec);
    if(dependencies.contains(entity)) throw new IllegalArgumentException("Self dependency forbidden");
    for(String dep:dependencies) {
      Integer exists=db.queryForObject("SELECT count(*) FROM awe_release WHERE entity_id=?",Integer.class,dep);
      if(exists==null || exists==0) throw new IllegalArgumentException("Unpublished dependency: "+dep);
    }
    var bindings=spec.path("bindings");
    if(type.equals("Dataset") && (!bindings.isArray() || bindings.isEmpty()))
      throw new IllegalArgumentException("Dataset publication requires source binding and fingerprint");
    UUID release=UUID.randomUUID();
    String hash=json.sha256(spec);
    db.update("INSERT INTO awe_release(id,draft_id,domain,entity_id,entity_type,version,content_hash,spec,published_by) VALUES(?,?,?,?,?,?,?,?::jsonb,?)",
      release,id,current.get("domain"),entity,type,version,hash,json.canonical(spec),actor);
    for(String dep:dependencies)
      db.update("INSERT INTO awe_release_relation(release_id,target_entity_id,relation_type) VALUES(?,?,'DEPENDS_ON')",release,dep);
    if(bindings.isArray()) for(JsonNode binding:bindings)
      db.update("INSERT INTO awe_source_binding(release_id,source_system,external_ref,source_fingerprint) VALUES(?,?,?,?)",
          release,text(binding,"sourceSystem"),text(binding,"externalRef"),text(binding,"fingerprint"));
    db.update("UPDATE awe_draft SET state='PUBLISHED',updated_at=NOW() WHERE id=?",id);
    audit(entity,id,"PUBLISHED",actor,Map.of("version",version,"contentHash",hash));
    return release(release);
  }
  public List<Map<String,Object>> releases() {
    return db.queryForList("SELECT id::text AS id,entity_id,entity_type,domain,version,content_hash,published_at FROM awe_release ORDER BY published_at DESC LIMIT 200");
  }
  public Map<String,Object> release(UUID id) {
    var r=one("SELECT id::text AS id,entity_id,entity_type,domain,version,content_hash,spec::text AS spec,published_at FROM awe_release WHERE id=?",id);
    r.put("spec",json.parse((String)r.get("spec")));
    r.put("relations",db.queryForList("SELECT target_entity_id,relation_type FROM awe_release_relation WHERE release_id=?",id));
    r.put("bindings",db.queryForList("SELECT source_system,external_ref,source_fingerprint,verification_status FROM awe_source_binding WHERE release_id=?",id));
    return r;
  }
  public List<Map<String,Object>> impact(String entityId) {
    return db.queryForList("SELECT r.entity_id AS dependent,r.version,r.entity_type,rel.relation_type FROM awe_release_relation rel JOIN awe_release r ON r.id=rel.release_id WHERE rel.target_entity_id=? ORDER BY r.published_at DESC",entityId);
  }
  public List<Map<String,Object>> auditTrail(String entityId) {
    return db.queryForList("SELECT event_type,actor,details::text AS details,created_at FROM awe_audit WHERE entity_id=? ORDER BY created_at DESC LIMIT 100",entityId);
  }
  private List<String> dependencies(JsonNode spec) {
    var refs=new LinkedHashSet<String>();
    var dependsOn=spec.path("dependsOn");
    if(dependsOn.isArray()) dependsOn.forEach(n->refs.add(n.asText()));
    for(String field:List.of("datasetRef","subjectRef")) if(spec.path(field).isTextual()) refs.add(spec.path(field).asText());
    return List.copyOf(refs);
  }
  private Map<String,Object> one(String sql,Object... params) {
    var rows=db.queryForList(sql,params);
    if(rows.isEmpty()) throw new NoSuchElementException("Model not found");
    return new LinkedHashMap<>(rows.get(0));
  }
  private String text(JsonNode n,String key) {
    var value=n.path(key);
    if(!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing "+key);
    return value.asText();
  }
  private void audit(String entity,UUID draftId,String event,String actor,Object details) {
    db.update("INSERT INTO awe_audit(entity_id,draft_id,event_type,actor,details) VALUES(?,?,?, ?,?::jsonb)",
      entity,draftId,event,actor,json.write(details));
  }
}