package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.runtime.DataRunScope;
import org.junit.jupiter.api.Test;

class DataRunScopeTest {
    private String snapshot(String projection, String transaction) {
        return "postgresql:binding:"
                + projection.repeat(64)
                + ":tx_snapshot_sha256:"
                + transaction.repeat(64);
    }

    @Test
    void repairMustKeepPhysicalProjectionWhileReadingAFreshTransaction() {
        assertTrue(DataRunScope.sameProjection(snapshot("a", "b"), snapshot("a", "c")));
        assertFalse(DataRunScope.sameProjection(snapshot("a", "b"), snapshot("c", "b")));
    }

    @Test
    void missingOrMalformedProvenanceCannotCloseAPhysicalProblem() {
        assertFalse(DataRunScope.sameProjection(null, snapshot("a", "b")));
        assertFalse(DataRunScope.sameProjection("postgresql:unknown", snapshot("a", "b")));
        assertFalse(
                DataRunScope.sameProjection(snapshot("a", "b"), snapshot("a", "b") + ":ignored"));
    }
}
