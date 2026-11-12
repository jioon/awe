package ai.awe.registry.source;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable observations and append-only reconciliation checks; never a business-truth
 * certification.
 */
@Service
public class SourceService {
    public record Observation(
            String status,
            String system,
            String externalRef,
            String sourceVersion,
            String fingerprint,
            String snapshotId,
            JsonNode metadata,
            List<String> limitations) {
        public Observation {
            limitations = List.copyOf(limitations);
        }
    }

    private final Map<String, SourceAdapter> adapters;
    private final JdbcTemplate db;
    private final JsonDocuments json;

    public SourceService(List<SourceAdapter> adapters, JdbcTemplate db, JsonDocuments json) {
        var bySystem = new TreeMap<String, SourceAdapter>();
        for (SourceAdapter adapter : adapters) {
            if (bySystem.put(adapter.system(), adapter) != null)
                throw new IllegalArgumentException("Duplicate source adapter");
        }
        this.adapters = Map.copyOf(bySystem);
        this.db = db;
        this.json = json;
    }

    public List<Map<String, Object>> configuration() {
        return adapters.values().stream()
                .sorted(Comparator.comparing(SourceAdapter::system))
                .map(
                        a ->
                                Map.<String, Object>of(
                                        "system",
                                        a.system(),
                                        "status",
                                        a.configured() ? "CONFIGURED" : "NOT_CONFIGURED",
                                        "capability",
                                        "METADATA_OBSERVATION",
                                        "limitations",
                                        List.of(
                                                "Configuration does not establish connectivity or row-level correctness")))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Observation observe(String system, String externalRef, String actor) {
        return check(system, externalRef, "", actor);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Observation verify(
            String system, String externalRef, String expectedFingerprint, String actor) {
        if (expectedFingerprint == null || !expectedFingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException(
                    "Source verification requires a server-observed SHA-256 fingerprint");
        return check(system, externalRef, expectedFingerprint, actor);
    }

    private Observation check(String system, String externalRef, String expected, String actor) {
        if (externalRef == null
                || externalRef.isBlank()
                || externalRef.length() > 500
                || externalRef.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(
                    "Valid externalRef required (maximum 500 characters)");
        SourceAdapter adapter = adapters.get(system);
        if (adapter == null) throw new IllegalArgumentException("Unsupported source system");
        var fetched = adapter.fetch(externalRef);
        String fingerprint = "", snapshot = "", status = fetched.status();
        if ("IMPORTED".equals(status)) {
            fingerprint = json.sha256(fetched.metadata());
            snapshot = UUID.randomUUID().toString();
            db.update(
                    "INSERT INTO awe_source_snapshot(id,source_system,external_ref,source_version,fingerprint,metadata,observed_by) VALUES(?::uuid,?,?,?,?,?::jsonb,?)",
                    snapshot,
                    system,
                    externalRef,
                    fetched.sourceVersion(),
                    fingerprint,
                    json.canonical(fetched.metadata()),
                    actor);
            if (!expected.isEmpty()) status = expected.equals(fingerprint) ? "VERIFIED" : "DRIFTED";
        }
        db.update(
                "INSERT INTO awe_source_check(source_system,external_ref,expected_fingerprint,observed_fingerprint,snapshot_id,status,checked_by,limitations) VALUES(?,?,?,?,?::uuid,?,?,?::jsonb)",
                system,
                externalRef,
                expected.isEmpty() ? null : expected,
                fingerprint.isEmpty() ? null : fingerprint,
                snapshot.isEmpty() ? null : snapshot,
                status,
                actor,
                json.write(fetched.limitations()));
        return new Observation(
                status,
                system,
                externalRef,
                fetched.sourceVersion(),
                fingerprint,
                snapshot,
                fetched.metadata(),
                fetched.limitations());
    }

    public List<Map<String, Object>> snapshots(String system, String externalRef) {
        if (!adapters.containsKey(system))
            throw new IllegalArgumentException("Unsupported source system");
        return db.queryForList(
                "SELECT id::text AS id,source_system,external_ref,source_version,fingerprint,observed_by,observed_at FROM awe_source_snapshot WHERE source_system=? AND external_ref=? ORDER BY observed_at DESC LIMIT 100",
                system,
                externalRef);
    }

    public Map<String, Object> snapshot(UUID id) {
        var rows =
                db.queryForList(
                        "SELECT id::text AS id,source_system,external_ref,source_version,fingerprint,metadata::text AS metadata,observed_by,observed_at FROM awe_source_snapshot WHERE id=?",
                        id);
        if (rows.isEmpty()) throw new NoSuchElementException("Source snapshot not found");
        var result = new LinkedHashMap<>(rows.getFirst());
        result.put("metadata", json.parse((String) result.get("metadata")));
        return result;
    }

    public List<Map<String, Object>> checks(String system, String externalRef) {
        if (!adapters.containsKey(system))
            throw new IllegalArgumentException("Unsupported source system");
        return db.queryForList(
                "SELECT id,source_system,external_ref,expected_fingerprint,observed_fingerprint,snapshot_id::text AS snapshot_id,status,checked_by,checked_at,limitations::text AS limitations FROM awe_source_check WHERE source_system=? AND external_ref=? ORDER BY checked_at DESC LIMIT 100",
                system,
                externalRef);
    }
}
