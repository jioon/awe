package ai.awe.registry.source;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * Real HTTP metadata gateway, shared by Hive/BI while native adapters can implement SourceAdapter.
 */
public final class HttpMetadataAdapter implements SourceAdapter {
    private static final int MAX_BYTES = 1_048_576;
    private static final Set<String> FIELDS =
            Set.of(
                    "externalRef",
                    "sourceVersion",
                    "columns",
                    "grainKeys",
                    "timeFields",
                    "definition");
    private final String system, token;
    private final URI base;
    private final boolean invalidConfiguration;
    private final JsonDocuments json;
    private final HttpClient client;

    public HttpMetadataAdapter(String system, String url, String token, JsonDocuments json) {
        this.system = system;
        this.token = token == null ? "" : token;
        this.json = json;
        URI parsed = null;
        boolean invalid = false;
        if (url != null && !url.isBlank()) {
            try {
                parsed = URI.create(url);
                if (parsed.getHost() == null
                        || parsed.getUserInfo() != null
                        || parsed.getQuery() != null
                        || parsed.getFragment() != null
                        || !("https".equalsIgnoreCase(parsed.getScheme())
                                || ("http".equalsIgnoreCase(parsed.getScheme())
                                        && EnvironmentProxySelector.isLoopback(parsed.getHost()))))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                parsed = null;
                invalid = true;
            }
        }
        this.base = parsed;
        this.invalidConfiguration = invalid;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(new EnvironmentProxySelector(System.getenv()))
                        .build();
    }

    @Override
    public String system() {
        return system;
    }

    @Override
    public boolean configured() {
        return base != null;
    }

    @Override
    public Fetch fetch(String externalRef) {
        if (invalidConfiguration)
            return Fetch.unavailable(
                    "UNAVAILABLE",
                    "Metadata gateway configuration is invalid; use HTTPS (HTTP is allowed only on loopback)");
        if (!configured())
            return Fetch.unavailable(
                    "NOT_CONFIGURED",
                    "No authoritative metadata gateway is configured for " + system);
        String path =
                base.toString().replaceAll("/+$", "")
                        + "/assets/"
                        + URLEncoder.encode(externalRef, StandardCharsets.UTF_8)
                                .replace("+", "%20");
        try {
            var request =
                    HttpRequest.newBuilder(URI.create(path))
                            .GET()
                            .timeout(Duration.ofSeconds(10))
                            .header("Accept", "application/json");
            if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
            var pending =
                    client.sendAsync(request.build(), info -> new LimitedBodySubscriber(MAX_BYTES));
            HttpResponse<byte[]> response;
            try {
                response = pending.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException | InterruptedException e) {
                pending.cancel(true);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return Fetch.unavailable(
                        "UNAVAILABLE",
                        "Metadata gateway did not complete within the observation deadline");
            } catch (ExecutionException e) {
                for (Throwable cause = e; cause != null; cause = cause.getCause())
                    if (cause instanceof ResponseLimitException)
                        return Fetch.unavailable(
                                "INVALID_RESPONSE", "Metadata response exceeds the 1 MiB limit");
                return Fetch.unavailable(
                        "UNAVAILABLE",
                        "Metadata gateway could not be reached with verified transport");
            }
            {
                int status = response.statusCode();
                if (status == 404)
                    return Fetch.unavailable(
                            "NOT_FOUND",
                            "The configured gateway did not return this asset; no claim about enterprise-wide coverage is made");
                if (status != 200)
                    return Fetch.unavailable(
                            "UNAVAILABLE",
                            "Configured metadata gateway returned HTTP "
                                    + status
                                    + "; redirects are not followed");
                if (!response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .split(";", 2)[0]
                        .trim()
                        .equalsIgnoreCase("application/json"))
                    return Fetch.unavailable(
                            "INVALID_RESPONSE", "Metadata gateway must return application/json");
                byte[] bytes = response.body();
                if (bytes.length > MAX_BYTES)
                    return Fetch.unavailable(
                            "INVALID_RESPONSE", "Metadata response exceeds the 1 MiB limit");
                JsonNode normalized =
                        normalize(
                                json.object(new String(bytes, StandardCharsets.UTF_8)),
                                externalRef);
                return new Fetch(
                        "IMPORTED",
                        normalized.path("sourceVersion").asText(),
                        normalized,
                        List.of(
                                "Metadata/schema observation only; row uniqueness, data quality and business truth are not verified"));
            }
        } catch (IllegalArgumentException e) {
            return Fetch.unavailable(
                    "INVALID_RESPONSE",
                    "Metadata gateway returned an invalid or unsupported asset document");
        }
    }

    private static final class ResponseLimitException extends IOException {}

    /**
     * Cancels before buffering unbounded input; completion is also bounded by the outer deadline.
     */
    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseLimitException());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    /** A single canonical fingerprint regardless of JSON object/column/key ordering. */
    private JsonNode normalize(ObjectNode node, String requestedRef) {
        node.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!FIELDS.contains(k)) throw new IllegalArgumentException();
                        });
        requireText(node, "externalRef", 500);
        requireText(node, "sourceVersion", 250);
        if (!requestedRef.equals(node.path("externalRef").asText()))
            throw new IllegalArgumentException();
        if (!node.path("columns").isArray()
                || node.path("columns").isEmpty()
                || node.path("columns").size() > 10000) throw new IllegalArgumentException();
        var columns = new TreeMap<String, JsonNode>();
        for (JsonNode column : node.path("columns")) {
            if (!column.isObject()
                    || column.size() != 3
                    || !column.has("name")
                    || !column.has("type")
                    || !column.has("nullable")) throw new IllegalArgumentException();
            requireText(column, "name", 250);
            requireText(column, "type", 250);
            if (!column.path("nullable").isBoolean()) throw new IllegalArgumentException();
            ObjectNode normalized = ((ObjectNode) column).deepCopy();
            normalized.put("type", normalized.path("type").asText().toLowerCase(Locale.ROOT));
            if (columns.put(column.path("name").asText(), normalized) != null)
                throw new IllegalArgumentException();
        }
        ArrayNode orderedColumns = node.arrayNode();
        columns.values().forEach(orderedColumns::add);
        node.set("columns", orderedColumns);
        for (String field : List.of("grainKeys", "timeFields")) {
            if (!node.path(field).isArray()) throw new IllegalArgumentException();
            var values = new TreeSet<String>();
            for (JsonNode value : node.path(field)) {
                if (!value.isTextual()
                        || value.asText().isBlank()
                        || !columns.containsKey(value.asText())
                        || !values.add(value.asText())) throw new IllegalArgumentException();
            }
            ArrayNode sorted = node.arrayNode();
            values.forEach(sorted::add);
            node.set(field, sorted);
        }
        if (node.has("definition") && !node.path("definition").isObject())
            throw new IllegalArgumentException();
        return json.parse(json.canonical(node));
    }

    private static void requireText(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
            throw new IllegalArgumentException();
    }
}
