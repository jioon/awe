package ai.awe.registry.consumption;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataTaskRequestsTest {
    private final JsonDocuments json = new JsonDocuments();

    private ObjectNode request() {
        return json.object(
                """
                {"protocolVersion":1,"intent":"EXTRACT",
                 "proposedFunction":{"releaseId":"16a8a491-cce4-490a-a309-c8d58ed9d776","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "requirements":{"fields":["event_id"],"from":"2026-11-12T00:00:00+08:00","to":"2026-11-13T00:00:00+08:00","view":"OBSERVED_SNAPSHOT"},
                 "consumer":{"purpose":"analysis"},"constraints":{"maxRows":10,"maxBytes":1024},"idempotencyKey":"read-1"}
                """);
    }

    @Test
    void equivalentExplicitOffsetsNormalizeToTheSameRequest() {
        var first = request();
        var second = request();
        ((ObjectNode) second.path("requirements"))
                .put("from", "2026-11-11T16:00:00Z")
                .put("to", "2026-11-12T16:00:00Z");
        assertEquals(
                json.sha256(DataTaskRequests.normalized(DataTaskRequests.decode(first), json)),
                json.sha256(DataTaskRequests.normalized(DataTaskRequests.decode(second), json)));
    }

    @Test
    void callerAuthorityAndUnimplementedRequirementsCannotBeIgnored() {
        for (String field :
                List.of("resolvedEntities", "principal", "workflow", "sql", "evidence")) {
            var request = request();
            request.put(field, "PASSED");
            assertThrows(IllegalArgumentException.class, () -> DataTaskRequests.decode(request));
        }
        var request = request();
        ((ObjectNode) request.path("consumer")).put("role", "admin");
        assertThrows(IllegalArgumentException.class, () -> DataTaskRequests.decode(request));
        var complete = request();
        ((ObjectNode) complete.path("requirements")).put("view", "PERIOD_COMPLETE");
        assertThrows(IllegalArgumentException.class, () -> DataTaskRequests.decode(complete));
        var filter = request();
        ((ObjectNode) filter.path("requirements")).put("filter", "user_id='other-tenant'");
        assertThrows(IllegalArgumentException.class, () -> DataTaskRequests.decode(filter));
    }

    @Test
    void datesWithoutOffsetOrCoercedBudgetsAreRejected() {
        var naive = request();
        ((ObjectNode) naive.path("requirements")).put("from", "2026-11-12T00:00:00");
        assertThrows(IllegalArgumentException.class, () -> DataTaskRequests.decode(naive));
        var coerced = request();
        ((ObjectNode) coerced.path("constraints")).put("maxRows", "10");
        assertThrows(IllegalArgumentException.class, () -> DataTaskRequests.decode(coerced));
    }
}
