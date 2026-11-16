package ai.awe.registry.consumption.query;

import ai.awe.kernel.contract.MetricContract;
import ai.awe.kernel.contract.RelationalIR;
import ai.awe.kernel.model.ObjectType;
import ai.awe.registry.model.LogicalDatasetBindings;
import ai.awe.registry.source.TrustedHttpGateway;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 复用已注册 BI 实现；可信网关观测只证明具名范围，不冒充独立数值对账。 */
@Component
public final class BiMetricAdapter {
    public record Plan(
            UUID operationId,
            UUID metricReleaseId,
            String metricContentHash,
            String planHash,
            MetricContract contract,
            Instant from,
            Instant to,
            String principal,
            String purpose,
            String policyHash,
            int maxRows,
            int maxBytes,
            boolean requireComplete,
            Map<String, RelationalIR.Predicate> mandatoryScopes,
            Map<String, BoundQuerySource> sources) {
        public Plan {
            mandatoryScopes = Map.copyOf(mandatoryScopes);
            sources = Map.copyOf(sources);
        }

        public Plan(
                UUID operationId,
                UUID metricReleaseId,
                String metricContentHash,
                String planHash,
                MetricContract contract,
                Instant from,
                Instant to,
                String principal,
                String purpose,
                String policyHash,
                int maxRows,
                int maxBytes,
                boolean requireComplete,
                Map<String, RelationalIR.Predicate> mandatoryScopes) {
            this(
                    operationId,
                    metricReleaseId,
                    metricContentHash,
                    planHash,
                    contract,
                    from,
                    to,
                    principal,
                    purpose,
                    policyHash,
                    maxRows,
                    maxBytes,
                    requireComplete,
                    mandatoryScopes,
                    Map.of());
        }

        public Plan(
                UUID operationId,
                UUID metricReleaseId,
                String metricContentHash,
                String planHash,
                MetricContract contract,
                Instant from,
                Instant to,
                String principal,
                String purpose,
                String policyHash,
                int maxRows,
                int maxBytes,
                boolean requireComplete) {
            this(
                    operationId,
                    metricReleaseId,
                    metricContentHash,
                    planHash,
                    contract,
                    from,
                    to,
                    principal,
                    purpose,
                    policyHash,
                    maxRows,
                    maxBytes,
                    requireComplete,
                    Map.of(),
                    Map.of());
        }
    }

    public record Result(
            String status,
            List<Map<String, Object>> rows,
            Map<String, String> snapshots,
            String readiness,
            Map<String, Object> evidence,
            List<String> limitations) {
        public Result {
            rows =
                    rows.stream()
                            .map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r)))
                            .toList();
            snapshots = Map.copyOf(snapshots);
            evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
            limitations = List.copyOf(limitations);
        }
    }

    private record Binding(
            String capabilityRef,
            String version,
            String hash,
            boolean rowScope,
            String sourceProjectionRef) {}

    private final JsonDocuments json;
    private final TrustedHttpGateway gateway;
    private final Map<String, Binding> bindings;
    private final boolean invalidBindings;
    private final Clock clock;
    private final ObjectProvider<LogicalDatasetBindings> realizations;

    public BiMetricAdapter(Environment environment, JsonDocuments json) {
        this(environment, json, Clock.systemUTC(), null);
    }

    @Autowired
    public BiMetricAdapter(
            Environment environment,
            JsonDocuments json,
            ObjectProvider<LogicalDatasetBindings> realizations) {
        this(environment, json, Clock.systemUTC(), realizations);
    }

    public BiMetricAdapter(Environment environment, JsonDocuments json, Clock clock) {
        this(environment, json, clock, null);
    }

    private BiMetricAdapter(
            Environment environment,
            JsonDocuments json,
            Clock clock,
            ObjectProvider<LogicalDatasetBindings> realizations) {
        this.json = json;
        this.clock = clock;
        this.realizations = realizations;
        gateway =
                new TrustedHttpGateway(
                        environment.getProperty("AWE_BI_QUERY_URL", ""),
                        environment.getProperty("AWE_BI_QUERY_TOKEN", ""),
                        json);
        var parsed = new TreeMap<String, Binding>();
        boolean invalid = false;
        try {
            String raw = environment.getProperty("AWE_BI_QUERY_BINDINGS", "");
            if (!raw.isBlank()) {
                if (raw.length() > 262_144) throw new IllegalArgumentException();
                JsonNode object = json.object(raw);
                if (object.size() > 1000) throw new IllegalArgumentException();
                object.fields()
                        .forEachRemaining(
                                entry -> {
                                    if (!entry.getKey()
                                            .matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}"))
                                        throw new IllegalArgumentException();
                                    JsonNode value = entry.getValue();
                                    fields(
                                            value,
                                            Set.of(
                                                    "capabilityRef",
                                                    "implementationVersion",
                                                    "implementationHash",
                                                    "rowScopeMode",
                                                    "sourceProjectionRef"));
                                    String ref = text(value, "capabilityRef", 500),
                                            version = text(value, "implementationVersion", 500),
                                            hash = digest(value, "implementationHash");
                                    String mode =
                                            value.has("rowScopeMode")
                                                    ? text(value, "rowScopeMode", 40)
                                                    : "NONE";
                                    if (!Set.of("NONE", "PREDICATE_V1").contains(mode))
                                        throw new IllegalArgumentException();
                                    parsed.put(
                                            entry.getKey(),
                                            new Binding(
                                                    ref,
                                                    version,
                                                    hash,
                                                    mode.equals("PREDICATE_V1"),
                                                    value.has("sourceProjectionRef")
                                                            ? text(
                                                                    value,
                                                                    "sourceProjectionRef",
                                                                    500)
                                                            : ""));
                                });
            }
        } catch (RuntimeException ex) {
            parsed.clear();
            invalid = true;
        }
        bindings = Map.copyOf(parsed);
        invalidBindings = invalid;
    }

    public Result execute(Plan plan) {
        try {
            validate(plan);
            if (invalidBindings)
                return failure(
                        "UNAVAILABLE",
                        "Configured BI bindings are invalid; no capability is accepted");
            if (bindings.isEmpty())
                return failure(
                        "NOT_CONFIGURED",
                        "A trusted BI query gateway and implementation binding must be configured");
            var implementation =
                    plan.contract()
                            .direct()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Metric has no DIRECT implementation contract"));
            Binding binding = bindings.get(implementation.bindingRef());
            if (binding == null)
                return failure(
                        "NOT_CONFIGURED",
                        "This published metric has no administrator-registered BI capability binding");
            if (!binding.version().equals(implementation.implementationVersion())
                    || !binding.hash().equals(implementation.implementationHash()))
                return failure(
                        "BLOCKED",
                        "The configured BI implementation version/hash differs from the published metric contract");
            if (!plan.mandatoryScopes().isEmpty() && !binding.rowScope())
                return failure(
                        "BLOCKED",
                        "This BI binding cannot propagate the mandatory delegated row-scope policy");
            PostgresMetricExecutor.currentRealizations(plan.sources(), realizations);
            JsonNode request = request(plan, binding);
            var response = gateway.post("/query", request);
            if (!"RECEIVED".equals(response.status()))
                return failure(
                        "DENIED".equals(response.status()) ? "BLOCKED" : response.status(),
                        response.limitation());
            var result = accept(plan, binding, request, response.document());
            PostgresMetricExecutor.currentRealizations(plan.sources(), realizations);
            return result;
        } catch (IncompleteCoverage ex) {
            return failure("NOT_READY", ex.getMessage());
        } catch (ai.awe.registry.source.postgres.PostgresWarehouse.Failure ex) {
            return failure("BLOCKED", ex.getMessage());
        } catch (IllegalArgumentException | DateTimeException ex) {
            return failure(
                    "BLOCKED",
                    "BI request or response does not satisfy the published exact contract, identity or proof scope");
        }
    }

    private JsonNode request(Plan plan, Binding binding) {
        JsonNode rowScope = rowScope(plan.mandatoryScopes());
        var scanWindows = scanWindows(plan);
        var lineage = lineage(plan);
        String scopeHash =
                json.sha256(
                        json.object(
                                json.write(
                                        Map.of(
                                                "principal",
                                                plan.principal(),
                                                "policyHash",
                                                plan.policyHash(),
                                                "rowScope",
                                                rowScope,
                                                "scanWindows",
                                                scanWindows,
                                                "realizations",
                                                lineage))));
        var body = new LinkedHashMap<String, Object>();
        body.put("protocolVersion", 1);
        body.put("operationId", plan.operationId().toString());
        body.put(
                "metric",
                Map.of(
                        "releaseId",
                        plan.metricReleaseId().toString(),
                        "contentHash",
                        plan.metricContentHash()));
        body.put("planHash", plan.planHash());
        body.put(
                "capability",
                Map.of(
                        "ref",
                        binding.capabilityRef(),
                        "implementationVersion",
                        binding.version(),
                        "implementationHash",
                        binding.hash()));
        body.put(
                "scope",
                Map.of(
                        "from",
                        plan.from().toString(),
                        "to",
                        plan.to().toString(),
                        "businessZone",
                        plan.contract().businessZone().getId(),
                        "view",
                        plan.requireComplete() ? "PERIOD_COMPLETE" : "OBSERVED_SNAPSHOT",
                        "scopeHash",
                        scopeHash));
        body.put(
                "consumer",
                Map.of(
                        "principal",
                        plan.principal(),
                        "purpose",
                        plan.purpose(),
                        "policyHash",
                        plan.policyHash()));
        body.put("budgets", Map.of("maxRows", plan.maxRows(), "maxBytes", plan.maxBytes()));
        if (!binding.sourceProjectionRef().isEmpty())
            body.put("sourceProjectionRef", binding.sourceProjectionRef());
        body.put("rowScope", rowScope);
        body.put("scanWindows", scanWindows);
        body.put("realizations", lineage);
        body.put(
                "outputContractHash",
                json.sha256(
                        json.object(
                                json.write(
                                        Map.of(
                                                "outputs",
                                                plan.contract().outputs(),
                                                "grain",
                                                plan.contract().grain(),
                                                "unit",
                                                plan.contract().unit(),
                                                "emptyPolicy",
                                                plan.contract().emptyPolicy().name())))));
        return json.object(json.write(body));
    }

    private Result accept(Plan plan, Binding binding, JsonNode request, JsonNode response) {
        fields(
                response,
                Set.of(
                        "protocolVersion",
                        "operationId",
                        "requestHash",
                        "metric",
                        "planHash",
                        "capability",
                        "scope",
                        "readiness",
                        "result"));
        if (!response.path("protocolVersion").isIntegralNumber()
                || !response.path("protocolVersion").canConvertToInt()
                || response.path("protocolVersion").intValue() != 1
                || !plan.operationId().toString().equals(text(response, "operationId", 100))
                || !json.sha256(request).equals(digest(response, "requestHash"))
                || !plan.planHash().equals(digest(response, "planHash"))
                || !request.path("metric").equals(response.path("metric"))
                || !request.path("capability").equals(response.path("capability"))
                || !request.path("scope").equals(response.path("scope")))
            throw new IllegalArgumentException("BI observation belongs to another accepted scope");
        JsonNode readiness = response.path("readiness");
        fields(
                readiness,
                Set.of(
                        "state",
                        "observedAt",
                        "completeFrom",
                        "completeThrough",
                        "snapshotRef",
                        "runRef",
                        "scanCoverage",
                        "realizations",
                        "sourceProjectionRef"));
        String state = text(readiness, "state", 40),
                snapshot = text(readiness, "snapshotRef", 1000),
                run = text(readiness, "runRef", 1000);
        if (!Set.of("READY", "NOT_READY", "UNKNOWN").contains(state))
            throw new IllegalArgumentException("Unknown readiness state");
        Instant observedAt = Instant.parse(text(readiness, "observedAt", 100));
        Instant now = clock.instant();
        if (observedAt.isAfter(now.plusSeconds(60)) || observedAt.isBefore(now.minusSeconds(300)))
            return failure(
                    "NOT_READY",
                    "The trusted BI readiness observation is stale or from the future");
        if (state.equals("READY")) {
            Instant completeFrom = Instant.parse(text(readiness, "completeFrom", 100)),
                    completeThrough = Instant.parse(text(readiness, "completeThrough", 100));
            if (completeFrom.isAfter(plan.from())
                    || completeThrough.isBefore(plan.to())
                    || !completeFrom.isBefore(completeThrough))
                return failure(
                        "NOT_READY",
                        "The trusted BI completion coverage does not include the requested window");
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("requestHash", json.sha256(request));
        evidence.put("scopeHash", request.path("scope").path("scopeHash").asText());
        evidence.put("metric", request.path("metric"));
        evidence.put("planHash", plan.planHash());
        evidence.put("implementationVersion", binding.version());
        evidence.put("implementationHash", binding.hash());
        evidence.put("gatewayConfigurationHash", gateway.configurationHash());
        // 投影身份只能由管理员绑定并经可信网关核对；不能从每次变化的 opaque snapshot 猜测。
        if (!binding.sourceProjectionRef().isEmpty()) {
            if (!binding.sourceProjectionRef().equals(text(readiness, "sourceProjectionRef", 500)))
                throw new IllegalArgumentException(
                        "BI source projection differs from the registered binding");
            evidence.put("sourceProjectionRef", binding.sourceProjectionRef());
        } else if (readiness.has("sourceProjectionRef"))
            throw new IllegalArgumentException("Unregistered BI source projection assertion");
        evidence.put("runRef", run);
        evidence.put("snapshotRef", snapshot);
        evidence.put("observedAt", observedAt.toString());
        evidence.put("readiness", state);
        evidence.put("completeFrom", readiness.path("completeFrom").asText());
        evidence.put("completeThrough", readiness.path("completeThrough").asText());
        evidence.put("independentReconciliation", "NOT_RUN");
        JsonNode result = response.path("result");
        fields(result, Set.of("state", "rows", "snapshotRef", "sourceSnapshots"));
        String valueState = text(result, "state", 40);
        evidence.put("valueState", valueState);
        if (!Set.of(
                                "PRESENT",
                                "ZERO",
                                "NO_RECORD",
                                "NOT_READY",
                                "UNKNOWN",
                                "INVALID",
                                "ACCESS_WITHHELD",
                                "NOT_APPLICABLE")
                        .contains(valueState)
                || !result.path("rows").isArray())
            throw new IllegalArgumentException("Unsupported BI result state");
        if (valueState.equals("NOT_APPLICABLE") && !result.path("rows").isEmpty())
            throw new IllegalArgumentException("NOT_APPLICABLE cannot carry invented data rows");
        if (plan.requireComplete() && !state.equals("READY") || valueState.equals("NOT_READY")) {
            if (!result.path("rows").isEmpty())
                throw new IllegalArgumentException("Not-ready result cannot carry partial data");
            return new Result(
                    "NOT_READY",
                    List.of(),
                    Map.of("bi", snapshot),
                    state,
                    evidence,
                    limits("No complete result was provided for the requested data state"));
        }
        if (Set.of("UNKNOWN", "INVALID", "ACCESS_WITHHELD").contains(valueState)) {
            if (!result.path("rows").isEmpty())
                throw new IllegalArgumentException("Unestablished result cannot carry data");
            return new Result(
                    "BLOCKED",
                    List.of(),
                    Map.of("bi", snapshot),
                    state,
                    evidence,
                    limits("BI result did not establish an authorized valid value: " + valueState));
        }
        var snapshots = acceptCoverage(plan, request, readiness, result, snapshot, evidence);
        var rows = new ArrayList<Map<String, Object>>();
        var names = new LinkedHashSet<String>();
        plan.contract().outputs().forEach(o -> names.add(o.name()));
        var grain = new HashSet<List<Object>>();
        int bytes = 2;
        if (bytes > plan.maxBytes())
            return failure(
                    "INCONCLUSIVE", "The result budget cannot represent an empty JSON array");
        for (JsonNode row : result.path("rows")) {
            if (rows.size() >= plan.maxRows())
                return failure(
                        "INCONCLUSIVE",
                        "BI result exceeded the output-row budget; no partial result is returned");
            fields(row, names);
            if (row.size() != names.size())
                throw new IllegalArgumentException("BI result omits a declared output field");
            var normalized = new LinkedHashMap<String, Object>();
            for (var output : plan.contract().outputs())
                normalized.put(output.name(), cell(row.get(output.name()), output));
            var key = new ArrayList<Object>();
            plan.contract()
                    .grain()
                    .forEach(
                            name -> {
                                Object v = normalized.get(name);
                                key.add(v instanceof BigDecimal n ? n.stripTrailingZeros() : v);
                            });
            if (!grain.add(Collections.unmodifiableList(key)))
                throw new IllegalArgumentException("BI result violates the declared grain");
            bytes +=
                    json.canonical(json.parse(json.write(normalized)))
                                    .getBytes(StandardCharsets.UTF_8)
                                    .length
                            + (rows.isEmpty() ? 0 : 1);
            if (bytes > plan.maxBytes())
                return failure(
                        "INCONCLUSIVE",
                        "BI result exceeded the canonical JSON byte budget; no partial result is returned");
            rows.add(normalized);
        }
        if (valueState.equals("NO_RECORD") && !rows.isEmpty()
                || Set.of("PRESENT", "ZERO").contains(valueState) && rows.isEmpty())
            throw new IllegalArgumentException("BI result state and row cardinality disagree");
        if (valueState.equals("ZERO")
                && (plan.contract().outputs().stream().noneMatch(o -> numeric(o.type()))
                        || rows.stream()
                                .anyMatch(
                                        r ->
                                                plan.contract().outputs().stream()
                                                        .filter(o -> numeric(o.type()))
                                                        .anyMatch(
                                                                o ->
                                                                        !(r.get(o.name())
                                                                                        instanceof
                                                                                        Number n)
                                                                                || new BigDecimal(
                                                                                                        n
                                                                                                                .toString())
                                                                                                .signum()
                                                                                        != 0))))
            throw new IllegalArgumentException(
                    "BI ZERO state does not contain zero numeric values");
        return new Result(
                "QUERIED",
                rows,
                snapshots,
                state,
                evidence,
                limits(
                        "The configured BI gateway returned a structurally valid, scope-bound result from the pinned implementation"));
    }

    private void validate(Plan plan) {
        if (plan == null
                || plan.operationId() == null
                || plan.metricReleaseId() == null
                || plan.contract() == null
                || !hash(plan.metricContentHash())
                || !hash(plan.planHash())
                || !hash(plan.policyHash())
                || plan.from() == null
                || plan.to() == null
                || !plan.from().isBefore(plan.to())
                || plan.from().getNano() % 1000 != 0
                || plan.to().getNano() % 1000 != 0
                || plan.maxRows() < 1
                || plan.maxRows() > 100_000
                || plan.maxBytes() < 1
                || plan.maxBytes() > 1_048_576)
            throw new IllegalArgumentException("Invalid BI metric operation");
        bounded(plan.principal(), 100);
        bounded(plan.purpose(), 500);
        if (!plan.contract().sources().stream()
                .map(MetricContract.Source::alias)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(plan.mandatoryScopes().keySet()))
            throw new IllegalArgumentException("Row scope is outside the metric source set");
        if (!plan.sources().isEmpty()
                && !plan.sources()
                        .keySet()
                        .equals(
                                plan.contract().sources().stream()
                                        .map(MetricContract.Source::alias)
                                        .collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException(
                    "BI source context must cover the exact published plan");
    }

    private List<Map<String, Object>> scanWindows(Plan plan) {
        return RelationalSqlCompiler.windows(plan.contract(), plan.from(), plan.to()).stream()
                .map(
                        window -> {
                            var source = plan.sources().get(window.source());
                            boolean snapshot = source != null && source.staticSnapshot();
                            return Map.<String, Object>of(
                                    "source",
                                    window.source(),
                                    "kind",
                                    snapshot ? "STATIC_SNAPSHOT" : "WINDOW",
                                    "from",
                                    snapshot ? "" : window.from().toString(),
                                    "to",
                                    snapshot ? "" : window.to().toString());
                        })
                .toList();
    }

    private Map<String, Object> lineage(Plan plan) {
        var records = new TreeMap<String, Object>();
        for (var source : plan.sources().values())
            source.realization()
                    .ifPresent(
                            proof ->
                                    records.put(
                                            source.alias(),
                                            Map.of(
                                                    "datasetReleaseId",
                                                    proof.datasetReleaseId().toString(),
                                                    "datasetContentHash",
                                                    proof.datasetContentHash(),
                                                    "realizationId",
                                                    proof.realizationId().toString(),
                                                    "consumptionId",
                                                    proof.consumptionId().toString(),
                                                    "artifactHash",
                                                    proof.artifactHash(),
                                                    "deploymentRef",
                                                    proof.deploymentRef(),
                                                    "fingerprint",
                                                    proof.binding().path("fingerprint").asText())));
        return records;
    }

    private Map<String, String> acceptCoverage(
            Plan plan,
            JsonNode request,
            JsonNode readiness,
            JsonNode result,
            String snapshot,
            Map<String, Object> evidence) {
        if (result.has("snapshotRef") && !snapshot.equals(result.path("snapshotRef").asText()))
            throw new IllegalArgumentException(
                    "BI value and readiness refer to different snapshots");
        if (request.path("realizations").size() > 0
                && !request.path("realizations").equals(readiness.path("realizations")))
            throw new IllegalArgumentException(
                    "BI run does not attest the exact logical-source implementations");
        var windows = request.path("scanWindows");
        boolean expanded = windows.size() != 1;
        for (JsonNode window : windows)
            expanded |=
                    !"WINDOW".equals(window.path("kind").asText())
                            || !plan.from().toString().equals(window.path("from").asText())
                            || !plan.to().toString().equals(window.path("to").asText());
        if ("READY".equals(readiness.path("state").asText())
                && (expanded || readiness.has("scanCoverage"))) {
            JsonNode coverage = readiness.path("scanCoverage");
            if (!coverage.isArray())
                throw new IncompleteCoverage(
                        "Ready result lacks actual dependency-window coverage");
            for (JsonNode window : windows) {
                boolean complete = false;
                for (JsonNode observed : coverage) {
                    if (!observed.path("source").equals(window.path("source"))
                            || !observed.path("kind").equals(window.path("kind"))
                            || !snapshot.equals(observed.path("snapshotRef").asText())) continue;
                    if ("STATIC_SNAPSHOT".equals(window.path("kind").asText()))
                        complete =
                                Set.of("OBSERVED", "COMPLETE")
                                        .contains(observed.path("status").asText());
                    else if ("COMPLETE".equals(observed.path("status").asText()))
                        complete =
                                !Instant.parse(observed.path("from").asText())
                                                .isAfter(
                                                        Instant.parse(window.path("from").asText()))
                                        && !Instant.parse(observed.path("to").asText())
                                                .isBefore(
                                                        Instant.parse(window.path("to").asText()));
                    if (complete) break;
                }
                if (!complete)
                    throw new IncompleteCoverage(
                            "Readiness does not establish all actual plan dependency windows");
            }
            evidence.put("scanCoverage", coverage);
        }
        if (result.has("sourceSnapshots"))
            evidence.put("sourceSnapshots", result.path("sourceSnapshots"));
        evidence.put("scanWindows", windows);
        evidence.put("realizations", request.path("realizations"));
        return Map.of("bi", snapshot);
    }

    private static final class IncompleteCoverage extends RuntimeException {
        IncompleteCoverage(String message) {
            super(message);
        }
    }

    private Object cell(JsonNode value, MetricContract.Output output) {
        if (value == null) throw new IllegalArgumentException("Missing output");
        if (value.isNull()) {
            if (!output.nullable())
                throw new IllegalArgumentException("Required metric value is null");
            return null;
        }
        return switch (output.type()) {
            case STRING -> {
                if (!value.isTextual() || value.textValue().length() > 4096)
                    throw new IllegalArgumentException("Unsupported string value");
                yield value.textValue();
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) throw new IllegalArgumentException("Non-boolean value");
                yield value.booleanValue();
            }
            case INTEGER -> {
                if (!value.isIntegralNumber() || value.bigIntegerValue().toString().length() > 4096)
                    throw new IllegalArgumentException("Non-integral or oversized metric value");
                yield value.bigIntegerValue();
            }
            case DECIMAL -> {
                if (!value.isNumber()
                        || value.decimalValue().precision() > 4096
                        || Math.abs((long) value.decimalValue().scale()) > 4096)
                    throw new IllegalArgumentException("Non-numeric or oversized metric value");
                yield value.decimalValue();
            }
            case DATE -> {
                if (!value.isTextual()) throw new IllegalArgumentException("Non-date value");
                LocalDate d = LocalDate.parse(value.textValue());
                if (d.getYear() < 1 || d.getYear() > 9999)
                    throw new IllegalArgumentException("Date outside profile");
                yield d.toString();
            }
            case TIMESTAMP -> {
                if (!value.isTextual()) throw new IllegalArgumentException("Non-timestamp value");
                Instant t = Instant.parse(value.textValue());
                if (t.getNano() % 1000 != 0
                        || t.atOffset(ZoneOffset.UTC).getYear() < 1
                        || t.atOffset(ZoneOffset.UTC).getYear() > 9999)
                    throw new IllegalArgumentException("Timestamp outside exact profile");
                yield t.toString();
            }
            default -> throw new IllegalArgumentException("Unsupported metric cell");
        };
    }

    private static boolean numeric(ObjectType.DataType type) {
        return type == ObjectType.DataType.INTEGER || type == ObjectType.DataType.DECIMAL;
    }

    private Result failure(String status, String reason) {
        return new Result(status, List.of(), Map.of(), "UNKNOWN", Map.of(), limits(reason));
    }

    private List<String> limits(String note) {
        return List.of(
                note,
                "Configured gateway observations attest only the exact model, implementation, request window and data snapshot; they are not independent numerical reconciliation",
                "No overall business-task success is asserted and gateway snapshot references do not promise historical restoration");
    }

    private static boolean hash(String s) {
        return s != null && s.matches("[a-f0-9]{64}");
    }

    private static String digest(JsonNode node, String key) {
        String value = text(node, key, 64);
        if (!hash(value)) throw new IllegalArgumentException("Expected SHA-256");
        return value;
    }

    private static String text(JsonNode node, String key, int max) {
        if (!node.path(key).isTextual()) throw new IllegalArgumentException("Text field required");
        String value = node.path(key).asText();
        bounded(value, max);
        return value;
    }

    private static void bounded(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid bounded value");
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new IllegalArgumentException("Object required");
        node.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!allowed.contains(k))
                                throw new IllegalArgumentException("Unknown gateway field");
                        });
    }

    public JsonNode rowScope(Map<String, RelationalIR.Predicate> scopes) {
        var document = new TreeMap<String, Object>();
        scopes.forEach((alias, p) -> document.put(alias, predicate(p)));
        return json.object(json.write(document));
    }

    private static Map<String, Object> predicate(RelationalIR.Predicate predicate) {
        return switch (predicate) {
            case RelationalIR.Compare c ->
                    Map.of(
                            "kind",
                            "COMPARE",
                            "operation",
                            c.operation().name(),
                            "left",
                            expression(c.left()),
                            "right",
                            expression(c.right()));
            case RelationalIR.All a ->
                    Map.of(
                            "kind",
                            "ALL",
                            "terms",
                            a.terms().stream().map(BiMetricAdapter::predicate).toList());
            case RelationalIR.Any a ->
                    Map.of(
                            "kind",
                            "ANY",
                            "terms",
                            a.terms().stream().map(BiMetricAdapter::predicate).toList());
            case RelationalIR.Not n -> Map.of("kind", "NOT", "input", predicate(n.input()));
            case RelationalIR.IsNull n ->
                    Map.of(
                            "kind",
                            "IS_NULL",
                            "input",
                            expression(n.input()),
                            "negated",
                            n.negated());
            case RelationalIR.In in ->
                    Map.of(
                            "kind",
                            "IN",
                            "input",
                            expression(in.input()),
                            "values",
                            in.values().stream().map(BiMetricAdapter::expression).toList(),
                            "negated",
                            in.negated());
        };
    }

    private static Map<String, Object> expression(RelationalIR.Expr expression) {
        return switch (expression) {
            case RelationalIR.Field f -> Map.of("kind", "FIELD", "name", f.name());
            case RelationalIR.Literal l ->
                    Map.of("kind", "LITERAL", "type", l.type().name(), "value", l.value());
            case RelationalIR.BusinessDate d ->
                    Map.of("kind", "BUSINESS_DATE", "input", expression(d.input()));
            case RelationalIR.Arithmetic a ->
                    Map.of(
                            "kind",
                            "ARITHMETIC",
                            "operation",
                            a.operation().name(),
                            "left",
                            expression(a.left()),
                            "right",
                            expression(a.right()));
        };
    }
}
