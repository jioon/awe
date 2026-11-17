package ai.awe.registry.consumption;

import ai.awe.registry.consumption.query.MetricQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** REST、MCP、评测及修复重验共享同一能力内核；传输协议不能绕过执行 Gate。 */
@Service
public final class DataTaskOperations {
    private final DataTaskService extraction;
    private final MetricQueryService metrics;

    public DataTaskOperations(DataTaskService extraction, MetricQueryService metrics) {
        this.extraction = extraction;
        this.metrics = metrics;
    }

    public Map<String, Object> create(
            JsonNode body, ConsumerAccess.Grant grant, Map<String, Object> identity) {
        if (body.path("protocolVersion").isIntegralNumber()
                && body.path("protocolVersion").canConvertToInt()
                && Set.of(2, 3).contains(body.path("protocolVersion").intValue()))
            return metrics.create(body, grant, identity);
        return extraction.create(body, grant);
    }

    public Map<String, Object> get(UUID id, ConsumerAccess.Grant grant) {
        return metrics.contains(id) ? metrics.get(id, grant) : extraction.get(id, grant);
    }

    public List<Map<String, Object>> list(ConsumerAccess.Grant grant) {
        var rows = new ArrayList<Map<String, Object>>();
        rows.addAll(extraction.list(grant));
        rows.addAll(metrics.list(grant));
        rows.sort(Comparator.comparingLong(DataTaskOperations::created).reversed());
        return List.copyOf(rows.subList(0, Math.min(100, rows.size())));
    }

    public List<Map<String, Object>> capabilities(ConsumerAccess.Grant grant) {
        var capabilities = new ArrayList<Map<String, Object>>();
        for (var original : extraction.capabilities(grant)) {
            var c = new LinkedHashMap<>(original);
            c.put("protocolVersion", 1);
            c.put("intent", "EXTRACT");
            capabilities.add(c);
        }
        capabilities.addAll(metrics.capabilities(grant));
        return capabilities;
    }

    public Map<String, Object> validatePlan(JsonNode request, ConsumerAccess.Grant grant) {
        return metrics.validatePlan(request, grant);
    }

    public Map<String, Object> assessRequirements(JsonNode request, ConsumerAccess.Grant grant) {
        return metrics.assessRequirements(request, grant);
    }

    public Map<String, Object> requirementAssessment(UUID id, ConsumerAccess.Grant grant) {
        return metrics.requirementAssessment(id, grant);
    }

    private static long created(Map<String, Object> row) {
        Object time = row.get("createdAt");
        if (time instanceof Timestamp t) return t.getTime();
        if (time instanceof Number n) return n.longValue();
        try {
            return Instant.parse(String.valueOf(time)).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
