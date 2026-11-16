package ai.awe.registry.production;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 变更影响保守覆盖历史版本和共享物理资产；超预算失败，不能截断后发放准入。 */
@Component
public final class ProductionImpactGraph {
    public static final int MAX_RELEASES = 512;
    private final JdbcTemplate db;
    private final JsonDocuments json;

    public ProductionImpactGraph(JdbcTemplate db, JsonDocuments json) {
        this.db = db;
        this.json = json;
    }

    public record SourceKey(String system, String externalRef) {
        public SourceKey {
            if (system == null
                    || !system.matches("[a-z][a-z0-9_-]*")
                    || externalRef == null
                    || externalRef.isBlank()
                    || externalRef.length() > 500
                    || externalRef.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid physical source key");
        }
    }

    public record Snapshot(
            List<Map<String, Object>> releases,
            List<Map<String, Object>> sharedAssets,
            String hash) {
        public Snapshot {
            releases = List.copyOf(releases);
            sharedAssets = List.copyOf(sharedAssets);
        }
    }

    public Snapshot snapshot(
            Set<String> changedEntities, Set<SourceKey> changedAssets, Set<UUID> excluded) {
        if (changedEntities.isEmpty() || changedEntities.size() > 64 || changedAssets.size() > 64)
            throw new IllegalArgumentException(
                    "Production change scope must contain bounded explicit target entities");
        var known = new TreeMap<String, Map<String, Object>>();
        var frontier = new TreeSet<>(changedEntities);
        var expanded = new HashSet<String>();
        var physical = new ArrayList<Map<String, Object>>();
        for (var asset :
                changedAssets.stream()
                        .sorted(
                                Comparator.comparing(SourceKey::system)
                                        .thenComparing(SourceKey::externalRef))
                        .toList()) {
            var rows =
                    db.queryForList(
                            "SELECT r.id::text,r.entity_id,r.version,r.content_hash,b.source_fingerprint FROM awe_source_binding b JOIN awe_release r ON r.id=b.release_id WHERE b.source_system=? AND b.external_ref=? ORDER BY r.entity_id,r.version,r.id LIMIT ?",
                            asset.system(),
                            asset.externalRef(),
                            MAX_RELEASES + 1);
            var runtime =
                    db.queryForList(
                            "SELECT d.id::text,d.entity_id,d.version,d.content_hash,r.binding_doc->>'fingerprint' AS source_fingerprint,r.id::text AS realization_id,r.consumption_id::text,r.artifact_hash FROM awe_dataset_realization r JOIN awe_release d ON d.id=r.dataset_release_id WHERE r.binding_doc->>'sourceSystem'=? AND r.binding_doc->>'externalRef'=? ORDER BY d.entity_id,d.version,d.id,r.id LIMIT ?",
                            asset.system(),
                            asset.externalRef(),
                            MAX_RELEASES + 1);
            if (runtime.size() > MAX_RELEASES) throw bound();
            rows = new ArrayList<>(rows);
            rows.addAll(runtime);
            if (rows.size() > MAX_RELEASES) throw bound();
            for (var row : rows) {
                UUID id = UUID.fromString((String) row.get("id"));
                var footprint = new LinkedHashMap<String, Object>();
                footprint.put("sourceSystem", asset.system());
                footprint.put("externalRef", asset.externalRef());
                footprint.put("releaseId", row.get("id"));
                footprint.put("contentHash", row.get("content_hash"));
                footprint.put("fingerprint", row.get("source_fingerprint"));
                for (String key : List.of("realization_id", "consumption_id", "artifact_hash"))
                    if (row.get(key) != null) footprint.put(key, row.get(key));
                physical.add(footprint);
                if (!excluded.contains(id)) {
                    known.put((String) row.get("id"), member(row, "SHARED_PHYSICAL_ASSET"));
                    frontier.add((String) row.get("entity_id"));
                }
            }
        }
        while (!frontier.isEmpty()) {
            var current = new TreeSet<>(frontier);
            frontier.clear();
            current.removeAll(expanded);
            if (current.isEmpty()) break;
            expanded.addAll(current);
            if (expanded.size() > MAX_RELEASES) throw bound();
            String placeholders = String.join(",", Collections.nCopies(current.size(), "?"));
            var arguments = new ArrayList<Object>(current);
            arguments.add(MAX_RELEASES + 1);
            var rows =
                    db.queryForList(
                            "SELECT DISTINCT r.id::text,r.entity_id,r.version,r.content_hash FROM awe_release_relation d JOIN awe_release r ON r.id=d.release_id WHERE d.target_entity_id IN ("
                                    + placeholders
                                    + ") ORDER BY r.entity_id,r.version,r.id::text LIMIT ?",
                            arguments.toArray());
            if (rows.size() > MAX_RELEASES) throw bound();
            for (var row : rows) {
                String id = (String) row.get("id");
                if (excluded.contains(UUID.fromString(id))) continue;
                if (known.putIfAbsent(id, member(row, "TRANSITIVE_DEPENDENT")) == null)
                    frontier.add((String) row.get("entity_id"));
            }
            if (known.size() > MAX_RELEASES) throw bound();
        }
        var releases = List.copyOf(known.values());
        physical.sort(Comparator.comparing(m -> json.canonical(json.parse(json.write(m)))));
        JsonNode document =
                json.parse(json.write(Map.of("releases", releases, "sharedAssets", physical)));
        return new Snapshot(releases, physical, json.sha256(document));
    }

    private Map<String, Object> member(Map<String, Object> row, String reason) {
        return Map.of(
                "releaseId",
                row.get("id"),
                "entityId",
                row.get("entity_id"),
                "modelVersion",
                row.get("version"),
                "contentHash",
                row.get("content_hash"),
                "reason",
                reason);
    }

    private IllegalStateException bound() {
        return new IllegalStateException(
                "Production impact exceeds the supported complete-closure budget");
    }
}
