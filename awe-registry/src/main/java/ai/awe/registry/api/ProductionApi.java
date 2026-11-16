package ai.awe.registry.api;

import ai.awe.registry.production.*;
import ai.awe.registry.support.JsonDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 准入与物化观测 API；原工程平台仍负责实际发布、CAS 与回滚。 */
@RestController
@RequestMapping(value = "/api/production", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ProductionApi {
    private final ProductionAdmissionService admissions;
    private final ProductionRealizations realizations;
    private final JsonDocuments json;

    public ProductionApi(
            ProductionAdmissionService admissions,
            ProductionRealizations realizations,
            JsonDocuments json) {
        this.admissions = admissions;
        this.realizations = realizations;
        this.json = json;
    }

    @GetMapping("/plans/{id}")
    public String plan(@PathVariable UUID id, @RequestParam String contentHash) {
        return json.write(admissions.plan(id, contentHash));
    }

    @PostMapping("/admissions")
    public String prepare(@RequestBody String body, HttpServletRequest request) {
        return json.write(admissions.prepare(body(body), actor(request)));
    }

    @GetMapping("/admissions")
    public String list() {
        return json.write(admissions.list());
    }

    @GetMapping("/admissions/{id}")
    public String get(@PathVariable UUID id) {
        return json.write(admissions.get(id));
    }

    @PostMapping("/admissions/{id}/verify")
    public String verify(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        return json.write(admissions.verify(id, body(body), actor(request)));
    }

    @PostMapping("/admissions/{id}/approve")
    public String approve(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        return json.write(admissions.approve(id, body(body), actor(request)));
    }

    @PostMapping("/admissions/{id}/consume")
    public String consume(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        return json.write(admissions.consume(id, body(body), platform(request)));
    }

    @PostMapping("/admissions/{id}/realizations")
    public String realize(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        return json.write(realizations.realize(id, body(body), platform(request)));
    }

    @GetMapping("/datasets/{id}/realizations")
    public String realizations(@PathVariable UUID id) {
        return json.write(realizations.list(id));
    }

    private com.fasterxml.jackson.databind.JsonNode body(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Production request exceeds 64 KiB");
        return json.object(value);
    }

    private String actor(HttpServletRequest request) {
        return (String) request.getAttribute("awe.actor");
    }

    private PlatformAccess.Grant platform(HttpServletRequest request) {
        return (PlatformAccess.Grant) request.getAttribute("awe.platform");
    }
}
