package ai.awe.registry.source;

import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 固定部署端点的有界 JSON 传输；调用方不能提交 URL、重定向目标或无限响应。 */
public final class TrustedHttpGateway {
    public record Response(String status, int httpStatus, JsonNode document, String limitation) {}

    private final URI base;
    private final String token;
    private final boolean invalid;
    private final JsonDocuments json;
    private final HttpClient client;

    public TrustedHttpGateway(String url, String token, JsonDocuments json) {
        this.json = json;
        this.token = token == null ? "" : token;
        URI parsed = null;
        boolean failed = false;
        if (url != null && !url.isBlank()) {
            try {
                parsed = URI.create(url);
                if (parsed.getHost() == null
                        || parsed.getUserInfo() != null
                        || parsed.getQuery() != null
                        || parsed.getFragment() != null
                        || !("https".equalsIgnoreCase(parsed.getScheme())
                                || "http".equalsIgnoreCase(parsed.getScheme())
                                        && EnvironmentProxySelector.isLoopback(parsed.getHost())))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                parsed = null;
                failed = true;
            }
        }
        base = parsed;
        invalid = failed || this.token.chars().anyMatch(Character::isISOControl);
        client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(new EnvironmentProxySelector(System.getenv()))
                        .build();
    }

    public boolean configured() {
        return base != null && !invalid;
    }

    public String configurationHash() {
        return json.sha256(
                json.parse(
                        json.write(
                                Map.of(
                                        "endpoint",
                                        base == null ? "" : base.toString(),
                                        "configured",
                                        configured()))));
    }

    public Response post(String fixedPath, JsonNode document) {
        return post(fixedPath, document, 1_048_576);
    }

    public Response post(String fixedPath, JsonNode document, int maxBytes) {
        if (invalid)
            return fail(
                    "UNAVAILABLE",
                    0,
                    "Configured gateway endpoint is invalid or does not use verified HTTPS");
        if (!configured())
            return fail("NOT_CONFIGURED", 0, "No trusted execution gateway is configured");
        if (fixedPath == null
                || !fixedPath.matches("/[A-Za-z0-9/_-]{1,200}")
                || maxBytes < 1
                || maxBytes > 1_048_576)
            throw new IllegalArgumentException("Unsupported fixed gateway path or response budget");
        byte[] body = json.canonical(document).getBytes(StandardCharsets.UTF_8);
        if (body.length > 262_144)
            return fail("BLOCKED", 0, "Gateway request exceeded the bounded request budget");
        URI uri = URI.create(base.toString().replaceAll("/+$", "") + fixedPath);
        var request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        var pending = client.sendAsync(request.build(), info -> new Body(maxBytes));
        HttpResponse<byte[]> response;
        try {
            response = pending.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return fail(
                    "UNAVAILABLE", 0, "Gateway operation was interrupted before complete evidence");
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return fail("UNAVAILABLE", 0, "Gateway did not complete within the execution deadline");
        } catch (ExecutionException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause())
                if (cause instanceof Limit)
                    return fail(
                            "INCONCLUSIVE",
                            0,
                            "Gateway response exceeded its byte budget; no partial result is accepted");
            return fail("UNAVAILABLE", 0, "Gateway could not complete using verified transport");
        }
        int code = response.statusCode();
        if (code != 200)
            return fail(
                    code == 401 || code == 403 ? "DENIED" : "UNAVAILABLE",
                    code,
                    "Gateway returned HTTP " + code + "; redirects are never followed");
        if (!response.headers()
                .firstValue("Content-Type")
                .orElse("")
                .split(";", 2)[0]
                .trim()
                .equalsIgnoreCase("application/json"))
            return fail("BLOCKED", code, "Gateway must return application/json");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(response.body()))
                            .toString();
            return new Response("RECEIVED", code, json.object(text), "");
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            return fail("BLOCKED", code, "Gateway returned malformed UTF-8 or unsupported JSON");
        }
    }

    private Response fail(String status, int httpStatus, String limitation) {
        return new Response(status, httpStatus, json.object("{}"), limitation);
    }

    private static final class Limit extends IOException {}

    private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        Body(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new Limit());
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
}
