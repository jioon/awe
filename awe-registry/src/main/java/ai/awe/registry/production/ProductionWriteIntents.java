package ai.awe.registry.production;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 不同 Pipeline 对共享目标使用同一授权代次；时间戳和回调到达顺序不能决定物理所有权。 */
@Component
public final class ProductionWriteIntents {
    private final JdbcTemplate db;
    private final JsonDocuments json;

    public ProductionWriteIntents(JdbcTemplate db, JsonDocuments json) {
        this.db = db;
        this.json = json;
    }

    public void lock(String environment, Collection<String> targets) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Physical write intent requires a transaction");
        // 所有多目标请求按同一顺序加锁，避免交叉目标的死锁；散列碰撞只会保守串行。
        for (String target : new TreeSet<>(targets))
            db.queryForList(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                    json.write(List.of(environment, target)));
    }

    public JsonNode append(String environment, Collection<String> targets, UUID consumption) {
        lock(environment, targets);
        var result = json.object("{}").putArray("intents");
        for (String target : new TreeSet<>(targets)) {
            Long id =
                    db.queryForObject(
                            "INSERT INTO awe_production_target_intent(environment,target_key,consumption_id) VALUES(?,?,?) RETURNING id",
                            Long.class,
                            environment,
                            target,
                            consumption);
            result.addObject()
                    .put("targetKey", target)
                    .put("intentId", Objects.requireNonNull(id).toString());
        }
        return result;
    }

    public boolean current(
            String environment, UUID consumption, JsonNode vector, Collection<String> targets) {
        Map<String, String> expected = ProductionTargetVersions.intents(vector, targets);
        for (var entry : expected.entrySet()) {
            // 限定原始 BIGINT 排序，不能按 id::text 的输出别名把第 9 代排在第 10 代之后。
            var rows =
                    db.queryForList(
                            "SELECT i.id::text,i.consumption_id::text FROM awe_production_target_intent i WHERE i.environment=? AND i.target_key=? ORDER BY i.id DESC LIMIT 1",
                            environment,
                            entry.getKey());
            if (rows.isEmpty()
                    || !entry.getValue().equals(rows.getFirst().get("id"))
                    || !consumption.toString().equals(rows.getFirst().get("consumption_id")))
                return false;
        }
        return !expected.isEmpty();
    }
}
