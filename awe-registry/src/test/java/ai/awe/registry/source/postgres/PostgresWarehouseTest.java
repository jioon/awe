package ai.awe.registry.source.postgres;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PostgresWarehouseTest {
    private final JsonDocuments json = new JsonDocuments();

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("AWE_WAREHOUSE_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/local_test")
                .withProperty("AWE_WAREHOUSE_JDBC_USER", "readonly_unit")
                .withProperty("AWE_WAREHOUSE_JDBC_PASSWORD", "unit-only-not-a-live-secret")
                .withProperty(
                        "AWE_WAREHOUSE_ASSET_BINDINGS",
                        "{\"postgres:consumption.events\":\"public.events\"}");
    }

    @Test
    void absentConfigurationDoesNotInventMetadata() {
        var warehouse = new PostgresWarehouse(new MockEnvironment(), json);
        assertFalse(warehouse.configured());
        var result = new PostgresMetadataAdapter(warehouse).fetch("consumption.events");
        assertEquals("NOT_CONFIGURED", result.status());
        assertNull(result.metadata());
    }

    @Test
    void nativePostgresDoesNotReuseAHiveAliasAsAnImplicitConnection() {
        var warehouse =
                new PostgresWarehouse(
                        environment()
                                .withProperty(
                                        "AWE_WAREHOUSE_ASSET_BINDINGS",
                                        "{\"hive:consumption.events\":\"public.events\"}"),
                        json);
        assertFalse(warehouse.configured());
        assertEquals("NOT_CONFIGURED", warehouse.status());
    }

    @Test
    void malformedConfigurationFailsClosedWithoutPrintingIt() {
        for (String url :
                List.of(
                        "jdbc:hive2://host/default",
                        "jdbc:postgresql://user:secret@host/database",
                        "jdbc:postgresql://remote.example/db?sslmode=disable",
                        "jdbc:postgresql://localhost/db?socketTimeout=0")) {
            var warehouse =
                    new PostgresWarehouse(
                            environment().withProperty("AWE_WAREHOUSE_JDBC_URL", url), json);
            assertFalse(warehouse.configured());
            assertEquals("UNAVAILABLE", warehouse.status());
            assertFalse(
                    new PostgresMetadataAdapter(warehouse)
                            .fetch("consumption.events")
                            .limitations()
                            .toString()
                            .contains(url));
        }
        var unsafe =
                new PostgresWarehouse(
                        environment()
                                .withProperty(
                                        "AWE_WAREHOUSE_ASSET_BINDINGS",
                                        "{\"postgres:x\":\"public.events;DROP\"}"),
                        json);
        assertEquals("UNAVAILABLE", unsafe.status());
    }

    @Test
    void projectionFingerprintPinsIdentityAndRelationButNeverReturnsConfiguration() {
        var a = new PostgresWarehouse(environment(), json);
        var b =
                new PostgresWarehouse(
                        environment().withProperty("AWE_WAREHOUSE_JDBC_USER", "other_scope"), json);
        var c =
                new PostgresWarehouse(
                        environment()
                                .withProperty(
                                        "AWE_WAREHOUSE_ASSET_BINDINGS",
                                        "{\"postgres:consumption.events\":\"public.other_table\"}"),
                        json);
        assertTrue(a.projectionHash("consumption.events").matches("[a-f0-9]{64}"));
        assertNotEquals(
                a.projectionHash("consumption.events"), b.projectionHash("consumption.events"));
        assertNotEquals(
                a.projectionHash("consumption.events"), c.projectionHash("consumption.events"));
    }

    @Test
    void metadataRetainsObservedPkAndTemporalColumnsWithoutGuessingGrain() {
        var warehouse = new PostgresWarehouse(environment(), json);
        var cols =
                List.of(
                        new PostgresWarehouse.Column(
                                "event_id", "text", "text", false, true, "default:"),
                        new PostgresWarehouse.Column(
                                "event_time",
                                "timestamp with time zone",
                                "timestamptz",
                                false,
                                true,
                                ""));
        var catalog =
                new PostgresWarehouse.Catalog("public.events", cols, List.of(), "a".repeat(64));
        var metadata = warehouse.metadata(catalog, "consumption.events");
        assertTrue(metadata.path("grainKeys").isEmpty());
        assertEquals("event_time", metadata.path("timeFields").get(0).asText());
        assertEquals("a".repeat(64), metadata.path("sourceVersion").asText());
    }

    @Test
    void onlyStrictExplicitSchemaQualifiedIdentifiersAreAccepted() {
        assertEquals("\"Mixed\".\"events\"", PostgresWarehouse.quoteRelation("Mixed.events"));
        for (String unsafe :
                List.of(
                        "events",
                        "db.schema.events",
                        "public.events;select x",
                        "public.\"events\""))
            assertThrows(RuntimeException.class, () -> PostgresWarehouse.quoteRelation(unsafe));
    }
}
