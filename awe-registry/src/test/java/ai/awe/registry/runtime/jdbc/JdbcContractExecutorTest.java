package ai.awe.registry.runtime.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.awe.kernel.contract.Grain;
import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * No simulated successful data checks: unit tests cover fail-closed configuration and SQL
 * construction only.
 */
class JdbcContractExecutorTest {
    private final JsonDocuments json = new JsonDocuments();
    private final Set<String> rules = JdbcContractExecutor.RULE_IDS;

    private MockEnvironment configuredEnvironment() {
        return new MockEnvironment()
                .withProperty(
                        "AWE_WAREHOUSE_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/isolated_test")
                .withProperty("AWE_WAREHOUSE_JDBC_USER", "readonly_test")
                .withProperty("AWE_WAREHOUSE_JDBC_PASSWORD", "local-unit-test-only")
                .withProperty(
                        "AWE_WAREHOUSE_ASSET_BINDINGS",
                        "{\"hive:runtime.events\":\"public.awe_test_events\"}");
    }

    private JsonNode binding() {
        return json.parse(
                """
          {"sourceSystem":"hive","externalRef":"runtime.events",
           "timeFields":{"EVENT_TIME":"event_time","AVAILABLE_AT":"available_at"}}
          """);
    }

    private SemanticModel.Dataset model(List<String> keys, Set<TemporalContract.TimeRole> roles) {
        return new SemanticModel.Dataset(
                new EntityRef("dataset.runtime.events", "1.0.0"),
                new Grain(keys, Grain.GrainKind.EVENT),
                new TemporalContract(roles, ZoneId.of("UTC"), true),
                new EntityRef("entity.runtime.user", "1.0.0"),
                Set.of());
    }

    private SemanticModel.Dataset model() {
        return model(
                List.of("event_id"),
                Set.of(
                        TemporalContract.TimeRole.EVENT_TIME,
                        TemporalContract.TimeRole.AVAILABLE_AT));
    }

    @Test
    void missingConnectionIsExplicitWithoutSuccessfulZeroCountEvidence() {
        var executor = new JdbcContractExecutor(new MockEnvironment(), json);
        assertFalse(executor.configured());
        var result = executor.execute(model(), binding(), rules);
        assertEquals("NOT_CONFIGURED", result.status());
        assertEquals("", result.dataSnapshotRef());
        assertEquals(0, result.rowsChecked());
        assertEquals(
                Set.of(
                        "check.grain.unique",
                        "check.grain.not_null",
                        "check.time.available_after_event"),
                result.violations().keySet());
    }

    @Test
    void unknownAssetRequiresAdministratorMappingWithoutTryingClientSuppliedRelation() {
        var executor = new JdbcContractExecutor(configuredEnvironment(), json);
        assertTrue(executor.configured());
        ObjectNode unbound = (ObjectNode) binding().deepCopy();
        unbound.put("externalRef", "unmapped.asset");
        unbound.put("table", "attacker.table");
        assertEquals("NOT_CONFIGURED", executor.execute(model(), unbound, rules).status());
    }

    @Test
    void unsupportedRulesCannotTurnIntoAnEmptyPassingRun() {
        var executor = new JdbcContractExecutor(new MockEnvironment(), json);
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(model(), binding(), Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(model(), binding(), Set.of("arbitrary.sql")));
        var nullRule = new HashSet<String>();
        nullRule.add(null);
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(model(), binding(), nullRule));
    }

    @Test
    void malformedUnsupportedOrUnsafeConnectionConfigurationIsUnavailableAndSanitized() {
        for (String value :
                List.of(
                        "jdbc:hive2://warehouse:10000/default",
                        "jdbc:postgresql://privateuser:do-not-leak@host/database",
                        "jdbc:postgresql://warehouse.example/db?sslmode=disable",
                        "jdbc:postgresql://localhost/db?socketTimeout=0",
                        "jdbc:postgresql://localhost/db?options=-c%20search_path%3Devil",
                        "jdbc:postgresql://localhost/db?sslfactory=custom.Class")) {
            var executor =
                    new JdbcContractExecutor(
                            configuredEnvironment().withProperty("AWE_WAREHOUSE_JDBC_URL", value),
                            json);
            assertFalse(executor.configured());
            var result = executor.execute(model(), binding(), rules);
            assertEquals("UNAVAILABLE", result.status());
            assertFalse(result.toString().contains(value));
            assertFalse(result.toString().contains("do-not-leak"));
        }
        assertTrue(
                new JdbcContractExecutor(
                                configuredEnvironment()
                                        .withProperty(
                                                "AWE_WAREHOUSE_JDBC_URL",
                                                "jdbc:postgresql://warehouse.example/db?sslmode=verify-full"),
                                json)
                        .configured());
    }

    @Test
    void invalidMappingsAndOutOfBudgetRowsNeverFallbackToDefaults() {
        for (String mappings :
                List.of(
                        "{",
                        "[]",
                        "{\"hive:runtime.events\":\"public.events;DROP TABLE x\"}",
                        "{\"hive:runtime.events\":\"events\"}",
                        "{\"hive:runtime.events\":\"db.schema.events\"}",
                        "{\"hive:runtime.events\":42}")) {
            var executor =
                    new JdbcContractExecutor(
                            configuredEnvironment()
                                    .withProperty("AWE_WAREHOUSE_ASSET_BINDINGS", mappings),
                            json);
            assertEquals("UNAVAILABLE", executor.execute(model(), binding(), rules).status());
        }
        for (String rows : List.of("0", "-1", "100001", "999999999999999", "unbounded")) {
            var executor =
                    new JdbcContractExecutor(
                            configuredEnvironment().withProperty("AWE_WAREHOUSE_MAX_ROWS", rows),
                            json);
            assertEquals("UNAVAILABLE", executor.execute(model(), binding(), rules).status());
        }
        assertTrue(
                new JdbcContractExecutor(
                                configuredEnvironment()
                                        .withProperty("AWE_WAREHOUSE_MAX_ROWS", "100000"),
                                json)
                        .configured());
    }

    @Test
    void planHasOnlyQuotedServerMappedRelationAndDeduplicatedModelColumnsWithBoundedSelect() {
        var dataset =
                model(
                        List.of("event_id", "event_time"),
                        Set.of(
                                TemporalContract.TimeRole.EVENT_TIME,
                                TemporalContract.TimeRole.AVAILABLE_AT));
        var plan =
                JdbcContractExecutor.plan(dataset, binding(), rules, "public.awe_test_events", 10);
        assertEquals(
                "SELECT \"event_id\",\"event_time\",\"available_at\" FROM \"public\".\"awe_test_events\" LIMIT 11",
                plan.sql());
        assertEquals(List.of("event_id", "event_time", "available_at"), plan.columns());
        assertEquals(2, plan.indexOf("event_time"));
        assertEquals(
                "\"CaseSensitive\".\"events\"",
                JdbcContractExecutor.quoteRelation("CaseSensitive.events"));
    }

    @Test
    void injectedColumnsCannotBecomeSqlAndMissingOrConflatedTimeMappingsAreInconclusive() {
        var executor = new JdbcContractExecutor(configuredEnvironment(), json);
        var dangerous =
                model(
                        List.of("event_id; select secret()"),
                        Set.of(TemporalContract.TimeRole.EVENT_TIME));
        assertEquals(
                "UNAVAILABLE",
                executor.execute(dangerous, binding(), Set.of("check.grain.unique")).status());
        var noAvailability =
                model(List.of("event_id"), Set.of(TemporalContract.TimeRole.EVENT_TIME));
        assertEquals(
                "INCONCLUSIVE",
                executor.execute(
                                noAvailability,
                                binding(),
                                Set.of("check.time.available_after_event"))
                        .status());
        ObjectNode conflated = (ObjectNode) binding().deepCopy();
        ((ObjectNode) conflated.path("timeFields")).put("AVAILABLE_AT", "event_time");
        assertEquals(
                "INCONCLUSIVE",
                executor.execute(model(), conflated, Set.of("check.time.available_after_event"))
                        .status());
        ObjectNode missing = (ObjectNode) binding().deepCopy();
        missing.remove("timeFields");
        assertEquals(
                "INCONCLUSIVE",
                executor.execute(model(), missing, Set.of("check.time.available_after_event"))
                        .status());
    }

    @Test
    void aggregateRetainedKeyBudgetStopsBeforeUnboundedTupleStorage() {
        long first = JdbcContractExecutor.retainKeyBudget(0, List.of("identity", 123L));
        assertTrue(first >= 128 + 16 * 2 + 64 + 2 * "identity".length() + 128);
        long second = JdbcContractExecutor.retainKeyBudget(first, List.of("another"));
        assertTrue(second > first);
        RuntimeException failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                JdbcContractExecutor.retainKeyBudget(
                                        JdbcContractExecutor.MAX_RETAINED_KEY_BYTES,
                                        List.of("identity")));
        assertTrue(failure.getMessage().contains("memory budget"));
        assertFalse(failure.getMessage().contains("identity"));
    }

    @Test
    void
            snapshotReferenceIsBoundedAndPinsProjectionAndActualTransactionWithoutLeakingConfiguration() {
        var executor = new JdbcContractExecutor(configuredEnvironment(), json);
        String snapshot = "10:20:" + "11,".repeat(10_000) + "12";
        String ref =
                executor.snapshotReference(
                        snapshot, "public.awe_test_events", "hive", "runtime.events");
        assertTrue(ref.matches("postgresql:binding:[a-f0-9]{64}:tx_snapshot_sha256:[a-f0-9]{64}"));
        assertEquals(
                ref,
                executor.snapshotReference(
                        snapshot, "public.awe_test_events", "hive", "runtime.events"));
        assertNotEquals(
                ref,
                executor.snapshotReference(
                        snapshot, "public.other_projection", "hive", "runtime.events"));
        assertNotEquals(
                ref,
                executor.snapshotReference(
                        snapshot + "1", "public.awe_test_events", "hive", "runtime.events"));
        assertFalse(ref.contains("awe_test_events"));
        assertFalse(ref.contains("jdbc:"));
        assertFalse(ref.contains("local-unit-test-only"));
        assertTrue(ref.length() < 200);
    }

    @Test
    void nondeterministicCollationCannotPassClientGrainEqualityAndMetadataQueryIsParameterized()
            throws Exception {
        Connection connection = mock(Connection.class);
        Statement lock = mock(Statement.class);
        PreparedStatement check = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        java.sql.Array keys = mock(java.sql.Array.class);
        when(connection.createStatement()).thenReturn(lock);
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(keys);
        when(connection.prepareStatement(anyString())).thenReturn(check);
        when(check.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        assertFalse(
                JdbcContractExecutor.deterministicGrainCollations(
                        connection, "public.events", List.of("event_id")));
        verify(lock)
                .execute(
                        "LOCK TABLE "
                                + JdbcContractExecutor.quoteRelation("public.events")
                                + " IN ACCESS SHARE MODE");
        verify(connection)
                .prepareStatement(
                        argThat(
                                sql ->
                                        sql.contains("pg_catalog.pg_collation")
                                                && sql.contains("pg_catalog.to_regclass(?)")
                                                && sql.contains("ANY(?::text[])")
                                                && sql.contains("NOT c.collisdeterministic")
                                                && !sql.contains("event_id")));
        verify(check).setString(1, JdbcContractExecutor.quoteRelation("public.events"));
        verify(check).setArray(2, keys);
        verify(keys).free();
        verify(check).close();
        verify(lock).close();
    }

    @Test
    void provenanceChangesWithConfiguredDatabaseIdentityWithoutRevealingIt() {
        var first = new JdbcContractExecutor(configuredEnvironment(), json);
        var second =
                new JdbcContractExecutor(
                        configuredEnvironment()
                                .withProperty(
                                        "AWE_WAREHOUSE_JDBC_USER", "different_readonly_scope"),
                        json);
        String a = first.snapshotReference("10:20:", "public.events", "hive", "runtime.events");
        String b = second.snapshotReference("10:20:", "public.events", "hive", "runtime.events");
        assertNotEquals(a, b);
        assertFalse(b.contains("different_readonly_scope"));
    }

    @Test
    void rowSecurityEnabledRelationDoesNotEstablishWholeRelationProof() throws Exception {
        Connection connection = mock(Connection.class);
        Statement lock = mock(Statement.class);
        PreparedStatement check = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(lock);
        when(connection.prepareStatement(anyString())).thenReturn(check);
        when(check.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(true);
        assertFalse(JdbcContractExecutor.unrestrictedRowScope(connection, "public.events"));
        verify(connection)
                .prepareStatement(
                        argThat(
                                sql ->
                                        sql.contains("relrowsecurity OR relforcerowsecurity")
                                                && sql.contains("pg_catalog.to_regclass(?)")));
        verify(check).setString(1, JdbcContractExecutor.quoteRelation("public.events"));
        verify(check).close();
        verify(lock).close();
    }

    @Test
    void resultDoesNotPermitCallerMutationOfViolationCounts() {
        var result =
                new JdbcContractExecutor(new MockEnvironment(), json)
                        .execute(model(), binding(), rules);
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.violations().put("check.grain.unique", 123L));
    }
}
