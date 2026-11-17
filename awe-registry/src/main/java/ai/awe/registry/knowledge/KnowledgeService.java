package ai.awe.registry.knowledge;

import ai.awe.registry.authoring.ModelRegistry;
import ai.awe.registry.consumption.ConsumerAccess;
import ai.awe.registry.consumption.DataTaskOperations;
import ai.awe.registry.model.ModelCompatibility;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 正式发布与受审术语提供候选；搜索分数永远不认证业务等价或企业范围不存在。 */
@Service
public class KnowledgeService {
    private final JdbcTemplate db;
    private final JsonDocuments json;
    private final ModelRegistry registry;
    private final DataTaskOperations operations;

    public KnowledgeService(
            JdbcTemplate db,
            JsonDocuments json,
            ModelRegistry registry,
            DataTaskOperations operations) {
        this.db = db;
        this.json = json;
        this.registry = registry;
        this.operations = operations;
    }

    @Transactional
    public Map<String, Object> proposeCoverage(JsonNode request, String actor) {
        KnowledgeJson.fields(
                request,
                "domain",
                "version",
                "ownerTeam",
                "validUntil",
                "complete",
                "operations",
                "assetReleases",
                "aliases",
                "sourceAssets",
                "limitations");
        String domain = KnowledgeJson.text(request, "domain", 80),
                version = KnowledgeJson.text(request, "version", 50);
        var parsed = ModelCompatibility.Version.parse(version);
        var domains = db.queryForList("SELECT owner_team FROM awe_domain WHERE code=?", domain);
        if (domains.isEmpty()
                || !KnowledgeJson.text(request, "ownerTeam", 200)
                        .equals(domains.getFirst().get("owner_team")))
            throw new IllegalArgumentException(
                    "Coverage must name the registered domain owner team");
        Instant until = KnowledgeJson.instant(request, "validUntil");
        if (!until.isAfter(Instant.now()) || until.isAfter(Instant.now().plus(Duration.ofDays(30))))
            throw new IllegalArgumentException("Coverage validity must be in the next 30 days");
        if (!request.path("complete").isBoolean())
            throw new IllegalArgumentException("complete must be a reviewed boolean declaration");
        var intents = new HashSet<String>();
        for (var operation : KnowledgeJson.array(request, "operations", 2)) {
            if (!operation.isTextual()
                    || !Set.of("EXTRACT", "QUERY_METRIC").contains(operation.asText())
                    || !intents.add(operation.asText()))
                throw new IllegalArgumentException(
                        "Coverage operations must be unique supported intents");
        }
        if (intents.isEmpty())
            throw new IllegalArgumentException("Coverage requires supported operations");
        var assets = new HashMap<String, JsonNode>();
        for (var asset : KnowledgeJson.array(request, "assetReleases", 1000)) {
            KnowledgeJson.fields(asset, "releaseId", "contentHash");
            var release = registry.release(KnowledgeJson.uuid(asset, "releaseId"));
            if (!domain.equals(release.get("domain"))
                    || !"FunctionDefinition".equals(release.get("entity_type"))
                    || !KnowledgeJson.hash(asset, "contentHash")
                            .equals(release.get("content_hash")))
                throw new IllegalArgumentException(
                        "Coverage assets must pin this domain's published Function releases");
            if (assets.put((String) release.get("id"), asset) != null)
                throw new IllegalArgumentException("Duplicate coverage asset");
        }
        if (assets.isEmpty())
            throw new IllegalArgumentException(
                    "A coverage declaration requires actual published assets");
        var aliases = new HashSet<String>();
        for (var alias : KnowledgeJson.array(request, "aliases", 1000)) {
            KnowledgeJson.fields(alias, "term", "functionReleaseId");
            String term = normalize(KnowledgeJson.text(alias, "term", 200));
            String ref = KnowledgeJson.uuid(alias, "functionReleaseId").toString();
            if (!assets.containsKey(ref) || !aliases.add(term + "\u0000" + ref))
                throw new IllegalArgumentException(
                        "Alias must pin a unique declared published asset");
        }
        String seed = String.join(",", Collections.nCopies(assets.size(), "?::uuid"));
        var sourceBindings =
                db.queryForList(
                        "WITH RECURSIVE scope(id) AS (SELECT id FROM awe_release WHERE id IN ("
                                + seed
                                + ") UNION SELECT rel.target_release_id FROM awe_release_relation rel JOIN scope s ON rel.release_id=s.id WHERE rel.target_release_id IS NOT NULL) SELECT DISTINCT b.source_system,b.external_ref,b.source_fingerprint FROM scope s JOIN awe_source_binding b ON b.release_id=s.id",
                        assets.keySet().toArray());
        var actualSources = new HashMap<String, String>();
        for (var binding : sourceBindings) {
            String key = binding.get("source_system") + "\u0000" + binding.get("external_ref");
            String previous =
                    actualSources.putIfAbsent(key, (String) binding.get("source_fingerprint"));
            if (previous != null && !previous.equals(binding.get("source_fingerprint")))
                throw new IllegalArgumentException(
                        "Coverage mixes incompatible versions of one physical source");
        }
        var declaredSources = new HashSet<String>();
        for (var source : KnowledgeJson.array(request, "sourceAssets", 1000)) {
            KnowledgeJson.fields(source, "sourceSystem", "externalRef", "snapshotId");
            String system = KnowledgeJson.text(source, "sourceSystem", 100),
                    external = KnowledgeJson.text(source, "externalRef", 500);
            String key = system + "\u0000" + external;
            if (!declaredSources.add(key) || !actualSources.containsKey(key))
                throw new IllegalArgumentException(
                        "Coverage source must belong to the pinned asset implementation scope");
            var found =
                    db.queryForList(
                            "SELECT id FROM awe_source_snapshot WHERE id=? AND source_system=? AND external_ref=? AND fingerprint=?",
                            KnowledgeJson.uuid(source, "snapshotId"),
                            system,
                            external,
                            actualSources.get(key));
            if (found.isEmpty())
                throw new IllegalArgumentException(
                        "Coverage source evidence must reference an actual matching scoped observation");
        }
        for (var limitation : KnowledgeJson.array(request, "limitations", 32))
            if (!limitation.isTextual() || limitation.asText().length() > 1000)
                throw new IllegalArgumentException("Invalid coverage limitation");
        if (request.path("complete").asBoolean() && request.path("sourceAssets").isEmpty())
            throw new IllegalArgumentException(
                    "A complete scope declaration requires source observations and explicit limitations");
        if (request.path("complete").asBoolean() && !declaredSources.equals(actualSources.keySet()))
            throw new IllegalArgumentException(
                    "Complete coverage cannot omit physical sources of its pinned asset inventory");
        db.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "coverage:" + domain);
        for (var prior :
                db.queryForList(
                        "SELECT version FROM awe_knowledge_coverage WHERE domain=?", domain))
            if (parsed.compareTo(ModelCompatibility.Version.parse((String) prior.get("version")))
                    <= 0) throw new IllegalArgumentException("Coverage version must increase");
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO awe_knowledge_coverage(id,domain,version,document,content_hash,proposed_by,valid_until) VALUES(?,?,?,?::jsonb,?,?,?)",
                id,
                domain,
                version,
                json.canonical(request),
                json.sha256(request),
                actor,
                java.sql.Timestamp.from(until));
        return coverage(id);
    }

    @Transactional
    public Map<String, Object> approveCoverage(UUID id, String actor) {
        var coverage = coverage(id);
        if (actor.equals(coverage.get("proposed_by")))
            throw new IllegalArgumentException("Coverage needs an independent reviewer");
        if (!((java.sql.Timestamp) coverage.get("valid_until")).toInstant().isAfter(Instant.now()))
            throw new IllegalArgumentException("Coverage proposal expired");
        db.update(
                "INSERT INTO awe_knowledge_coverage_approval(coverage_id,reviewed_by) VALUES(?,?) ON CONFLICT(coverage_id) DO NOTHING",
                id,
                actor);
        return coverage(id);
    }

    public List<Map<String, Object>> coverage(ConsumerAccess.Grant grant) {
        var allowed = domains(grant);
        var result = new ArrayList<Map<String, Object>>();
        for (var row : currentCoverage()) {
            if (!allowed.contains(row.get("domain"))) continue;
            var document = json.parse((String) row.get("document"));
            var visible = new ArrayList<JsonNode>();
            for (var asset : document.path("assetReleases")) {
                var release = registry.release(UUID.fromString(asset.path("releaseId").asText()));
                if (grant.functionIds().contains(release.get("entity_id"))) visible.add(asset);
            }
            result.add(
                    Map.of(
                            "id",
                            row.get("id"),
                            "domain",
                            row.get("domain"),
                            "version",
                            row.get("version"),
                            "contentHash",
                            row.get("content_hash"),
                            "ownerTeam",
                            document.path("ownerTeam").asText(),
                            "validUntil",
                            row.get("valid_until"),
                            "completeWithinDeclaredScope",
                            document.path("complete").asBoolean(),
                            "operations",
                            document.path("operations"),
                            "visibleAssets",
                            visible,
                            "limitations",
                            document.path("limitations")));
        }
        return result;
    }

    public Map<String, Object> resolve(JsonNode request, ConsumerAccess.Grant grant) {
        return resolveRequest(request, grant, false);
    }

    private Map<String, Object> resolveRequest(
            JsonNode request, ConsumerAccess.Grant grant, boolean assessment) {
        KnowledgeJson.fields(
                request, "text", "domain", "intent", "functionId", "releaseId", "contentHash");
        String intent =
                KnowledgeJson.enumeration(request, "intent", Set.of("EXTRACT", "QUERY_METRIC"));
        String term = KnowledgeJson.optional(request, "text", 4000),
                function = KnowledgeJson.optional(request, "functionId", 240),
                domain = KnowledgeJson.optional(request, "domain", 80);
        if (term.isEmpty() && function.isEmpty() && !request.has("releaseId"))
            throw new IllegalArgumentException("A data term or exact reference is required");
        if (!domain.isEmpty() && !domains(grant).contains(domain))
            throw new NoSuchElementException("Domain is not in authorized discovery scope");
        if (!function.isEmpty() && !grant.functionIds().contains(function))
            throw new NoSuchElementException("Capability is not in authorized discovery scope");
        String release =
                request.has("releaseId") ? KnowledgeJson.uuid(request, "releaseId").toString() : "";
        String hash = request.has("contentHash") ? KnowledgeJson.hash(request, "contentHash") : "";
        if (release.isEmpty() != hash.isEmpty())
            throw new IllegalArgumentException(
                    "Exact release resolution requires both releaseId and contentHash");
        var capabilities = operations.capabilities(grant);
        var coverages = currentCoverage();
        var reviewedAliases = new HashMap<String, Set<String>>();
        for (var coverage : coverages) {
            var doc = json.parse((String) coverage.get("document"));
            if (!domain.isEmpty() && !domain.equals(coverage.get("domain"))) continue;
            for (var alias : doc.path("aliases"))
                reviewedAliases
                        .computeIfAbsent(
                                alias.path("functionReleaseId").asText(), key -> new HashSet<>())
                        .add(normalize(alias.path("term").asText()));
        }
        var scored = new ArrayList<Map<String, Object>>();
        var index = new ArrayList<Map<String, Object>>();
        String normalized = normalize(term);
        for (var cap : capabilities) {
            String id = (String) cap.get("functionId"), versionId = (String) cap.get("releaseId");
            if (id == null
                    || versionId == null
                    || !grant.functionIds().contains(id)
                    || !domain.isEmpty() && !id.startsWith("function." + domain + ".")) continue;
            if (!intent.equals(capIntent(cap))) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("functionId", id);
            entry.put("releaseId", versionId);
            entry.put("contentHash", cap.get("contentHash"));
            entry.put("name", cap.getOrDefault("name", id));
            var published = registry.release(UUID.fromString(versionId));
            entry.put("definition", ((JsonNode) published.get("spec")).path("definition").asText());
            entry.put(
                    "aliases",
                    reviewedAliases.getOrDefault(versionId, Set.of()).stream().sorted().toList());
            index.add(entry);
            if (!function.isEmpty() && !function.equals(id)
                    || !release.isEmpty() && !release.equals(versionId)) continue;
            int score = 0;
            String basis = "";
            if (!release.isEmpty()) {
                if (!hash.equals(cap.get("contentHash")))
                    throw new IllegalArgumentException("Pinned discovery contentHash mismatch");
                score = 1000;
                basis = "EXACT_RELEASE";
            } else if (!function.isEmpty() || normalized.equals(normalize(id))) {
                score = 900;
                basis = "EXACT_ID";
            } else if (reviewedAliases.getOrDefault(versionId, Set.of()).contains(normalized)) {
                score = 850;
                basis = "REVIEWED_ALIAS";
            } else if (normalized.equals(normalize((String) entry.get("name")))) {
                score = 800;
                basis = "EXACT_PUBLISHED_NAME";
            } else if (!normalized.isEmpty()) {
                String haystack =
                        normalize(id + " " + entry.get("name") + " " + entry.get("definition"));
                if (normalized.contains(normalize((String) entry.get("name")))) score += 50;
                for (String alias : reviewedAliases.getOrDefault(versionId, Set.of()))
                    if (normalized.contains(alias)) score += 60;
                for (String token : normalized.split("[\\s\\p{Punct}]+"))
                    if (!token.isEmpty() && haystack.contains(token)) score += 10;
                basis = "LEXICAL_CANDIDATE_ONLY";
            }
            if (score == 0) continue;
            var candidate = new LinkedHashMap<String, Object>(entry);
            candidate.put("matchBasis", basis);
            candidate.put("score", score);
            candidate.put("supply", cap.getOrDefault("supply", "UNKNOWN"));
            candidate.put("readiness", cap.getOrDefault("readiness", "UNKNOWN"));
            if (cap.get("metric") != null) candidate.put("metric", cap.get("metric"));
            scored.add(candidate);
        }
        scored.sort(
                Comparator.<Map<String, Object>>comparingInt(
                                row -> ((Number) row.get("score")).intValue())
                        .reversed()
                        .thenComparing(row -> (String) row.get("releaseId")));
        if (scored.size() > 20) scored = new ArrayList<>(scored.subList(0, 20));
        long exact =
                scored.stream().filter(c -> ((Number) c.get("score")).intValue() >= 800).count();
        String resolution = exact == 1 ? "RESOLVED" : scored.isEmpty() ? "UNRESOLVED" : "AMBIGUOUS";
        // 单个模糊候选仍须确认；候选分数不把模糊匹配升级为业务等价。
        if (exact == 0 && scored.size() == 1) resolution = "UNRESOLVED";
        var result = new LinkedHashMap<String, Object>();
        result.put("resolution", resolution);
        result.put("candidates", scored);
        result.put("needsClarification", !"RESOLVED".equals(resolution));
        result.put(
                "supply",
                "RESOLVED".equals(resolution)
                        ? scored.stream()
                                .filter(c -> ((Number) c.get("score")).intValue() >= 800)
                                .findFirst()
                                .orElseThrow()
                                .get("supply")
                        : "UNKNOWN");
        result.put("readiness", "UNKNOWN");
        result.put(
                "indexRoutes",
                List.of("EXACT_RELEASE_OR_ID", "REVIEWED_ALIAS", "PUBLISHED_TEXT_CANDIDATES"));
        result.put("coverage", coverage(grant));
        if (assessment) {
            result.put("authorization", "ALLOWED_FOR_VISIBLE_CANDIDATES");
            result.put("assessmentScope", "REGISTERED_AUTHORIZED_CAPABILITIES");
            result.put("executionPermission", false);
            // 只有显式允许的规范 Function ID、已审核完整注册命名空间和实际目录缺失才产生 MISSING。
            // 该结论不包括任意加工的不可推导性，更不意味着企业中没有其他数据。
            if (scored.isEmpty()
                    && !function.isEmpty()
                    && db.queryForObject(
                                    "SELECT count(*) FROM awe_release WHERE entity_id=? AND entity_type='FunctionDefinition'",
                                    Long.class,
                                    function)
                            == 0) {
                String namespace = function.split("\\.")[1];
                boolean covered =
                        coverages.stream()
                                .anyMatch(
                                        c -> {
                                            JsonNode d = json.parse((String) c.get("document"));
                                            return namespace.equals(c.get("domain"))
                                                    && d.path("complete").asBoolean()
                                                    && java.util.stream.StreamSupport.stream(
                                                                    d.path("operations")
                                                                            .spliterator(),
                                                                    false)
                                                            .anyMatch(
                                                                    i -> intent.equals(i.asText()));
                                        });
                if (covered) {
                    result.put("supply", "MISSING");
                    result.put("assessmentScope", "REVIEWED_REGISTERED_FUNCTION_NAMESPACE");
                    result.put(
                            "absenceEvidence",
                            Map.of(
                                    "functionId",
                                    function,
                                    "registeredReleaseCount",
                                    0,
                                    "scope",
                                    "Published Function registry only; enterprise data and unregistered derivations remain UNKNOWN"));
                }
            }
        }
        result.put(
                "limitations",
                List.of(
                        "Lexical misses do not prove missing enterprise knowledge or data",
                        "Coverage completeness is limited to a separately reviewed inventory and validity period",
                        "No result readiness or numeric correctness is inferred from resolution"));
        String indexHash =
                json.sha256(
                        json.parse(
                                json.write(
                                        Map.of(
                                                "authorizedIndex",
                                                index,
                                                "coverage",
                                                result.get("coverage")))));
        result.put("indexHash", indexHash);
        result.put("policyHash", grant.policyHash());
        result.put(
                "stages",
                List.of(
                        Map.of(
                                "stage",
                                "DISCOVERY",
                                "status",
                                "AUTHORIZED_INDEX_READ",
                                "candidateCount",
                                scored.size()),
                        Map.of(
                                "stage",
                                "RESOLUTION",
                                "status",
                                resolution,
                                "basis",
                                "PUBLISHED_REFERENCE_AND_REVIEWED_ALIAS")));
        UUID id = UUID.randomUUID();
        result.put("id", id.toString());
        db.update(
                "INSERT INTO awe_resolution_observation(id,principal,policy_hash,request_doc,index_hash,report) VALUES(?,?,?,?::jsonb,?,?::jsonb)",
                id,
                grant.principal(),
                grant.policyHash(),
                json.canonical(request),
                indexHash,
                json.write(result));
        return result;
    }

    public Map<String, Object> assess(JsonNode request, ConsumerAccess.Grant grant) {
        return resolveRequest(request, grant, true);
    }

    public Map<String, Object> observation(UUID id, ConsumerAccess.Grant grant) {
        var rows =
                db.queryForList(
                        "SELECT request_doc::text,report::text,policy_hash FROM awe_resolution_observation WHERE id=? AND principal=?",
                        id,
                        grant.principal());
        if (rows.isEmpty()) throw new NoSuchElementException("Resolution observation not found");
        if (!grant.policyHash().equals(rows.getFirst().get("policy_hash")))
            throw new NoSuchElementException(
                    "Resolution observation is outside the current data policy");
        JsonNode original = json.parse((String) rows.getFirst().get("request_doc"));
        if (original.path("protocolVersion").asInt() == 3)
            return operations.requirementAssessment(id, grant);
        if (original.has("domain") && !domains(grant).contains(original.path("domain").asText()))
            throw new NoSuchElementException(
                    "Resolution observation is outside current discovery scope");
        JsonNode report = json.parse((String) rows.getFirst().get("report"));
        for (var candidate : report.path("candidates"))
            if (!grant.functionIds().contains(candidate.path("functionId").asText()))
                throw new NoSuchElementException(
                        "Resolution observation is outside current discovery scope");
        for (var coverage : report.path("coverage")) {
            if (!domains(grant).contains(coverage.path("domain").asText()))
                throw new NoSuchElementException(
                        "Resolution observation is outside current coverage scope");
            for (var asset : coverage.path("visibleAssets")) {
                var release = registry.release(UUID.fromString(asset.path("releaseId").asText()));
                if (!grant.functionIds().contains(release.get("entity_id")))
                    throw new NoSuchElementException(
                            "Resolution observation contains a revoked asset reference");
            }
        }
        var result = new LinkedHashMap<String, Object>();
        report.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
        result.put("request", json.parse((String) rows.getFirst().get("request_doc")));
        result.put("principal", grant.principal());
        return result;
    }

    private String capIntent(Map<String, Object> cap) {
        if (cap.containsKey("intent")) return String.valueOf(cap.get("intent"));
        return cap.containsKey("metric") ? "QUERY_METRIC" : "EXTRACT";
    }

    private Set<String> domains(ConsumerAccess.Grant grant) {
        var result = new HashSet<String>();
        for (String id : grant.functionIds()) {
            String[] p = id.split("\\.");
            if (p.length >= 3) result.add(p[1]);
        }
        return result;
    }

    private List<Map<String, Object>> currentCoverage() {
        return db.queryForList(
                "SELECT DISTINCT ON(c.domain) c.id::text,c.domain,c.version,c.document::text,c.content_hash,c.valid_until,a.reviewed_by FROM awe_knowledge_coverage c JOIN awe_knowledge_coverage_approval a ON a.coverage_id=c.id WHERE c.valid_until>clock_timestamp() ORDER BY c.domain,c.created_at DESC");
    }

    private Map<String, Object> coverage(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT c.id::text,c.domain,c.version,c.document::text,c.content_hash,c.proposed_by,c.valid_until,c.created_at,a.reviewed_by FROM awe_knowledge_coverage c LEFT JOIN awe_knowledge_coverage_approval a ON a.coverage_id=c.id WHERE c.id=?",
                        id);
        if (rows.isEmpty()) throw new NoSuchElementException("Coverage declaration not found");
        var result = new LinkedHashMap<String, Object>(rows.getFirst());
        result.put("document", json.parse((String) result.get("document")));
        return result;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
}
