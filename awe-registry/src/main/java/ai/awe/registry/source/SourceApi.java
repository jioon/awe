package ai.awe.registry.source;

import ai.awe.registry.JsonDocuments;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/sources", produces = MediaType.APPLICATION_JSON_VALUE)
public final class SourceApi {
    private final SourceService sources;
    private final JsonDocuments json;
    public SourceApi(SourceService sources, JsonDocuments json) { this.sources = sources; this.json = json; }
    @GetMapping("/configuration") public String configuration() { return json.write(sources.configuration()); }
    @PostMapping("/{system}/imports") public String observe(@PathVariable String system, @RequestBody String body, HttpServletRequest request) {
        var req = json.object(body);
        req.fieldNames().forEachRemaining(k -> { if (!Set.of("externalRef").contains(k)) throw new IllegalArgumentException("Unknown import request field"); });
        if (!req.path("externalRef").isTextual()) throw new IllegalArgumentException("externalRef must be text");
        return json.write(sources.observe(system, req.path("externalRef").asText(), (String) request.getAttribute("awe.actor")));
    }
    @GetMapping("/snapshots/{id}") public String snapshot(@PathVariable UUID id) { return json.write(sources.snapshot(id)); }
    @GetMapping("/{system}/snapshots") public String snapshots(@PathVariable String system, @RequestParam String externalRef) {
        return json.write(sources.snapshots(system, externalRef));
    }
    @GetMapping("/{system}/checks") public String checks(@PathVariable String system, @RequestParam String externalRef) {
        return json.write(sources.checks(system, externalRef));
    }
}
