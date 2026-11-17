package ai.awe.registry.consumption.query;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SemanticMetricRequestTest {
    private final JsonDocuments json = new JsonDocuments();

    private ObjectNode request() {
        return json.object(
                """
                {"protocolVersion":3,"intent":"QUERY_METRIC",
                 "metric":{"releaseId":"00000000-0000-0000-0000-000000000001","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "requirements":{"statisticalSubject":{"entityId":"entity.demo.device","modelVersion":"1.0.0"},
                  "unit":"devices","businessZone":"Asia/Shanghai","timeRole":"EVENT_TIME",
                  "measures":["devices"],"dimensions":["city"],"filters":[],
                  "from":"2026-08-18T00:00:00Z","to":"2026-08-19T00:00:00Z",
                  "view":"OBSERVED_SNAPSHOT","verification":"OBSERVE"},
                 "consumer":{"purpose":"analysis"},"constraints":{"maxRows":100,"maxBytes":65536},"idempotencyKey":"semantic.request"}
                """);
    }

    @Test
    void roundTripsSemanticFieldsWithoutInventingAFunction() {
        var original = request();
        var decoded = SemanticMetricRequest.decode(original);
        var normalized = decoded.normalized(json);
        assertEquals(json.canonical(original), json.canonical(normalized));
        assertFalse(normalized.has("proposedFunction"));
        assertFalse(normalized.has("candidateFunction"));
        assertEquals(decoded.metric(), decoded.withFunction(decoded.metric()).proposedFunction());
        assertEquals(decoded.from(), decoded.withFunction(decoded.metric()).from());
    }

    @Test
    void rejectsUnknownAuthoritySqlOrPlanAssertionsAndMissingSemantics() {
        for (String key :
                new String[] {
                    "sql", "plan", "resolved", "validated", "policyHash", "proposedFunction"
                }) {
            var value = request();
            value.put(key, "caller-asserted");
            assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(value));
        }
        for (String key :
                new String[] {
                    "unit", "timeRole", "businessZone", "dimensions", "filters", "verification"
                }) {
            var value = request();
            ((ObjectNode) value.path("requirements")).remove(key);
            assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(value));
        }
        var value = request();
        ((ObjectNode) value.path("requirements").path("statisticalSubject")).put("tenant", "admin");
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(value));
    }

    @Test
    void rejectsLossyFilterValuesAndNormalizesTypedNumericStrings() {
        var value = request();
        var filters = ((ObjectNode) value.path("requirements")).putArray("filters");
        var filter =
                filters.addObject()
                        .put("field", "city")
                        .put("operation", "EQ")
                        .put("type", "INTEGER");
        filter.put("value", 9007199254740993L);
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(value));
        filter.put("value", "9007199254740993");
        assertEquals(
                "9007199254740993",
                SemanticMetricRequest.decode(value).filters().getFirst().value());
        filter.put("type", "DECIMAL").put("value", "1.2000");
        assertEquals("1.2", SemanticMetricRequest.decode(value).filters().getFirst().value());
        filter.put("type", "BOOLEAN").put("value", "true").put("operation", "GT");
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(value));
        filter.put("type", "INTEGER").put("value", "001").put("operation", "EQ");
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(value));
    }

    @Test
    void rejectsOversizedSemanticRequestsAcrossTransportEntrypoints() {
        var value = request();
        var filters = ((ObjectNode) value.path("requirements")).putArray("filters");
        for (int i = 0; i < 32; i++)
            filters.addObject()
                    .put("field", "city")
                    .put("operation", "EQ")
                    .put("type", "STRING")
                    .put("value", "x".repeat(4096 - Integer.toString(i).length()) + i);
        var failure =
                assertThrows(
                        IllegalArgumentException.class, () -> SemanticMetricRequest.decode(value));
        assertEquals("Semantic data request exceeds 64 KiB", failure.getMessage());
    }

    @Test
    void rejectsDuplicateNamesUnsupportedVersionsAndInvalidTimeOrBudgets() {
        var duplicate = request();
        ((ObjectNode) duplicate.path("requirements"))
                .putArray("dimensions")
                .add("city")
                .add("city");
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(duplicate));
        var version = request().put("protocolVersion", 3.1);
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(version));
        var time = request();
        ((ObjectNode) time.path("requirements")).put("from", "2026-08-18");
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(time));
        var budget = request();
        ((ObjectNode) budget.path("constraints")).put("maxRows", 0);
        assertThrows(IllegalArgumentException.class, () -> SemanticMetricRequest.decode(budget));
    }
}
