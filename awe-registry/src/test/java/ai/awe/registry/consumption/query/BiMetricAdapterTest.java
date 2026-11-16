package ai.awe.registry.consumption.query;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.contract.*;
import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 只测试可信网关协议的证明范围与失败语义；真实数值计算另由 PostgreSQL 集成测试验收。 */
class BiMetricAdapterTest {
    private final JsonDocuments json = new JsonDocuments();
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-08-18T00:00:00Z"),
            TO = Instant.parse("2026-08-19T00:00:00Z");

    private MetricContract contract() {
        var plan =
                new RelationalIR.Aggregate(
                        new RelationalIR.Scan("e", 0, 0),
                        List.of(),
                        List.of(
                                new RelationalIR.Measure(
                                        "value",
                                        RelationalIR.AggregateKind.COUNT_DISTINCT,
                                        new RelationalIR.Field("e.user_id"))));
        return new MetricContract(
                new EntityRef("entity.demo.user", "1.0.0"),
                "e.user_id",
                MetricContract.IdentityNullPolicy.REJECT,
                "users",
                TemporalContract.TimeRole.EVENT_TIME,
                ZoneId.of("UTC"),
                List.of(
                        new MetricContract.Source(
                                "e", new EntityRef("function.demo.scan", "1.0.0"))),
                plan,
                List.of(
                        new MetricContract.Output(
                                "value", ObjectType.DataType.INTEGER, "users", false)),
                List.of(),
                MetricContract.EmptyPolicy.NO_RECORD,
                MetricContract.ZeroDivisionPolicy.NULL,
                Optional.of(new MetricContract.Direct("bi.demo.users", "impl-7", HASH)));
    }

    private BiMetricAdapter.Plan plan(int maxRows, int maxBytes, boolean complete) {
        return new BiMetricAdapter.Plan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                HASH,
                "b".repeat(64),
                contract(),
                FROM,
                TO,
                "test-consumer",
                "analysis",
                "c".repeat(64),
                maxRows,
                maxBytes,
                complete);
    }

    private final class Gateway implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();
        private Consumer<ObjectNode> mutation = ignored -> {};
        private String contentType = "application/json";
        private int status = 200;

        Gateway() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/query",
                    exchange -> {
                        try (exchange) {
                            calls.incrementAndGet();
                            ObjectNode request =
                                    json.object(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8));
                            var response =
                                    json.object(
                                            json.write(
                                                    Map.of(
                                                            "protocolVersion",
                                                            1,
                                                            "operationId",
                                                            request.path("operationId"),
                                                            "requestHash",
                                                            json.sha256(request),
                                                            "metric",
                                                            request.path("metric"),
                                                            "planHash",
                                                            request.path("planHash"),
                                                            "capability",
                                                            request.path("capability"),
                                                            "scope",
                                                            request.path("scope"),
                                                            "readiness",
                                                            Map.of(
                                                                    "state",
                                                                    "READY",
                                                                    "observedAt",
                                                                    NOW.toString(),
                                                                    "completeFrom",
                                                                    FROM.toString(),
                                                                    "completeThrough",
                                                                    TO.toString(),
                                                                    "snapshotRef",
                                                                    "reference-snapshot:1",
                                                                    "runRef",
                                                                    "reference-run:1"),
                                                            "result",
                                                            Map.of(
                                                                    "state",
                                                                    "PRESENT",
                                                                    "rows",
                                                                    List.of(Map.of("value", 3))))));
                            mutation.accept(response);
                            byte[] body = json.write(response).getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", contentType);
                            exchange.getResponseHeaders()
                                    .set("Location", "http://127.0.0.1:1/not-followed");
                            exchange.sendResponseHeaders(status, body.length);
                            exchange.getResponseBody().write(body);
                        } catch (java.io.IOException ignored) {
                            /* The bounded subscriber can cancel excessive input. */
                        }
                    });
            server.start();
        }

        BiMetricAdapter adapter() {
            var env =
                    new MockEnvironment()
                            .withProperty(
                                    "AWE_BI_QUERY_URL",
                                    "http://127.0.0.1:" + server.getAddress().getPort())
                            .withProperty(
                                    "AWE_BI_QUERY_BINDINGS",
                                    json.write(
                                            Map.of(
                                                    "bi.demo.users",
                                                    Map.of(
                                                            "capabilityRef",
                                                            "provider.users",
                                                            "implementationVersion",
                                                            "impl-7",
                                                            "implementationHash",
                                                            HASH))));
            return new BiMetricAdapter(env, json, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void missingAndMalformedConfigurationNeverProduceSuccessfulRows() {
        assertEquals(
                "NOT_CONFIGURED",
                new BiMetricAdapter(new MockEnvironment(), json)
                        .execute(plan(10, 4096, false))
                        .status());
        var invalid =
                new MockEnvironment()
                        .withProperty("AWE_BI_QUERY_URL", "http://remote.example/api")
                        .withProperty("AWE_BI_QUERY_BINDINGS", "{broken");
        assertEquals(
                "UNAVAILABLE",
                new BiMetricAdapter(invalid, json).execute(plan(10, 4096, false)).status());
    }

    @Test
    void trustedObservationIsBoundToRequestButStillNotIndependentReconciliation() throws Exception {
        try (var gateway = new Gateway()) {
            var result = gateway.adapter().execute(plan(10, 4096, true));
            assertEquals("QUERIED", result.status());
            assertEquals("READY", result.readiness());
            assertEquals(1, result.rows().size());
            assertEquals("NOT_RUN", result.evidence().get("independentReconciliation"));
            assertEquals("reference-snapshot:1", result.snapshots().get("bi"));
            assertEquals(1, gateway.calls.get());
            assertFalse(json.write(result.evidence()).contains("127.0.0.1"));
        }
    }

    @Test
    void authenticButWrongOperationModelImplementationWindowOrHashIsRejected() throws Exception {
        List<Consumer<ObjectNode>> mutations =
                List.of(
                        r -> r.put("operationId", UUID.randomUUID().toString()),
                        r -> r.put("requestHash", "0".repeat(64)),
                        r -> ((ObjectNode) r.path("metric")).put("contentHash", "0".repeat(64)),
                        r ->
                                ((ObjectNode) r.path("capability"))
                                        .put("implementationVersion", "wrong-attempt"),
                        r -> ((ObjectNode) r.path("scope")).put("to", TO.plusSeconds(1).toString()),
                        r -> r.put("protocolVersion", 4_294_967_297L));
        for (var mutation : mutations)
            try (var gateway = new Gateway()) {
                gateway.mutation = mutation;
                var result = gateway.adapter().execute(plan(10, 4096, false));
                assertEquals("BLOCKED", result.status());
                assertTrue(result.rows().isEmpty());
            }
    }

    @Test
    void incompleteCoverageStaleObservationsAndNotReadyAreNotZero() throws Exception {
        List<Consumer<ObjectNode>> mutations =
                List.of(
                        r ->
                                ((ObjectNode) r.path("readiness"))
                                        .put("completeThrough", TO.minusSeconds(1).toString()),
                        r ->
                                ((ObjectNode) r.path("readiness"))
                                        .put("observedAt", NOW.minusSeconds(301).toString()),
                        r -> {
                            ((ObjectNode) r.path("readiness")).put("state", "NOT_READY");
                            ((ObjectNode) r.path("result"))
                                    .put("state", "NOT_READY")
                                    .putArray("rows");
                        });
        for (var mutation : mutations)
            try (var gateway = new Gateway()) {
                gateway.mutation = mutation;
                var result = gateway.adapter().execute(plan(10, 4096, true));
                assertEquals("NOT_READY", result.status());
                assertTrue(result.rows().isEmpty());
            }
    }

    @Test
    void rowStateTypeGrainAndBudgetCounterexamplesReturnNoPartialData() throws Exception {
        List<Consumer<ObjectNode>> mutations =
                List.of(
                        r -> ((ObjectNode) r.path("result")).put("state", "NO_RECORD"),
                        r -> ((ObjectNode) r.path("result").path("rows").get(0)).put("value", "3"),
                        r -> ((ObjectNode) r.path("result").path("rows").get(0)).put("secret", 4),
                        r ->
                                ((com.fasterxml.jackson.databind.node.ArrayNode)
                                                r.path("result").path("rows"))
                                        .add(json.object("{\"value\":4}")));
        for (var mutation : mutations)
            try (var gateway = new Gateway()) {
                gateway.mutation = mutation;
                var result = gateway.adapter().execute(plan(10, 4096, false));
                assertEquals("BLOCKED", result.status());
                assertTrue(result.rows().isEmpty());
            }
        try (var gateway = new Gateway()) {
            assertEquals("INCONCLUSIVE", gateway.adapter().execute(plan(10, 2, false)).status());
            gateway.mutation =
                    r ->
                            ((com.fasterxml.jackson.databind.node.ArrayNode)
                                            r.path("result").path("rows"))
                                    .add(json.object("{\"value\":4}"));
            assertEquals("INCONCLUSIVE", gateway.adapter().execute(plan(1, 4096, false)).status());
        }
    }

    @Test
    void delegatedRowPolicyRequiresRegisteredPropagationCapability() throws Exception {
        try (var gateway = new Gateway()) {
            var base = plan(10, 4096, false);
            var scoped =
                    new BiMetricAdapter.Plan(
                            base.operationId(),
                            base.metricReleaseId(),
                            base.metricContentHash(),
                            base.planHash(),
                            base.contract(),
                            base.from(),
                            base.to(),
                            base.principal(),
                            base.purpose(),
                            base.policyHash(),
                            base.maxRows(),
                            base.maxBytes(),
                            base.requireComplete(),
                            Map.of(
                                    "e",
                                    new RelationalIR.Compare(
                                            RelationalIR.CompareOp.EQ,
                                            new RelationalIR.Field("e.tenant_id"),
                                            new RelationalIR.Literal(
                                                    ObjectType.DataType.STRING, "tenant-a"))));
            assertEquals("BLOCKED", gateway.adapter().execute(scoped).status());
            assertEquals(0, gateway.calls.get());
        }
    }

    @Test
    void redirectsHtmlAndOversizedBodiesAreRejectedBeforePartialValuesAreAccepted()
            throws Exception {
        try (var gateway = new Gateway()) {
            gateway.status = 302;
            assertEquals("UNAVAILABLE", gateway.adapter().execute(plan(10, 4096, false)).status());
            assertEquals(1, gateway.calls.get());
        }
        try (var gateway = new Gateway()) {
            gateway.contentType = "text/html";
            assertEquals("BLOCKED", gateway.adapter().execute(plan(10, 4096, false)).status());
        }
        try (var gateway = new Gateway()) {
            gateway.mutation =
                    r ->
                            ((ObjectNode) r.path("result").path("rows").get(0))
                                    .put("value", "x".repeat(1_048_576));
            var result = gateway.adapter().execute(plan(10, 4096, false));
            assertEquals("INCONCLUSIVE", result.status());
            assertTrue(result.rows().isEmpty());
        }
    }
}
