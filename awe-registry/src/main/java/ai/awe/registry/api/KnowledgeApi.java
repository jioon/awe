package ai.awe.registry.api;

import ai.awe.registry.consumption.DataTaskOperations;
import ai.awe.registry.knowledge.KnowledgeHttp;
import ai.awe.registry.knowledge.KnowledgeService;
import ai.awe.registry.support.JsonDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public final class KnowledgeApi {
    private final KnowledgeService knowledge;
    private final DataTaskOperations operations;
    private final JsonDocuments json;

    public KnowledgeApi(
            KnowledgeService knowledge, DataTaskOperations operations, JsonDocuments json) {
        this.knowledge = knowledge;
        this.operations = operations;
        this.json = json;
    }

    @PostMapping("/knowledge/resolve")
    public String resolve(@RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(knowledge.resolve(json.object(body), KnowledgeHttp.grant(request)));
    }

    @PostMapping("/knowledge/assess")
    public String assess(@RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(knowledge.assess(json.object(body), KnowledgeHttp.grant(request)));
    }

    @GetMapping("/knowledge/observations/{id}")
    public String observation(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(knowledge.observation(id, KnowledgeHttp.grant(request)));
    }

    @GetMapping("/knowledge/coverage")
    public String coverage(HttpServletRequest request) {
        return json.write(knowledge.coverage(KnowledgeHttp.grant(request)));
    }

    @PostMapping("/knowledge/coverage")
    public String propose(@RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(
                knowledge.proposeCoverage(json.object(body), KnowledgeHttp.actor(request)));
    }

    @PostMapping("/knowledge/coverage/{id}/approve")
    public String approve(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(knowledge.approveCoverage(id, KnowledgeHttp.actor(request)));
    }

    @PostMapping("/semantic-plans/validate")
    public String validate(@RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(operations.validatePlan(json.object(body), KnowledgeHttp.grant(request)));
    }
}
