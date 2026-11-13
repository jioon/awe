package ai.awe.kernel;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.capability.DataTask;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 请求对象不携带授权结论；格式、时间及资源约束在进入运行边界前即失败关闭。 */
final class DataTaskTest {
    private final Instant from = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-01-02T00:00:00Z");

    private DataTask.Requirements requirements() {
        return new DataTask.Requirements(
                List.of("event_id", "event_time"), from, to, DataTask.View.OBSERVED_SNAPSHOT);
    }

    private DataTask task(int version, String key) {
        return new DataTask(
                version,
                DataTask.Intent.EXTRACT,
                new DataTask.ExternalTaskRef("analysis-app", "external-42"),
                new DataTask.ProposedFunction(UUID.randomUUID(), "a".repeat(64)),
                requirements(),
                new DataTask.Consumer("authorized domain analysis"),
                new DataTask.Constraints(1000, 65536),
                key);
    }

    @Test
    void validRequestPreservesExplicitScopeAndImmutableFields() {
        var task = task(1, "request-1");
        assertEquals(DataTask.Intent.EXTRACT, task.intent());
        assertEquals(from, task.requirements().from());
        assertEquals(to, task.requirements().to());
        var fields = new ArrayList<>(List.of("event_id"));
        var request = new DataTask.Requirements(fields, from, to, DataTask.View.OBSERVED_SNAPSHOT);
        fields.add("unrequested_field");
        assertEquals(List.of("event_id"), request.fields());
        assertThrows(UnsupportedOperationException.class, () -> request.fields().add("other"));
    }

    @Test
    void protocolAndIdempotencyCannotBeSilentlyCoerced() {
        assertThrows(IllegalArgumentException.class, () -> task(2, "request-1"));
        assertThrows(IllegalArgumentException.class, () -> task(0, "request-1"));
        assertThrows(IllegalArgumentException.class, () -> task(1, "same key with spaces"));
        assertThrows(IllegalArgumentException.class, () -> task(1, ""));
        assertThrows(IllegalArgumentException.class, () -> task(1, "x".repeat(121)));
    }

    @Test
    void timeRangeMustBeNonemptyAndForward() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataTask.Requirements(
                                List.of("id"), to, from, DataTask.View.OBSERVED_SNAPSHOT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataTask.Requirements(
                                List.of("id"), from, from, DataTask.View.OBSERVED_SNAPSHOT));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataTask.Requirements(List.of("id"), from, to, null));
    }

    @Test
    void fieldsMustBeExplicitUniqueAndSupportedIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataTask.Requirements(
                                List.of(), from, to, DataTask.View.OBSERVED_SNAPSHOT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataTask.Requirements(
                                List.of("id", "id"), from, to, DataTask.View.OBSERVED_SNAPSHOT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataTask.Requirements(
                                List.of("id; DROP TABLE x"),
                                from,
                                to,
                                DataTask.View.OBSERVED_SNAPSHOT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataTask.Requirements(
                                List.of("*"), from, to, DataTask.View.OBSERVED_SNAPSHOT));
    }

    @Test
    void requestBudgetsCannotExceedHardResourceCeilings() {
        assertDoesNotThrow(() -> new DataTask.Constraints(DataTask.MAX_ROWS, DataTask.MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> new DataTask.Constraints(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DataTask.Constraints(1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataTask.Constraints(DataTask.MAX_ROWS + 1, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataTask.Constraints(100, DataTask.MAX_BYTES + 1));
    }

    @Test
    void proposedReferencesAndPurposesAreBoundedDeclarations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataTask.ProposedFunction(UUID.randomUUID(), "client-says-verified"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataTask.ProposedFunction(UUID.randomUUID(), "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new DataTask.Consumer(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataTask.Consumer("analysis\nadmin=true"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataTask.ExternalTaskRef("app", "id\u0000hidden"));
    }
}
