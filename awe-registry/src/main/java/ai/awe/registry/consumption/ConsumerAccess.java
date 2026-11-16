package ai.awe.registry.consumption;

import ai.awe.kernel.contract.RelationalIR;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.identity.TrustedOidcVerifier;
import ai.awe.registry.identity.TrustedOidcVerifier.Identity;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-owned data scopes, independently authenticated service or OIDC identity and typed row
 * constraints. A token's role/tenant claims never become self-granted warehouse permissions.
 */
@Component
public final class ConsumerAccess {
    private static final Set<String> REQUIRED_FIELDS =
            Set.of(
                    "principal",
                    "functionIds",
                    "purposes",
                    "fields",
                    "maxRows",
                    "maxBytes",
                    "maxWindowSeconds");
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_BYTES = 1_048_576;

    public record Grant(
            String principal,
            Set<String> functionIds,
            Set<String> purposes,
            Set<String> fields,
            int maxRows,
            int maxBytes,
            long maxWindowSeconds,
            String policyHash,
            List<DatasetRule> rowScopes) {
        public Grant(
                String principal,
                Set<String> functionIds,
                Set<String> purposes,
                Set<String> fields,
                int maxRows,
                int maxBytes,
                long maxWindowSeconds,
                String policyHash) {
            this(
                    principal,
                    functionIds,
                    purposes,
                    fields,
                    maxRows,
                    maxBytes,
                    maxWindowSeconds,
                    policyHash,
                    List.of());
        }

        public Grant {
            functionIds = Set.copyOf(functionIds);
            purposes = Set.copyOf(purposes);
            fields = Set.copyOf(fields);
            rowScopes = List.copyOf(rowScopes);
        }
    }

    public enum RowOperation {
        EQ,
        NE,
        LT,
        LE,
        GT,
        GE,
        IN,
        NOT_IN,
        IS_NULL,
        IS_NOT_NULL
    }

    public record RowFilter(
            String field, RowOperation operation, ObjectType.DataType type, List<String> values) {
        public RowFilter {
            RelationalIR.identifier(field);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(type);
            values = List.copyOf(values);
            if (values.size() > 100) throw invalid();
            if (operation == RowOperation.IS_NULL || operation == RowOperation.IS_NOT_NULL) {
                if (!values.isEmpty()) throw invalid();
            } else if (operation == RowOperation.IN || operation == RowOperation.NOT_IN) {
                if (values.isEmpty()) throw invalid();
            } else if (values.size() != 1) throw invalid();
            values = values.stream().map(v -> new RelationalIR.Literal(type, v).value()).toList();
        }
    }

    public record DatasetRule(String datasetId, Set<String> fields, List<RowFilter> filters) {
        public DatasetRule {
            if (datasetId == null
                    || datasetId.length() > 240
                    || !datasetId.matches("dataset(?:\\.[a-z][a-z0-9_-]*){2,}")) throw invalid();
            fields = Set.copyOf(fields);
            if (fields.isEmpty() || fields.size() > 256) throw invalid();
            fields.forEach(RelationalIR::identifier);
            filters = List.copyOf(filters);
            if (filters.size() > 32) throw invalid();
        }
    }

    public record Authenticated(Grant grant, Identity identity) {}

    private record OidcBinding(
            String issuer,
            String subject,
            String authorizedParty,
            Set<String> delegatedActors,
            Grant grant) {
        private OidcBinding {
            delegatedActors = Set.copyOf(delegatedActors);
        }
    }

    private record Credential(byte[] digest, Grant grant) {}

    private final List<Credential> credentials;
    private final Map<String, Grant> grants;
    private final List<OidcBinding> oidcBindings;
    private final TrustedOidcVerifier oidc;

    public ConsumerAccess(Environment env, JsonDocuments json) {
        List<Credential> parsed = new ArrayList<>();
        Map<String, Grant> byPrincipal = new HashMap<>();
        List<OidcBinding> parsedOidc = new ArrayList<>();
        oidc = new TrustedOidcVerifier(env, json);
        try {
            String raw = env.getProperty("AWE_CONSUMER_POLICIES", "");
            if (raw.isBlank() || raw.length() > 262_144) throw invalid();
            JsonNode policies = json.parse(raw);
            if (!policies.isArray() || policies.isEmpty() || policies.size() > 128) throw invalid();
            Set<String> tokenDigests = new HashSet<>();
            Set<String> bootstrapTokens = new HashSet<>();
            for (String key :
                    List.of(
                            "AWE_AUTHOR_TOKEN",
                            "AWE_REVIEWER_TOKEN",
                            "awe.auth.author-token",
                            "awe.auth.reviewer-token")) {
                String value = env.getProperty(key, "");
                if (!value.isEmpty()) bootstrapTokens.add(value);
            }
            for (JsonNode policy : policies) {
                if (!policy.isObject()) throw invalid();
                Set<String> keys = new HashSet<>();
                policy.fieldNames().forEachRemaining(keys::add);
                if (!keys.containsAll(REQUIRED_FIELDS)) throw invalid();
                var allowed = new HashSet<>(REQUIRED_FIELDS);
                allowed.addAll(List.of("token", "oidc", "rowScopes"));
                if (!allowed.containsAll(keys) || policy.has("token") == policy.has("oidc"))
                    throw invalid();
                String principal = text(policy.path("principal"), 100);
                if (!principal.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                        || principal.startsWith("bootstrap-")) throw invalid();
                byte[] tokenDigest = null;
                if (policy.has("token")) {
                    String token = text(policy.path("token"), 4096);
                    if (!token.matches("[A-Za-z0-9._~+/-]+={0,2}")
                            || bootstrapTokens.contains(token)) throw invalid();
                    tokenDigest = digest(token);
                    if (!tokenDigests.add(HexFormat.of().formatHex(tokenDigest))) throw invalid();
                }
                Set<String> functions = strings(policy.path("functionIds"), 240);
                if (functions.stream()
                        .anyMatch(f -> !f.matches("function(?:\\.[a-z][a-z0-9_-]*){2,}")))
                    throw invalid();
                Set<String> purposes = strings(policy.path("purposes"), 160);
                Set<String> fields = strings(policy.path("fields"), 128);
                if (fields.stream().anyMatch(f -> !f.matches("[A-Za-z_][A-Za-z0-9_]*")))
                    throw invalid();
                int maxRows = (int) positiveInteger(policy.path("maxRows"), MAX_ROWS);
                int maxBytes = (int) positiveInteger(policy.path("maxBytes"), MAX_BYTES);
                long maxWindow = positiveInteger(policy.path("maxWindowSeconds"), Long.MAX_VALUE);
                List<DatasetRule> rowScopes = rowScopes(policy.path("rowScopes"));
                // Canonicalize the authorization scope, never the raw token or its digest.
                var scope =
                        new LinkedHashMap<String, Object>(
                                Map.of(
                                        "principal", principal,
                                        "functionIds", new TreeSet<>(functions),
                                        "purposes", new TreeSet<>(purposes),
                                        "fields", new TreeSet<>(fields),
                                        "maxRows", maxRows,
                                        "maxBytes", maxBytes,
                                        "maxWindowSeconds", maxWindow));
                if (!rowScopes.isEmpty())
                    scope.put(
                            "rowScopes",
                            rowScopes.stream()
                                    .map(
                                            rule ->
                                                    Map.of(
                                                            "datasetId",
                                                            rule.datasetId(),
                                                            "fields",
                                                            new TreeSet<>(rule.fields()),
                                                            "filters",
                                                            rule.filters()))
                                    .toList());
                JsonNode identity = policy.get("oidc");
                String identityIssuer = "", identitySubject = "", party = "";
                Set<String> actors = Set.of();
                if (identity != null) {
                    fields(
                            identity,
                            Set.of("issuer", "subject", "authorizedParty", "delegatedActors"));
                    identityIssuer = text(identity.path("issuer"), 1000);
                    identitySubject = text(identity.path("subject"), 240);
                    if (!oidc.configured() || !identityIssuer.equals(oidc.issuer()))
                        throw invalid();
                    if (identity.has("authorizedParty"))
                        party = text(identity.get("authorizedParty"), 240);
                    if (identity.has("delegatedActors"))
                        actors = strings(identity.get("delegatedActors"), 240);
                    scope.put(
                            "identity",
                            Map.of(
                                    "issuer",
                                    identityIssuer,
                                    "subject",
                                    identitySubject,
                                    "audience",
                                    oidc.audience(),
                                    "authorizedParty",
                                    party,
                                    "delegatedActors",
                                    new TreeSet<>(actors)));
                }
                String hash = json.sha256(json.parse(json.write(scope)));
                Grant grant =
                        new Grant(
                                principal, functions, purposes, fields, maxRows, maxBytes,
                                maxWindow, hash, rowScopes);
                if (byPrincipal.put(principal, grant) != null) throw invalid();
                if (tokenDigest != null) parsed.add(new Credential(tokenDigest, grant));
                else {
                    String finalIssuer = identityIssuer,
                            finalSubject = identitySubject,
                            finalParty = party;
                    if (parsedOidc.stream()
                            .anyMatch(
                                    b ->
                                            b.issuer().equals(finalIssuer)
                                                    && b.subject().equals(finalSubject)
                                                    && (b.authorizedParty().isEmpty()
                                                            || finalParty.isEmpty()
                                                            || b.authorizedParty()
                                                                    .equals(finalParty))))
                        throw invalid();
                    parsedOidc.add(
                            new OidcBinding(identityIssuer, identitySubject, party, actors, grant));
                }
            }
        } catch (RuntimeException invalidConfiguration) {
            // Keep the application available for authoring while every consumption request fails
            // closed. Do not propagate parser exceptions that could contain a credential.
            parsed.clear();
            byPrincipal.clear();
            parsedOidc.clear();
        }
        credentials = List.copyOf(parsed);
        grants = Map.copyOf(byPrincipal);
        oidcBindings = List.copyOf(parsedOidc);
    }

    public Grant authenticate(String bearer) {
        return authenticateContext(bearer).grant();
    }

    public Authenticated authenticateContext(String bearer) {
        if (grants.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Consumer policies are missing or invalid");
        }
        if (bearer == null || bearer.isEmpty() || bearer.length() > 16_384) throw unauthenticated();
        byte[] provided = digest(bearer);
        Grant matched = null;
        for (Credential credential : credentials) {
            if (MessageDigest.isEqual(provided, credential.digest())) matched = credential.grant();
        }
        if (matched != null)
            return new Authenticated(matched, Identity.service(matched.principal()));
        if (oidcBindings.isEmpty()) throw unauthenticated();
        Identity identity = oidc.verify(bearer);
        for (var binding : oidcBindings)
            if (binding.issuer().equals(identity.issuer())
                    && binding.subject().equals(identity.subject())
                    && (binding.authorizedParty().isEmpty()
                            || binding.authorizedParty().equals(identity.authorizedParty()))
                    && binding.delegatedActors().containsAll(identity.delegatedActors()))
                return new Authenticated(binding.grant(), identity);
        throw unauthenticated();
    }

    public void authorize(
            Grant g,
            String functionId,
            String purpose,
            List<String> fields,
            int maxRows,
            int maxBytes,
            Instant from,
            Instant to) {
        authorizeQuery(g, functionId, purpose, fields, maxRows, maxBytes, from, to);
        // 未显式接入强制行条件的调用方必须拒绝，避免静默返回未过滤数据。
        if (!g.rowScopes().isEmpty()) throw denied();
    }

    public void authorizeQuery(
            Grant g,
            String functionId,
            String purpose,
            List<String> fields,
            int maxRows,
            int maxBytes,
            Instant from,
            Instant to) {
        // A caller-created record cannot increase the immutable configured grant.
        if (g == null
                || g.principal() == null
                || !g.equals(grants.get(g.principal()))
                || functionId == null
                || purpose == null
                || !g.functionIds().contains(functionId)
                || !g.purposes().contains(purpose)
                || fields == null
                || fields.isEmpty()
                || fields.stream().anyMatch(Objects::isNull)
                || new HashSet<>(fields).size() != fields.size()
                || !g.fields().containsAll(fields)
                || maxRows <= 0
                || maxRows > g.maxRows()
                || maxBytes <= 0
                || maxBytes > g.maxBytes()
                || from == null
                || to == null
                || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofSeconds(g.maxWindowSeconds()))
                        > 0) {
            throw denied();
        }
    }

    /** 返回绑定实际 Dataset 与别名的服务端强制条件；不存在该源允许规则时失败关闭。 */
    public Optional<RelationalIR.Predicate> scope(
            Grant grant, String datasetId, String alias, ObjectType rowSchema) {
        current(grant);
        RelationalIR.identifier(alias);
        if (grant.rowScopes().isEmpty()) return Optional.empty();
        DatasetRule rule =
                grant.rowScopes().stream()
                        .filter(r -> r.datasetId().equals(datasetId))
                        .findFirst()
                        .orElseThrow(ConsumerAccess::denied);
        var properties = new HashMap<String, ObjectType.Property>();
        rowSchema.properties().forEach(p -> properties.put(p.name(), p));
        if (!properties.keySet().containsAll(rule.fields())) throw denied();
        var terms = new ArrayList<RelationalIR.Predicate>();
        for (var filter : rule.filters()) {
            var property = properties.get(filter.field());
            if (property == null || property.type() != filter.type()) throw denied();
            var field = new RelationalIR.Field(alias + "." + filter.field());
            var values =
                    filter.values().stream()
                            .map(value -> new RelationalIR.Literal(filter.type(), value))
                            .toList();
            terms.add(
                    switch (filter.operation()) {
                        case IS_NULL, IS_NOT_NULL ->
                                new RelationalIR.IsNull(
                                        field, filter.operation() == RowOperation.IS_NOT_NULL);
                        case IN, NOT_IN ->
                                new RelationalIR.In(
                                        field, values, filter.operation() == RowOperation.NOT_IN);
                        default ->
                                new RelationalIR.Compare(
                                        RelationalIR.CompareOp.valueOf(filter.operation().name()),
                                        field,
                                        values.getFirst());
                    });
        }
        if (terms.isEmpty()) return Optional.empty();
        return Optional.of(terms.size() == 1 ? terms.getFirst() : new RelationalIR.All(terms));
    }

    public Set<String> inputFields(Grant grant, String datasetId) {
        current(grant);
        if (grant.rowScopes().isEmpty()) return grant.fields();
        return grant.rowScopes().stream()
                .filter(r -> r.datasetId().equals(datasetId))
                .findFirst()
                .orElseThrow(ConsumerAccess::denied)
                .fields();
    }

    public void requireCurrent(Grant grant) {
        current(grant);
    }

    private void current(Grant grant) {
        if (grant == null
                || grant.principal() == null
                || !grant.equals(grants.get(grant.principal()))) throw denied();
    }

    private static List<DatasetRule> rowScopes(JsonNode array) {
        if (array.isMissingNode()) return List.of();
        if (!array.isArray() || array.isEmpty() || array.size() > 32) throw invalid();
        var result = new ArrayList<DatasetRule>();
        Set<String> datasets = new HashSet<>();
        for (JsonNode entry : array) {
            fields(entry, Set.of("datasetId", "fields", "filters"));
            String id = text(entry.path("datasetId"), 240);
            if (!datasets.add(id)
                    || !entry.path("filters").isArray()
                    || entry.path("filters").size() > 32) throw invalid();
            var filters = new ArrayList<RowFilter>();
            for (var filter : entry.path("filters")) {
                fields(filter, Set.of("field", "operation", "type", "values"));
                if (!filter.path("values").isArray()) throw invalid();
                var values = new ArrayList<String>();
                for (var value : filter.get("values")) {
                    if (!value.isTextual()) throw invalid();
                    values.add(value.asText());
                }
                filters.add(
                        new RowFilter(
                                text(filter.path("field"), 63),
                                RowOperation.valueOf(text(filter.path("operation"), 30)),
                                ObjectType.DataType.valueOf(text(filter.path("type"), 30)),
                                values));
            }
            result.add(new DatasetRule(id, strings(entry.path("fields"), 63), filters));
        }
        return result.stream().sorted(Comparator.comparing(DatasetRule::datasetId)).toList();
    }

    private static void fields(JsonNode object, Set<String> allowed) {
        if (!object.isObject()) throw invalid();
        object.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!allowed.contains(key)) throw invalid();
                        });
    }

    private static Set<String> strings(JsonNode array, int maxLength) {
        if (!array.isArray() || array.isEmpty() || array.size() > 256) throw invalid();
        Set<String> values = new HashSet<>();
        for (JsonNode value : array) if (!values.add(text(value, maxLength))) throw invalid();
        return Set.copyOf(values);
    }

    private static String text(JsonNode value, int maxLength) {
        if (!value.isTextual()) throw invalid();
        String text = value.textValue();
        if (text.isBlank()
                || text.length() > maxLength
                || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    private static long positiveInteger(JsonNode value, long maximum) {
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() <= 0
                || value.longValue() > maximum) throw invalid();
        return value.longValue();
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid consumer policy configuration");
    }

    private static ResponseStatusException unauthenticated() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid consumer credential");
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Consumer policy does not authorize this request");
    }
}
