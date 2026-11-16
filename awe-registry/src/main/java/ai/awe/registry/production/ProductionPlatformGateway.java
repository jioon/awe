package ai.awe.registry.production;

import ai.awe.registry.source.TrustedHttpGateway;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 固定可信工程平台的只读观测接口；请求方不能提交 URL、制品正文或 PASSED。 */
@Component
public final class ProductionPlatformGateway {
    public record Observation(String status, JsonNode document, List<String> limitations) {
        public Observation {
            limitations = List.copyOf(limitations);
        }
    }

    private final TrustedHttpGateway client;
    private final JsonDocuments json;
    private final boolean hasCredential;
    private final String policyRevision;

    public ProductionPlatformGateway(Environment env, JsonDocuments json) {
        this.json = json;
        String token = env.getProperty("AWE_PRODUCTION_PLATFORM_TOKEN", "");
        hasCredential = !token.isBlank();
        client =
                new TrustedHttpGateway(
                        env.getProperty("AWE_PRODUCTION_PLATFORM_URL", ""), token, json);
        policyRevision = env.getProperty("AWE_PRODUCTION_POLICY_REVISION", "1");
        if (!policyRevision.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw new IllegalArgumentException("Invalid production policy revision");
    }

    public boolean configured() {
        return hasCredential && client.configured();
    }

    public String configurationHash() {
        return json.sha256(
                json.parse(
                        json.write(
                                Map.of(
                                        "gateway",
                                        client.configurationHash(),
                                        "authenticated",
                                        hasCredential,
                                        "policyRevision",
                                        policyRevision))));
    }

    public Observation observe(JsonNode request) {
        return fetch("/admissions/observe", request);
    }

    public Observation realization(JsonNode request) {
        return fetch("/admissions/realizations", request);
    }

    private Observation fetch(String path, JsonNode request) {
        if (!hasCredential)
            return new Observation(
                    "NOT_CONFIGURED",
                    json.object("{}"),
                    List.of(
                            "Production platform URL and independent readonly gateway credential are required"));
        var response = client.post(path, request);
        return new Observation(
                response.status(),
                response.document() == null ? json.object("{}") : response.document(),
                response.limitation().isBlank() ? List.of() : List.of(response.limitation()));
    }
}
