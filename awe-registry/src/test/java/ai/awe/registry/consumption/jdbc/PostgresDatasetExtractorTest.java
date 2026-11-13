package ai.awe.registry.consumption.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.Grain;
import ai.awe.kernel.contract.TemporalContract;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.source.postgres.PostgresWarehouse;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PostgresDatasetExtractorTest {
    private final JsonDocuments json = new JsonDocuments();

    private SemanticModel.Dataset dataset() {
        return new SemanticModel.Dataset(
                new EntityRef("dataset.consumption.events", "1.0.0"),
                new Grain(List.of("event_id"), Grain.GrainKind.EVENT),
                new TemporalContract(
                        Set.of(TemporalContract.TimeRole.EVENT_TIME), ZoneId.of("UTC"), false),
                new EntityRef("entity.consumption.user", "1.0.0"),
                Set.of());
    }

    private ObjectType rowSchema() {
        return new ObjectType(
                new EntityRef("asset.consumption.rows", "1.0.0"),
                ObjectType.Family.PHYSICAL_ASSET,
                "Explicit extract row",
                List.of(
                        new ObjectType.Property(
                                "event_id", ObjectType.DataType.STRING, true, false),
                        new ObjectType.Property(
                                "event_time", ObjectType.DataType.TIMESTAMP, true, false)),
                List.of("event_id"));
    }

    private JsonNode binding() {
        return json.parse(
                "{\"sourceSystem\":\"postgres\",\"externalRef\":\"consumption.events\",\"fingerprint\":\""
                        + "a".repeat(64)
                        + "\",\"timeFields\":{\"EVENT_TIME\":\"event_time\"}}");
    }

    private PostgresDatasetExtractor.Plan plan(
            List<String> fields, Instant from, int rows, int bytes) {
        return new PostgresDatasetExtractor.Plan(
                dataset(),
                rowSchema(),
                binding(),
                fields,
                from,
                Instant.parse("2026-11-13T00:00:00Z"),
                rows,
                bytes);
    }

    private PostgresDatasetExtractor extractor() {
        return new PostgresDatasetExtractor(
                new PostgresWarehouse(new MockEnvironment(), json), json);
    }

    @Test
    void missingNativeConnectionIsNotAnEmptySuccessfulDataset() {
        var result =
                extractor()
                        .execute(
                                plan(
                                        List.of("event_id"),
                                        Instant.parse("2026-11-12T00:00:00Z"),
                                        10,
                                        1024));
        assertEquals("NOT_CONFIGURED", result.status());
        assertTrue(result.rows().isEmpty());
        assertEquals("", result.dataSnapshotRef());
    }

    @Test
    void noSilentNanosecondRoundingAndInvalidBudgetsFailClosed() {
        assertEquals(
                "BLOCKED",
                extractor()
                        .execute(
                                plan(
                                        List.of("event_id"),
                                        Instant.parse("2026-11-12T00:00:00.000000001Z"),
                                        10,
                                        1024))
                        .status());
        assertEquals(
                "BLOCKED",
                extractor()
                        .execute(
                                plan(
                                        List.of("event_id"),
                                        Instant.parse("2026-11-12T00:00:00Z"),
                                        10,
                                        1048577))
                        .status());
        assertEquals(
                "BLOCKED",
                extractor()
                        .execute(
                                plan(
                                        List.of("event_id", "event_id"),
                                        Instant.parse("2026-11-12T00:00:00Z"),
                                        10,
                                        1024))
                        .status());
    }

    @Test
    void sqlSeparatesPreparedTimeParametersAndPreventsOversizedCellTransfer() {
        var plan =
                plan(
                        List.of("event_id", "event_time"),
                        Instant.parse("2026-11-12T00:00:00Z"),
                        10,
                        1024);
        String sql = PostgresDatasetExtractor.sql(plan, "public.events", "event_time");
        assertTrue(
                sql.contains(
                        "pg_catalog.octet_length(\"event_id\"::pg_catalog.text)>1024 THEN NULL"));
        assertTrue(
                sql.contains(
                        "WHERE \"event_time\">=? AND \"event_time\"<? ORDER BY \"event_id\" LIMIT 11"));
        assertTrue(sql.contains("__awe_overflow"));
        assertFalse(sql.contains("2026-11"));
    }

    @Test
    void sameNameFieldsCannotInjectSql() {
        var malicious =
                plan(
                        List.of("event_id;SELECT pg_sleep(9)"),
                        Instant.parse("2026-11-12T00:00:00Z"),
                        10,
                        1024);
        assertEquals("BLOCKED", extractor().execute(malicious).status());
        assertThrows(
                RuntimeException.class,
                () -> PostgresDatasetExtractor.sql(malicious, "public.events", "event_time"));
    }

    @Test
    void unsupportedCellTypesAreNotCoercedToDeclaredPrimitive() {
        assertFalse(PostgresDatasetExtractor.compatible(ObjectType.DataType.STRING, "bpchar"));
        assertFalse(PostgresDatasetExtractor.compatible(ObjectType.DataType.INTEGER, "text"));
        assertFalse(PostgresDatasetExtractor.compatible(ObjectType.DataType.DECIMAL, "float8"));
        assertTrue(
                PostgresDatasetExtractor.compatible(ObjectType.DataType.TIMESTAMP, "timestamptz"));
    }

    @Test
    void outputResultIsImmutableAndUsesSerializablePrimitiveCells() {
        var original = new LinkedHashMap<String, Object>();
        original.put("event_id", "e1");
        original.put("optional", null);
        var result =
                new PostgresDatasetExtractor.Result(
                        "EXTRACTED", "snapshot", List.of(original), List.of("observed only"));
        original.put("event_id", "mutated");
        assertEquals("e1", result.rows().get(0).get("event_id"));
        assertDoesNotThrow(() -> json.write(result));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.rows().get(0).put("event_id", "x"));
    }
}
