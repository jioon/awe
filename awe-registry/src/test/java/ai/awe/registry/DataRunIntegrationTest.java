package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Published Dataset/Contract -> actual PostgreSQL rows -> durable failure -> external repair ->
 * replay.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=data-run-integration-author",
            "awe.auth.reviewer-token=data-run-integration-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class DataRunIntegrationTest extends DataRunTestSupport {
    @DynamicPropertySource
    static void actualWarehouse(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
        properties.add("AWE_WAREHOUSE_MAX_ROWS", () -> "10000");
    }

    @Test
    void realWarehouseViolationsAreRepairedAndReplayedWithoutRewritingFailedEvidence()
            throws Exception {
        Published published = publishExecutionContract();
        insertBadRows();
        JsonNode failed = ok("POST", "/api/data-runs", AUTHOR, runRequest(published, key()));
        assertEquals("FAILED", failed.path("status").asText(), failed.toString());
        assertEquals(4, failed.path("rows_checked").asInt());
        assertEquals(1, failed.path("violations").path("check.grain.unique").asInt());
        assertEquals(1, failed.path("violations").path("check.grain.not_null").asInt());
        assertEquals(1, failed.path("violations").path("check.time.available_after_event").asInt());
        assertPinnedRequest(published, failed);
        assertTrue(
                failed.path("data_snapshot_ref")
                        .asText()
                        .matches(
                                "postgresql:binding:[a-f0-9]{64}:tx_snapshot_sha256:[a-f0-9]{64}"));
        assertFalse(failed.path("source_snapshot_id").asText().isBlank());
        var snapshot =
                ok(
                        "GET",
                        "/api/sources/snapshots/" + failed.path("source_snapshot_id").asText(),
                        REVIEWER,
                        null);
        assertEquals(
                published.dataset().path("bindings").path(0).path("source_fingerprint").asText(),
                snapshot.path("fingerprint").asText());
        assertFalse(failed.path("limitations").isEmpty());
        JsonNode gap = gapFor(failed.path("id").asText());
        assertNotNull(gap);
        assertEquals("OPEN", gap.path("status").asText());

        // Repair happens in the actual warehouse, outside AWE's read-only executor.
        replaceWithGoodRows();
        String repairNote =
                "Removed duplicate and null identities and repaired availability timestamps in the source relation";
        String successfulReplayKey = key();
        JsonNode replay =
                ok(
                        "POST",
                        "/api/data-runs/" + failed.path("id").asText() + "/replays",
                        AUTHOR,
                        Map.of("idempotencyKey", successfulReplayKey, "repairNote", repairNote));
        assertEquals("PASSED", replay.path("status").asText(), replay.toString());
        assertEquals(2, replay.path("rows_checked").asInt());
        assertEquals(failed.path("id"), replay.path("replay_of"));
        assertEquals(repairNote, replay.path("repair_note").asText());
        assertPinnedRequest(published, replay);
        assertNotEquals(failed.path("id"), replay.path("id"));
        assertNotEquals(failed.path("data_snapshot_ref"), replay.path("data_snapshot_ref"));
        for (var count : replay.path("violations")) assertEquals(0, count.asInt());
        JsonNode closed = gapFor(failed.path("id").asText());
        assertNotNull(closed);
        assertEquals(gap.path("id"), closed.path("id"));
        assertEquals("CLOSED", closed.path("status").asText());
        assertEquals(replay.path("id"), closed.path("closed_run_id"));
        assertFalse(closed.path("closed_at").isNull());
        assertEquals(
                failed, ok("GET", "/api/data-runs/" + failed.path("id").asText(), REVIEWER, null));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_data_run SET status='PASSED' WHERE id=?::uuid",
                                failed.path("id").asText()));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_data_run WHERE id=?::uuid",
                                replay.path("id").asText()));
        // 实际数据再次退化必须重开问题；旧幂等成功回执不能再次关闭新问题。
        insertBadRows();
        JsonNode regression =
                ok(
                        "POST",
                        "/api/data-runs/" + failed.path("id").asText() + "/replays",
                        AUTHOR,
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "repairNote",
                                "Verify a new source regression"));
        assertEquals("FAILED", regression.path("status").asText());
        assertEquals("OPEN", gapFor(failed.path("id").asText()).path("status").asText());
        JsonNode oldReceipt =
                ok(
                        "POST",
                        "/api/data-runs/" + failed.path("id").asText() + "/replays",
                        AUTHOR,
                        Map.of("idempotencyKey", successfulReplayKey, "repairNote", repairNote));
        assertEquals(replay, oldReceipt);
        assertEquals("OPEN", gapFor(failed.path("id").asText()).path("status").asText());
    }

    @Test
    void idempotentRequestsReturnTheOriginalRunAndChangedPayloadConflicts() throws Exception {
        Published published = publishExecutionContract();
        replaceWithGoodRows();
        var request = runRequest(published, key());
        JsonNode initial = ok("POST", "/api/data-runs", AUTHOR, request);
        assertEquals("PASSED", initial.path("status").asText(), initial.toString());
        insertBadRows();
        JsonNode repeated = ok("POST", "/api/data-runs", AUTHOR, request);
        assertEquals(initial, repeated, "The same request must not re-query a changed warehouse");
        JsonNode alternateContract =
                publishContractFor(published.dataset(), "alternate_row_checks");
        var different =
                runRequest(
                        new Published(published.dataset(), alternateContract),
                        request.get("idempotencyKey").toString());
        assertEquals(409, send("POST", "/api/data-runs", AUTHOR, different).statusCode());
        JsonNode fresh = ok("POST", "/api/data-runs", AUTHOR, runRequest(published, key()));
        assertEquals("FAILED", fresh.path("status").asText(), fresh.toString());
        assertNotEquals(initial.path("id"), fresh.path("id"));
        assertTrue(fresh.path("rows_checked").asInt() > initial.path("rows_checked").asInt());
    }

    @Test
    void authenticationAndPinnedHashOrSubjectFailuresPrecedeAnyWarehouseQuery() throws Exception {
        Published published = publishExecutionContract();
        Published otherSubject = publishExecutionContract();
        db.execute("DROP TABLE " + TABLE);
        var request = runRequest(published, key());
        assertEquals(401, send("POST", "/api/data-runs", null, request).statusCode());
        assertEquals(403, send("POST", "/api/data-runs", REVIEWER, request).statusCode());
        var wrongHash = runRequest(published, key());
        wrongHash.put("contentHash", "0".repeat(64));
        assertEquals(400, send("POST", "/api/data-runs", AUTHOR, wrongHash).statusCode());
        var wrongContractHash = runRequest(published, key());
        wrongContractHash.put("contractContentHash", "0".repeat(64));
        assertEquals(400, send("POST", "/api/data-runs", AUTHOR, wrongContractHash).statusCode());
        var wrongSubject =
                runRequest(new Published(published.dataset(), otherSubject.contract()), key());
        assertEquals(400, send("POST", "/api/data-runs", AUTHOR, wrongSubject).statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM awe_data_run WHERE model_release_id=?::uuid",
                        Integer.class,
                        published.dataset().path("id").asText()));
    }

    @Test
    void metadataDriftBlocksExecutionBeforeTheWarehouseIsRead() throws Exception {
        Published published = publishExecutionContract();
        db.execute("DROP TABLE " + TABLE);
        gateway().put(EXTERNAL_REF, metadata("runtime-schema-v2"));
        JsonNode blocked = ok("POST", "/api/data-runs", AUTHOR, runRequest(published, key()));
        assertEquals("BLOCKED", blocked.path("status").asText(), blocked.toString());
        assertEquals(0, blocked.path("rows_checked").asInt());
        assertTrue(blocked.path("data_snapshot_ref").asText().isBlank());
        assertTrue(blocked.path("gate_report").toString().contains("DRIFTED"));
        assertNull(
                gapFor(blocked.path("id").asText()),
                "Metadata drift is not fabricated row-violation evidence");
        gateway().put(EXTERNAL_REF, metadata("runtime-schema-v1"));
        JsonNode missingTable = ok("POST", "/api/data-runs", AUTHOR, runRequest(published, key()));
        assertEquals("UNAVAILABLE", missingTable.path("status").asText(), missingTable.toString());
        assertNotEquals("PASSED", missingTable.path("status").asText());
    }

    @Test
    void cappedOrEmptySnapshotsAreInconclusiveAndCannotCloseARecordedFailure() throws Exception {
        Published published = publishExecutionContract();
        insertBadRows();
        JsonNode failed = ok("POST", "/api/data-runs", AUTHOR, runRequest(published, key()));
        assertEquals("FAILED", failed.path("status").asText());
        db.execute("TRUNCATE TABLE " + TABLE);
        db.execute(
                "INSERT INTO "
                        + TABLE
                        + " SELECT 'event_'||n, 'user_'||n, TIMESTAMPTZ '2026-11-12 00:00:00+00', TIMESTAMPTZ '2026-11-12 00:01:00+00' FROM generate_series(1,10001) n");
        JsonNode capped =
                ok(
                        "POST",
                        "/api/data-runs/" + failed.path("id").asText() + "/replays",
                        AUTHOR,
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "repairNote",
                                "Large corrected partition still requires complete bounded validation"));
        assertEquals("INCONCLUSIVE", capped.path("status").asText(), capped.toString());
        assertEquals(10000, capped.path("rows_checked").asInt());
        assertEquals("OPEN", gapFor(failed.path("id").asText()).path("status").asText());
        assertTrue(gapFor(failed.path("id").asText()).path("closed_run_id").isNull());
        db.execute("TRUNCATE TABLE " + TABLE);
        JsonNode empty =
                ok(
                        "POST",
                        "/api/data-runs/" + failed.path("id").asText() + "/replays",
                        AUTHOR,
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "repairNote",
                                "Empty relation is insufficient to prove repaired data"));
        assertEquals("INCONCLUSIVE", empty.path("status").asText(), empty.toString());
        assertEquals(0, empty.path("rows_checked").asInt());
        assertEquals("OPEN", gapFor(failed.path("id").asText()).path("status").asText());
        assertEquals(
                "FAILED",
                ok("GET", "/api/data-runs/" + failed.path("id").asText(), AUTHOR, null)
                        .path("status")
                        .asText());
    }

    @Test
    void nondeterministicWarehouseKeyEqualityCannotBeCertifiedByJavaStringComparison()
            throws Exception {
        Published published = publishExecutionContract();
        String collation =
                "awe_it_ci_"
                        + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        db.execute(
                "CREATE COLLATION public."
                        + collation
                        + " (provider=icu, locale='und-u-ks-level2', deterministic=false)");
        boolean changed = false;
        try {
            db.execute(
                    "ALTER TABLE "
                            + TABLE
                            + " ALTER COLUMN event_id TYPE TEXT COLLATE public."
                            + collation);
            changed = true;
            db.execute(
                    "INSERT INTO "
                            + TABLE
                            + " VALUES "
                            + "('A','user_a','2026-11-12T00:00:00Z','2026-11-12T00:01:00Z'),"
                            + "('a','user_b','2026-11-12T00:00:00Z','2026-11-12T00:01:00Z')");
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(DISTINCT event_id) FROM " + TABLE, Integer.class),
                    "The real PostgreSQL collation considers differently cased keys equal");
            JsonNode run = ok("POST", "/api/data-runs", AUTHOR, runRequest(published, key()));
            assertEquals("INCONCLUSIVE", run.path("status").asText(), run.toString());
            assertNotEquals("PASSED", run.path("status").asText());
            assertFalse(run.path("limitations").isEmpty());
            assertNull(
                    gapFor(run.path("id").asText()),
                    "Unsupported database equality is not invented row-violation evidence");
        } finally {
            if (changed) {
                db.execute("TRUNCATE TABLE " + TABLE);
                db.execute(
                        "ALTER TABLE "
                                + TABLE
                                + " ALTER COLUMN event_id TYPE TEXT COLLATE pg_catalog.\"default\"");
            }
            db.execute("DROP COLLATION public." + collation);
        }
    }

    private void assertPinnedRequest(Published published, JsonNode run) {
        assertEquals(published.dataset().path("id"), run.path("model_release_id"));
        assertEquals(published.dataset().path("content_hash"), run.path("model_content_hash"));
        assertEquals(published.contract().path("id"), run.path("contract_release_id"));
        assertEquals(published.contract().path("content_hash"), run.path("contract_content_hash"));
        assertTrue(run.path("request_hash").asText().matches("[a-f0-9]{64}"));
    }

    private JsonNode gapFor(String runId) throws Exception {
        JsonNode found = null;
        for (var gap : ok("GET", "/api/data-gaps", AUTHOR, null))
            if (runId.equals(gap.path("original_run_id").asText())) {
                assertNull(found, "One original failed task must not create duplicate repair gaps");
                found = gap;
            }
        return found;
    }
}
