package ai.awe.registry.production;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.registry.support.JsonDocuments;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionTargetsTest {
    private final JsonDocuments json = new JsonDocuments();

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("AWE_PRODUCTION_ENVIRONMENT", "prod")
                .withProperty(
                        "AWE_PRODUCTION_TARGET_BINDINGS",
                        json.write(
                                Map.of(
                                        "target.orders",
                                        Map.of(
                                                "sourceSystem",
                                                "postgres",
                                                "externalRef",
                                                "domain.orders",
                                                "timeFields",
                                                Map.of()))));
    }

    @Test
    void productionRejectsAliasesToOneWarehouseRelationWithoutChangingUnrelatedTargets() {
        var env =
                environment()
                        .withProperty(
                                "AWE_WAREHOUSE_ASSET_BINDINGS",
                                json.write(
                                        Map.of(
                                                "postgres:domain.orders",
                                                "warehouse.orders",
                                                "postgres:alias.orders",
                                                "warehouse.orders")));
        assertTrue(new ProductionTargets(env, json).find("target.orders").isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionTargets(env, json).require("target.orders"));
    }

    @Test
    void canonicalAssetMappingsRemainUsableAndScopeIsEnvironmentBound() {
        var env =
                environment()
                        .withProperty(
                                "AWE_WAREHOUSE_ASSET_BINDINGS",
                                json.write(
                                        Map.of(
                                                "postgres:domain.orders",
                                                "warehouse.orders",
                                                "postgres:other",
                                                "warehouse.other")));
        var production = new ProductionTargets(env, json);
        assertTrue(production.find("target.orders").isPresent());
        var staging =
                new ProductionTargets(
                        environment().withProperty("AWE_PRODUCTION_ENVIRONMENT", "stage"), json);
        assertNotEquals(production.configurationHash(), staging.configurationHash());
    }
}
