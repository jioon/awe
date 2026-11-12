package ai.awe.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** HTTP contract fixture, not a pretend Hive installation or a production verification claim. */
final class MetadataGatewayFixture implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, JsonNode> assets = new ConcurrentHashMap<>();
    private final JsonDocuments json = new JsonDocuments();

    MetadataGatewayFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start local metadata HTTP contract fixture", e);
        }
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "metadata-contract-fixture");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/assets/", exchange -> {
            try {
                String ref = URLDecoder.decode(exchange.getRequestURI().getRawPath().substring("/assets/".length()), StandardCharsets.UTF_8);
                JsonNode document = assets.get(ref);
                int status = !"GET".equals(exchange.getRequestMethod()) ? 405 : document == null ? 404 : 200;
                byte[] body = (status == 200 ? json.write(document) : "{\"error\":\"fixture asset unavailable\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    void put(String externalRef, JsonNode document) { assets.put(externalRef, document.deepCopy()); }
    @Override public void close() { server.stop(0); }
}
