package ai.awe.registry.source.postgres;

import ai.awe.registry.source.SourceAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 实际读取 PostgreSQL catalog；没有 PK 时不猜测逻辑粒度，没有配置时不伪造资产。 */
public final class PostgresMetadataAdapter implements SourceAdapter {
    private final PostgresWarehouse warehouse;

    public PostgresMetadataAdapter(PostgresWarehouse warehouse) {
        this.warehouse = warehouse;
    }

    @Override
    public String system() {
        return "postgres";
    }

    @Override
    public boolean configured() {
        return warehouse.configured();
    }

    @Override
    public Fetch fetch(String externalRef) {
        if (!warehouse.configured())
            return Fetch.unavailable(
                    warehouse.status(),
                    "Native PostgreSQL connection and administrator mapping are required");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        try (Connection connection = warehouse.openReadOnly()) {
            try {
                var catalog = warehouse.inspect(connection, externalRef, deadline);
                return new Fetch(
                        "IMPORTED",
                        catalog.sourceVersion(),
                        warehouse.metadata(catalog, externalRef),
                        List.of(
                                "Native PostgreSQL schema/PK observation only; no data watermark or accounting-period readiness is inferred",
                                catalog.primaryKeys().isEmpty()
                                        ? "No primary key observed; logical grain is unknown and was not inferred"
                                        : "Grain is limited to the observed primary-key constraint"));
            } finally {
                connection.rollback();
            }
        } catch (PostgresWarehouse.Failure ex) {
            String status = mapStatus(ex.status());
            return Fetch.unavailable(status, ex.getMessage());
        } catch (SQLException | IllegalArgumentException ex) {
            return Fetch.unavailable(
                    "UNAVAILABLE",
                    "Native PostgreSQL metadata observation could not complete with verified transport and read-only privileges");
        }
    }

    private String mapStatus(String status) {
        return switch (status) {
            case "NOT_CONFIGURED", "NOT_FOUND", "INCONCLUSIVE" ->
                    status.equals("INCONCLUSIVE") ? "UNAVAILABLE" : status;
            default -> "UNAVAILABLE";
        };
    }
}
