package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A real previously published source binding does not imply that a later consumer is configured.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=consumption-integration-author",
            "awe.auth.reviewer-token=consumption-integration-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class UnconfiguredDatasetConsumptionIntegrationTest extends DatasetConsumptionTestSupport {
    @Override
    String domain() {
        return "consume_missing";
    }

    @Override
    boolean bootstrapThroughConfiguredServer() {
        return true;
    }

    @DynamicPropertySource
    static void unavailableWarehouse(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> "");
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> "");
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> "");
    }

    @Test
    void absentWarehouseReturnsNotConfiguredWithoutPretendingThePeriodIsEmpty() throws Exception {
        var operation = ok("POST", "/api/data-tasks", ALICE, request(key()));
        assertEquals("NOT_CONFIGURED", operation.path("status").asText(), operation.toString());
        assertNotEquals("NO_RECORD", operation.path("result").path("state").asText());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertFalse(operation.path("payloadAvailable").asBoolean());
        assertEquals("UNKNOWN", operation.path("decision").path("readiness").asText());
        assertEquals("NOT_ESTABLISHED", operation.path("verification").path("structural").asText());
        assertEquals(
                operation,
                ok("GET", "/api/data-tasks/" + operation.path("id").asText(), ALICE, null));
    }
}
