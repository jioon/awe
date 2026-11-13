package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Actual native PostgreSQL metadata, HTTP release workflow, consumer authorization and data
 * extraction.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=consumption-integration-author",
            "awe.auth.reviewer-token=consumption-integration-reviewer"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class DatasetConsumptionIntegrationTest extends DatasetConsumptionTestSupport {
    @Override
    String domain() {
        return "consume";
    }

    @DynamicPropertySource
    static void realWarehouse(DynamicPropertyRegistry properties) {
        properties.add("AWE_WAREHOUSE_JDBC_URL", () -> System.getenv("AWE_DB_URL"));
        properties.add("AWE_WAREHOUSE_JDBC_USER", () -> System.getenv("AWE_DB_USER"));
        properties.add("AWE_WAREHOUSE_JDBC_PASSWORD", () -> System.getenv("AWE_DB_PASSWORD"));
    }

    @Test
    void consumesPublishedProjectionWithHalfOpenBoundsAndIndependentSqlReconciliation()
            throws Exception {
        JsonNode operation = ok("POST", "/api/data-tasks", ALICE, request(key()));
        assertEquals("EXTRACTED", operation.path("status").asText(), operation.toString());
        assertEquals("consumer-alice", operation.path("principal").asText());
        assertEquals("RESOLVED", operation.path("decision").path("resolution").asText());
        assertEquals("DIRECT", operation.path("decision").path("supply").asText());
        assertEquals("ALLOWED", operation.path("decision").path("authorization").asText());
        assertEquals("UNKNOWN", operation.path("decision").path("readiness").asText());
        assertEquals("RECORDS", operation.path("result").path("state").asText());
        assertEquals("OBSERVED_SNAPSHOT", operation.path("result").path("scope").asText());
        assertEquals("UNKNOWN", operation.path("result").path("periodCompleteness").asText());
        assertEquals(2, operation.path("result").path("rows").size());
        List<Map<String, Object>> oracle =
                db.queryForList(
                        "SELECT event_id,user_id,event_time FROM "
                                + TABLE
                                + " WHERE event_time>=? AND event_time<? ORDER BY event_id",
                        Timestamp.from(Instant.parse(FROM)),
                        Timestamp.from(Instant.parse(TO)));
        var actual = new TreeMap<String, JsonNode>();
        for (JsonNode row : operation.path("result").path("rows")) {
            assertEquals(Set.of("event_id", "user_id", "event_time"), fieldNames(row));
            assertNull(actual.put(row.path("event_id").asText(), row));
            assertFalse(row.has("sensitive_value"));
        }
        assertEquals(oracle.size(), actual.size());
        for (var expected : oracle) {
            JsonNode row = actual.get(expected.get("event_id"));
            assertNotNull(row);
            assertEquals(expected.get("user_id"), row.path("user_id").asText());
            Object expectedTime = expected.get("event_time");
            Instant oracleTime =
                    expectedTime instanceof Timestamp stamp
                            ? stamp.toInstant()
                            : ((java.time.OffsetDateTime) expectedTime).toInstant();
            assertEquals(oracleTime, Instant.parse(row.path("event_time").asText()));
        }
        assertEquals(Set.of("at_from", "inside"), actual.keySet());
        assertPointer(operation.path("resolved").path("function"), function);
        assertPointer(operation.path("resolved").path("dataset"), dataset);
        assertPointer(operation.path("resolved").path("schema"), rowSchema);
        assertPointer(operation.path("resolved").path("subject"), subject);
        assertEquals(FROM, operation.path("resolved").path("requirements").path("from").asText());
        assertEquals(TO, operation.path("resolved").path("requirements").path("to").asText());
        assertTrue(operation.path("data_snapshot_ref").asText().startsWith("postgresql:binding:"));
        assertEquals("PASSED", operation.path("verification").path("structural").asText());
        assertEquals(
                "NOT_RUN",
                operation.path("verification").path("independentReconciliation").asText());
        assertEquals(
                "EXTERNAL", operation.path("verification").path("businessAcceptance").asText());
        assertEquals(
                json.sha256(operation.path("result").path("rows")),
                operation.path("resultHash").asText());
        assertFalse(operation.toString().contains("fixture-private"));
        assertEquals(
                operation,
                ok("GET", "/api/data-tasks/" + operation.path("id").asText(), ALICE, null));
    }

    @Test
    void observedEmptyRangeReturnsNoRecordWithoutClaimingPeriodReadiness() throws Exception {
        ObjectNode body = request(key());
        ((ObjectNode) body.path("requirements"))
                .put("from", "2026-08-18T02:00:00Z")
                .put("to", "2026-08-18T03:00:00Z");
        JsonNode operation = ok("POST", "/api/data-tasks", ALICE, body);
        assertEquals("EXTRACTED", operation.path("status").asText(), operation.toString());
        assertEquals("NO_RECORD", operation.path("result").path("state").asText());
        assertTrue(operation.path("result").path("rows").isEmpty());
        assertEquals("UNKNOWN", operation.path("decision").path("readiness").asText());
        assertEquals("UNKNOWN", operation.path("result").path("periodCompleteness").asText());
    }

    @Test
    void strictProtocolRejectsUnknownFieldsVersionsViewsAndTamperedHashes() throws Exception {
        List<ObjectNode> invalid = new ArrayList<>();
        var hash = request(key());
        ((ObjectNode) hash.path("proposedFunction")).put("contentHash", "0".repeat(64));
        invalid.add(hash);
        invalid.add(request(key()).put("protocolVersion", 2));
        invalid.add(request(key()).put("protocolVersion", "1"));
        invalid.add(request(key()).put("intent", "SQL"));
        invalid.add(request(key()).put("sql", "select * from forbidden"));
        var requirements = request(key());
        ((ObjectNode) requirements.path("requirements")).put("where", "1=1");
        invalid.add(requirements);
        var view = request(key());
        ((ObjectNode) view.path("requirements")).put("view", "COMPLETE_PERIOD");
        invalid.add(view);
        var consumer = request(key());
        ((ObjectNode) consumer.path("consumer")).put("principal", "admin");
        invalid.add(consumer);
        var limit = request(key());
        ((ObjectNode) limit.path("constraints")).put("maxRows", 1.5);
        invalid.add(limit);
        for (var body : invalid) {
            var response = send("POST", "/api/data-tasks", ALICE, body);
            assertEquals(400, response.statusCode(), response.body());
        }
    }

    @Test
    void capabilitiesProjectionPurposeBudgetsAndOperationsArePrincipalScoped() throws Exception {
        assertEquals(401, send("GET", "/api/capabilities", AUTHOR, null).statusCode());
        assertEquals(401, send("POST", "/api/data-tasks", REVIEWER, request(key())).statusCode());
        assertEquals(401, send("POST", "/api/data-tasks", null, request(key())).statusCode());
        var aliceCapabilities = ok("GET", "/api/capabilities", ALICE, null);
        JsonNode visible = capability(aliceCapabilities, function.path("id").asText());
        assertNotNull(visible);
        Set<String> availableFields = new HashSet<>();
        for (var field : visible.path("fields")) availableFields.add(field.path("name").asText());
        assertEquals(Set.of("event_id", "user_id", "event_time"), availableFields);
        assertEquals("UNKNOWN", visible.path("readiness").asText());
        assertTrue(ok("GET", "/api/capabilities", CHARLIE, null).isEmpty());
        assertEquals(404, send("POST", "/api/data-tasks", CHARLIE, request(key())).statusCode());
        var unknownFunction = request(key());
        ((ObjectNode) unknownFunction.path("proposedFunction"))
                .put("releaseId", UUID.randomUUID().toString());
        assertEquals(404, send("POST", "/api/data-tasks", ALICE, unknownFunction).statusCode());
        var sensitive = request(key());
        ((ObjectNode) sensitive.path("requirements"))
                .set("fields", json.parse("[\"sensitive_value\"]"));
        assertEquals(403, send("POST", "/api/data-tasks", ALICE, sensitive).statusCode());
        var forbiddenPurpose = request(key());
        ((ObjectNode) forbiddenPurpose.path("consumer")).put("purpose", "unapproved-export");
        assertEquals(403, send("POST", "/api/data-tasks", ALICE, forbiddenPurpose).statusCode());
        var overRows = request(key());
        ((ObjectNode) overRows.path("constraints")).put("maxRows", 21);
        assertEquals(403, send("POST", "/api/data-tasks", ALICE, overRows).statusCode());
        var overBytes = request(key());
        ((ObjectNode) overBytes.path("constraints")).put("maxBytes", 4097);
        assertEquals(403, send("POST", "/api/data-tasks", ALICE, overBytes).statusCode());
        var bobProjection = request(key());
        ((ObjectNode) bobProjection.path("constraints")).put("maxRows", 5).put("maxBytes", 1024);
        assertEquals(403, send("POST", "/api/data-tasks", BOB, bobProjection).statusCode());
        JsonNode own = ok("POST", "/api/data-tasks", ALICE, request(key()));
        assertEquals(
                404,
                send("GET", "/api/data-tasks/" + own.path("id").asText(), BOB, null).statusCode());
        assertEquals(
                404,
                send("GET", "/api/data-tasks/" + own.path("id").asText(), CHARLIE, null)
                        .statusCode());
        for (var entry : ok("GET", "/api/data-tasks", BOB, null))
            assertNotEquals(own.path("id"), entry.path("id"));
        boolean found = false;
        for (var entry : ok("GET", "/api/data-tasks", ALICE, null))
            if (entry.path("id").equals(own.path("id"))) {
                found = true;
                assertFalse(entry.has("result"));
                assertEquals("consumer-alice", entry.path("principal").asText());
            }
        assertTrue(found);
    }

    @Test
    void idempotencyAndTerminalEvidenceDoNotReReadMutatedRows() throws Exception {
        var body = request(key());
        JsonNode original = ok("POST", "/api/data-tasks", ALICE, body);
        assertEquals("EXTRACTED", original.path("status").asText());
        db.execute(
                "UPDATE "
                        + TABLE
                        + " SET user_id='changed-after-extraction' WHERE event_id='inside'");
        JsonNode repeated = ok("POST", "/api/data-tasks", ALICE, body);
        assertEquals(original, repeated);
        var conflict = body.deepCopy();
        ((ObjectNode) conflict.path("requirements")).put("to", "2026-08-18T00:45:00Z");
        assertEquals(409, send("POST", "/api/data-tasks", ALICE, conflict).statusCode());
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_data_task SET status='BLOCKED' WHERE id=?::uuid",
                                original.path("id").asText()));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "DELETE FROM awe_data_task WHERE id=?::uuid",
                                original.path("id").asText()));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_data_task_result SET rows_doc='[]'::jsonb WHERE operation_id=?::uuid",
                                original.path("id").asText()));
    }

    @Test
    void rowAndByteLimitsNeverReturnPartiallySuccessfulData() throws Exception {
        for (boolean rowLimit : List.of(true, false)) {
            var body = request(key());
            if (rowLimit) ((ObjectNode) body.path("constraints")).put("maxRows", 1);
            else ((ObjectNode) body.path("constraints")).put("maxBytes", 64);
            JsonNode operation = ok("POST", "/api/data-tasks", ALICE, body);
            assertEquals("INCONCLUSIVE", operation.path("status").asText(), operation.toString());
            assertTrue(operation.path("result").path("rows").isEmpty());
            assertNotEquals("RECORDS", operation.path("result").path("state").asText());
            assertFalse(operation.path("result").path("completeForObservedRange").asBoolean());
            assertFalse(operation.path("payloadAvailable").asBoolean());
        }
    }

    @Test
    void expiredPayloadRetainsProofAndIdempotencyNeverReReadsTheWarehouse() throws Exception {
        var body = request(key());
        JsonNode original = ok("POST", "/api/data-tasks", ALICE, body);
        db.update(
                "DELETE FROM awe_data_task_result WHERE operation_id=?::uuid",
                original.path("id").asText());
        db.execute("UPDATE " + TABLE + " SET user_id='must-not-return-new-rows'");
        JsonNode expired =
                ok("GET", "/api/data-tasks/" + original.path("id").asText(), ALICE, null);
        assertEquals("EXTRACTED", expired.path("status").asText());
        assertEquals("EXPIRED", expired.path("result").path("state").asText());
        assertEquals("RECORDS", expired.path("result").path("recordedState").asText());
        assertFalse(expired.path("payloadAvailable").asBoolean());
        assertTrue(expired.path("result").path("rows").isEmpty());
        assertEquals(original.path("resultHash"), expired.path("resultHash"));
        assertEquals(original.path("data_snapshot_ref"), expired.path("data_snapshot_ref"));
        assertEquals(expired, ok("POST", "/api/data-tasks", ALICE, body));
    }

    @Test
    void nativeSchemaDriftBlocksAFormerlyPublishedCapability() throws Exception {
        db.execute("ALTER TABLE " + TABLE + " ADD COLUMN drifted_marker INTEGER");
        try {
            JsonNode operation = ok("POST", "/api/data-tasks", ALICE, request(key()));
            assertEquals("BLOCKED", operation.path("status").asText(), operation.toString());
            assertTrue(operation.path("result").path("rows").isEmpty());
            assertTrue(operation.path("gate_report").toString().contains("DRIFTED"));
        } finally {
            db.execute("ALTER TABLE " + TABLE + " DROP COLUMN drifted_marker");
        }
    }

    @Test
    void paddedBpcharCannotBypassTheSqlTransferBudgetOrTheDeclaredRowProfile() throws Exception {
        db.execute("ALTER TABLE " + TABLE + " ALTER COLUMN user_id TYPE CHARACTER(1000000)");
        try {
            db.execute("TRUNCATE TABLE " + TABLE);
            db.execute(
                    "INSERT INTO " + TABLE + " VALUES ('padded','x','2026-08-18T00:30:00Z',NULL)");
            assertEquals(
                    1000000,
                    db.queryForObject("SELECT octet_length(user_id) FROM " + TABLE, Integer.class));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT octet_length(user_id::text) FROM " + TABLE, Integer.class),
                    "Casting bpchar to text hides padding and cannot protect the JDBC transfer budget");
            var imported =
                    ok(
                            "POST",
                            "/api/sources/postgres/imports",
                            AUTHOR,
                            Map.of("externalRef", "consumption.events"));
            assertEquals("IMPORTED", imported.path("status").asText(), imported.toString());
            var definition =
                    spec(
                            model(
                                    "ref",
                                    ref("dataset.consume.bpchar_events"),
                                    "grain",
                                    Map.of("kind", "EVENT", "keys", List.of("event_id")),
                                    "time",
                                    Map.of(
                                            "roles",
                                            List.of("EVENT_TIME"),
                                            "businessZone",
                                            "Asia/Shanghai",
                                            "backfillPossible",
                                            false),
                                    "subjectType",
                                    ref("entity.consume.user"),
                                    "dimensions",
                                    List.of()));
            definition.set(
                    "bindings",
                    json.parse(
                            json.write(
                                    List.of(
                                            Map.of(
                                                    "sourceSystem",
                                                    "postgres",
                                                    "externalRef",
                                                    "consumption.events",
                                                    "fingerprint",
                                                    imported.path("fingerprint").asText(),
                                                    "timeFields",
                                                    Map.of("EVENT_TIME", "event_time"))))));
            publish("Dataset", definition);
            var paddedFunction =
                    publish(
                            "FunctionDefinition",
                            spec(
                                    model(
                                            "ref",
                                            ref("function.consume.bpchar_extract"),
                                            "ownerType",
                                            ref("dataset.consume.bpchar_events"),
                                            "operation",
                                            "dataset.extract.v1",
                                            "parameters",
                                            List.of(
                                                    Map.of(
                                                            "name",
                                                            "from",
                                                            "type",
                                                            "TIMESTAMP",
                                                            "required",
                                                            true),
                                                    Map.of(
                                                            "name",
                                                            "to",
                                                            "type",
                                                            "TIMESTAMP",
                                                            "required",
                                                            true)),
                                            "resultSchemaRef",
                                            "dataset.consume.event_row@1.0.0",
                                            "executorBindingRef",
                                            "postgres.dataset.extract.v1",
                                            "requiredAssertions",
                                            List.of())));
            for (var fields : List.of(List.of("user_id"), List.of("event_id", "event_time"))) {
                var body = request(key());
                ((ObjectNode) body.path("proposedFunction"))
                        .put("releaseId", paddedFunction.path("id").asText())
                        .put("contentHash", paddedFunction.path("content_hash").asText());
                ((ObjectNode) body.path("requirements"))
                        .set("fields", json.parse(json.write(fields)));
                var operation = ok("POST", "/api/data-tasks", ALICE, body);
                assertEquals("BLOCKED", operation.path("status").asText(), operation.toString());
                assertTrue(operation.path("result").path("rows").isEmpty());
                assertFalse(operation.path("payloadAvailable").asBoolean());
            }
        } finally {
            db.execute("TRUNCATE TABLE " + TABLE);
            db.execute("ALTER TABLE " + TABLE + " ALTER COLUMN user_id TYPE TEXT");
        }
    }

    @Test
    void inheritedChildRowsCannotBeCertifiedUsingOnlyTheParentsPrimaryKey() throws Exception {
        String child =
                "public.awe_consume_child_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        db.execute("CREATE TABLE " + child + " () INHERITS (" + TABLE + ")");
        try {
            db.execute(
                    "INSERT INTO "
                            + child
                            + " VALUES ('at_from','child_user','2026-08-18T00:00:00Z',NULL)");
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT count(*) FROM " + TABLE + " WHERE event_id='at_from'",
                            Integer.class),
                    "A parent primary key does not constrain traditional inheritance children");
            var observation =
                    ok(
                            "POST",
                            "/api/sources/postgres/imports",
                            AUTHOR,
                            Map.of("externalRef", "consumption.events"));
            assertNotEquals("IMPORTED", observation.path("status").asText());
            var operation = ok("POST", "/api/data-tasks", ALICE, request(key()));
            assertEquals("UNAVAILABLE", operation.path("status").asText(), operation.toString());
            assertTrue(operation.path("result").path("rows").isEmpty());
            assertFalse(operation.path("payloadAvailable").asBoolean());
        } finally {
            db.execute("DROP TABLE " + child);
        }
    }

    @Test
    void encodedAndMatrixPathsCannotBypassEitherAuthenticationRealm() throws Exception {
        for (String path :
                List.of(
                        "/%61pi/releases",
                        "/api;v=1/releases", "/%61pi/capabilities", "/api;v=1/capabilities")) {
            int status = send("GET", path, null, null).statusCode();
            assertTrue(
                    status >= 400 && status < 500,
                    path + " must reject unauthenticated requests, got " + status);
        }
        var draft =
                createDraft(
                        "ObjectType",
                        spec(
                                model(
                                        "ref",
                                        ref("entity.consume.path_security"),
                                        "family",
                                        "BUSINESS_ENTITY",
                                        "description",
                                        "Path filter regression",
                                        "properties",
                                        List.of(property("user_id", "STRING", true, false)),
                                        "identityKeys",
                                        List.of("user_id"))));
        String id = draft.path("id").asText();
        ok("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        for (String suffix : List.of("approv%65", "approve;x=y")) {
            int status =
                    send("POST", "/api/drafts/" + id + "/" + suffix, AUTHOR, Map.of()).statusCode();
            assertTrue(
                    status >= 400 && status < 500,
                    "Author must not approve through alternate URI syntax, got " + status);
            assertEquals(
                    "IN_REVIEW",
                    ok("GET", "/api/drafts/" + id, AUTHOR, null).path("state").asText());
        }
        for (String path : List.of("/%61pi/capabilities", "/api;v=1/capabilities")) {
            int status = send("GET", path, AUTHOR, null).statusCode();
            assertTrue(
                    status >= 400 && status < 500,
                    "Model author credentials must not enter the consumer realm");
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode capability(JsonNode capabilities, String releaseId) {
        for (var item : capabilities)
            if (releaseId.equals(item.path("releaseId").asText())) return item;
        return null;
    }

    private void assertPointer(JsonNode pointer, JsonNode release) {
        assertEquals(release.path("id").asText(), pointer.path("releaseId").asText());
        assertEquals(release.path("content_hash").asText(), pointer.path("contentHash").asText());
        assertEquals(release.path("entity_id").asText(), pointer.path("entityId").asText());
        assertEquals(release.path("version").asText(), pointer.path("modelVersion").asText());
    }
}
