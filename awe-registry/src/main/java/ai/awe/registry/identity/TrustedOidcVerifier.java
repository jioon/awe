package ai.awe.registry.identity;

import ai.awe.registry.source.EnvironmentProxySelector;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;

/** 只从部署方固定的信任根验证身份；签名 claims 也不能自行授予数据权限。 */
public final class TrustedOidcVerifier {
    private static final int MAX_JWKS_BYTES = 262_144;
    private final JsonDocuments json;
    private final String issuer, audience;
    private final NimbusJwtDecoder decoder;
    private final boolean configured;

    public record Identity(
            String mechanism,
            String issuer,
            String subject,
            String authorizedParty,
            List<String> delegatedActors,
            Instant expiresAt) {
        public Identity {
            delegatedActors = List.copyOf(delegatedActors);
        }

        public Map<String, Object> toEvidence() {
            var result = new LinkedHashMap<String, Object>();
            result.put("mechanism", mechanism);
            result.put("issuer", issuer);
            result.put("subject", subject);
            result.put("authorizedParty", authorizedParty);
            result.put("delegatedActors", delegatedActors);
            if (expiresAt != null) result.put("expiresAt", expiresAt.toString());
            return Collections.unmodifiableMap(result);
        }

        public static Identity service(String principal) {
            return new Identity(
                    "SERVICE_TOKEN",
                    "awe:configured-service-policies",
                    principal,
                    "",
                    List.of(),
                    null);
        }
    }

    public TrustedOidcVerifier(Environment environment, JsonDocuments json) {
        this.json = json;
        issuer = environment.getProperty("AWE_OIDC_ISSUER", "").trim();
        audience = environment.getProperty("AWE_OIDC_AUDIENCE", "").trim();
        NimbusJwtDecoder built = null;
        boolean ready = false;
        try {
            String raw = environment.getProperty("AWE_OIDC_JWKS_JSON", "").trim();
            String url = environment.getProperty("AWE_OIDC_JWKS_URL", "").trim();
            if (issuer.isBlank()
                    || audience.isBlank()
                    || audience.length() > 500
                    || audience.chars().anyMatch(Character::isISOControl)
                    || raw.isBlank() == url.isBlank()) throw new IllegalArgumentException();
            endpoint(issuer);
            JWKSource<SecurityContext> source;
            if (!raw.isBlank()) {
                JWKSet keys = keys(raw);
                source = (selector, context) -> selector.select(keys);
            } else {
                source = new RemoteKeys(endpoint(url));
            }
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
            processor.setJWSTypeVerifier(
                    (type, context) -> {
                        if (type != null && !Set.of("JWT", "at+jwt").contains(type.toString()))
                            throw new BadJOSEException("Unsupported identity token type");
                    });
            // 时间、issuer、audience 统一由具名 Spring validators 校验，不能只验证 RSA 签名。
            processor.setJWTClaimsSetVerifier((claims, context) -> {});
            built = new NimbusJwtDecoder(processor);
            built.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(
                            new JwtTimestampValidator(Duration.ofSeconds(30)),
                            new JwtIssuerValidator(issuer),
                            jwt ->
                                    jwt.getExpiresAt() != null
                                                    && jwt.getSubject() != null
                                                    && !jwt.getSubject().isBlank()
                                                    && jwt.getAudience().contains(audience)
                                            ? OAuth2TokenValidatorResult.success()
                                            : OAuth2TokenValidatorResult.failure(
                                                    new OAuth2Error(
                                                            "invalid_token",
                                                            "Required identity scope is absent",
                                                            null))));
            ready = true;
        } catch (RuntimeException invalidConfiguration) {
            // 缺失/无效信任配置不影响建模，但不能通过身份认证；不把配置异常或密钥内容泄露出去。
        }
        decoder = built;
        configured = ready;
    }

    public boolean configured() {
        return configured;
    }

    public String issuer() {
        return issuer;
    }

    public String audience() {
        return audience;
    }

    public Identity verify(String token) {
        if (!configured) throw unavailable();
        try {
            if (token == null || token.length() > 16_384) throw invalid();
            String[] pieces = token.split("\\.", -1);
            if (pieces.length != 3
                    || Arrays.stream(pieces)
                            .anyMatch(p -> p.isEmpty() || !p.matches("[A-Za-z0-9_-]+")))
                throw invalid();
            JsonNode header =
                    json.object(
                            new String(
                                    Base64.getUrlDecoder().decode(pieces[0]),
                                    StandardCharsets.UTF_8));
            JsonNode claims =
                    json.object(
                            new String(
                                    Base64.getUrlDecoder().decode(pieces[1]),
                                    StandardCharsets.UTF_8));
            if (!"RS256".equals(header.path("alg").asText())
                    || !boundedText(header.path("kid"), 128)
                    || header.has("crit")
                    || header.has("jku")
                    || header.has("jwk")
                    || header.has("x5u")
                    || header.has("b64")
                    || !boundedText(claims.path("sub"), 240)
                    || !claims.path("exp").isIntegralNumber()
                    || !claims.path("exp").canConvertToLong()) throw invalid();
            for (String time : List.of("nbf", "iat"))
                if (claims.has(time)
                        && (!claims.get(time).isIntegralNumber()
                                || !claims.get(time).canConvertToLong())) throw invalid();
            if (claims.has("iat")
                    && claims.get("iat").longValue()
                            > Instant.now().plusSeconds(30).getEpochSecond()) throw invalid();
            Jwt verified = decoder.decode(token);
            String party = "";
            if (claims.has("azp")) {
                if (!boundedText(claims.get("azp"), 240)) throw invalid();
                party = claims.get("azp").asText();
            }
            // act 链来自签名后的 token，仍须在对应服务端 policy 中逐项允许后才可委托。
            var actors = new ArrayList<String>();
            JsonNode actor = claims.get("act");
            while (actor != null) {
                if (!actor.isObject()
                        || actors.size() >= 3
                        || !boundedText(actor.path("sub"), 240)
                        || actor.has("iss") && !issuer.equals(actor.path("iss").asText()))
                    throw invalid();
                actors.add(actor.path("sub").asText());
                actor = actor.get("act");
            }
            return new Identity(
                    "OIDC_JWT",
                    issuer,
                    verified.getSubject(),
                    party,
                    actors,
                    verified.getExpiresAt());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (JwtException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause())
                if (cause instanceof KeysUnavailable) throw unavailable();
            throw invalid();
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private JWKSet keys(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_JWKS_BYTES)
            throw new IllegalArgumentException();
        JsonNode document = json.object(raw);
        if (document.size() != 1
                || !document.path("keys").isArray()
                || document.path("keys").isEmpty()
                || document.path("keys").size() > 32) throw new IllegalArgumentException();
        try {
            JWKSet result = JWKSet.parse(json.canonical(document));
            Set<String> ids = new HashSet<>();
            for (JWK key : result.getKeys()) {
                if (!(key instanceof RSAKey rsa)
                        || key.isPrivate()
                        || key.getKeyID() == null
                        || key.getKeyID().isBlank()
                        || key.getKeyID().length() > 128
                        || !ids.add(key.getKeyID())
                        || key.getAlgorithm() != null
                                && !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                        || key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())
                        || key.getKeyOperations() != null
                                && !key.getKeyOperations().isEmpty()
                                && !Set.of(KeyOperation.VERIFY).equals(key.getKeyOperations()))
                    throw new IllegalArgumentException();
                int bits = rsa.toRSAPublicKey().getModulus().bitLength();
                if (bits < 2048 || bits > 8192) throw new IllegalArgumentException();
            }
            return result;
        } catch (java.text.ParseException | JOSEException ex) {
            throw new IllegalArgumentException("Invalid verification keys");
        }
    }

    private static URI endpoint(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme())
                                && EnvironmentProxySelector.isLoopback(uri.getHost())))
            throw new IllegalArgumentException();
        return uri;
    }

    private static boolean boundedText(JsonNode node, int limit) {
        return node.isTextual()
                && !node.asText().isBlank()
                && node.asText().length() <= limit
                && node.asText().chars().noneMatch(Character::isISOControl);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Invalid consumer identity credential");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Trusted consumer identity verification is not configured or available");
    }

    private final class RemoteKeys implements JWKSource<SecurityContext> {
        private final URI url;
        private final HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(new EnvironmentProxySelector(System.getenv()))
                        .build();
        private JWKSet cached;
        private Instant expires = Instant.EPOCH, lastAttempt = Instant.EPOCH;

        RemoteKeys(URI url) {
            this.url = url;
        }

        @Override
        public synchronized List<JWK> get(
                com.nimbusds.jose.jwk.JWKSelector selector, SecurityContext context) {
            Instant now = Instant.now();
            boolean expired = !now.isBefore(expires);
            boolean unknown = cached == null || selector.select(cached).isEmpty();
            if (expired
                    || unknown
                            && Duration.between(lastAttempt, now).compareTo(Duration.ofSeconds(5))
                                    >= 0) {
                if (Duration.between(lastAttempt, now).compareTo(Duration.ofSeconds(5)) < 0) {
                    if (expired) throw new KeysUnavailable();
                } else {
                    lastAttempt = now;
                    var request =
                            HttpRequest.newBuilder(url)
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Accept", "application/json")
                                    .GET()
                                    .build();
                    var pending = client.sendAsync(request, ignored -> new BoundedBody());
                    try {
                        var response = pending.get(10, TimeUnit.SECONDS);
                        String mediaType =
                                response.headers()
                                        .firstValue("Content-Type")
                                        .orElse("")
                                        .split(";", 2)[0]
                                        .trim()
                                        .toLowerCase(Locale.ROOT);
                        if (response.statusCode() != 200
                                || !Set.of("application/json", "application/jwk-set+json")
                                        .contains(mediaType)) throw new KeysUnavailable();
                        cached = keys(new String(response.body(), StandardCharsets.UTF_8));
                        expires = Instant.now().plusSeconds(60);
                    } catch (InterruptedException ex) {
                        pending.cancel(true);
                        Thread.currentThread().interrupt();
                        throw new KeysUnavailable();
                    } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                        pending.cancel(true);
                        throw new KeysUnavailable();
                    }
                }
            }
            if (cached == null || !Instant.now().isBefore(expires)) throw new KeysUnavailable();
            return selector.select(cached);
        }
    }

    private static final class KeysUnavailable extends RuntimeException {}

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> values) {
            for (var value : values) {
                if (value.remaining() > MAX_JWKS_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IOException("Verification key response exceeded its budget"));
                    return;
                }
                byte[] chunk = new byte[value.remaining()];
                value.get(chunk);
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
