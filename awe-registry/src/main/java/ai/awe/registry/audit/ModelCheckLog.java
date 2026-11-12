package ai.awe.registry.audit;

import ai.awe.kernel.model.AweDefinition;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retains failed candidate/source contract checks even when publication is rolled back. */
@Service
public class ModelCheckLog {
    private final JdbcTemplate db;
    private final JsonDocuments json;

    public ModelCheckLog(JdbcTemplate db, JsonDocuments json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(
            AweDefinition model, JsonNode spec, Map<String, Object> check, String actor) {
        db.update(
                "INSERT INTO awe_model_check(entity_id,model_version,candidate_hash,status,details,checked_by) VALUES(?,?,?,?,?::jsonb,?)",
                model.ref().id(),
                model.ref().modelVersion(),
                json.sha256(spec),
                check.get("status"),
                json.write(check),
                actor);
    }

    public List<Map<String, Object>> history(String entityId) {
        var rows =
                db.queryForList(
                        "SELECT id,entity_id,model_version,candidate_hash,status,details::text,checked_by,checked_at FROM awe_model_check WHERE entity_id=? ORDER BY checked_at DESC LIMIT 100",
                        entityId);
        rows.forEach(r -> r.put("details", json.parse((String) r.get("details"))));
        return rows;
    }
}
