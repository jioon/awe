package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A configured Registry/source gateway cannot manufacture success for an absent warehouse binding.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=data-run-integration-author",
            "awe.auth.reviewer-token=data-run-integration-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class UnconfiguredDataRunIntegrationTest extends DataRunTestSupport {
    @DynamicPropertySource
    static void absentWarehouse(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> "");
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> "");
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> "");
    }

    @Test
    void missingWarehouseConfigurationProducesNotConfiguredEvidenceWithoutFakeRows()
            throws Exception {
        Published published = publishExecutionContract();
        replaceWithGoodRows();
        var run = ok("POST", "/api/data-runs", AUTHOR, runRequest(published, key()));
        assertEquals("NOT_CONFIGURED", run.path("status").asText(), run.toString());
        assertEquals(0, run.path("rows_checked").asInt());
        assertTrue(run.path("data_snapshot_ref").asText().isBlank());
        assertFalse(run.path("limitations").isEmpty());
        assertEquals(run, ok("GET", "/api/data-runs/" + run.path("id").asText(), REVIEWER, null));
        for (var gap : ok("GET", "/api/data-gaps", AUTHOR, null))
            assertNotEquals(run.path("id").asText(), gap.path("original_run_id").asText());
        assertNotEquals("PASSED", run.path("status").asText());
    }
}
