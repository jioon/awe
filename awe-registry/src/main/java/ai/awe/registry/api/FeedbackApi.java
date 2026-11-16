package ai.awe.registry.api;

import ai.awe.registry.knowledge.FeedbackService;
import ai.awe.registry.knowledge.KnowledgeHttp;
import ai.awe.registry.support.JsonDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public final class FeedbackApi {
    private final FeedbackService feedback;
    private final JsonDocuments json;

    public FeedbackApi(FeedbackService feedback, JsonDocuments json) {
        this.feedback = feedback;
        this.json = json;
    }

    @PostMapping("/feedback")
    public String report(@RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(feedback.report(json.object(body), KnowledgeHttp.grant(request)));
    }

    @GetMapping("/feedback")
    public String list(HttpServletRequest request) {
        return json.write(feedback.list(KnowledgeHttp.grant(request)));
    }

    @GetMapping("/feedback/{id}")
    public String get(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(feedback.get(id, KnowledgeHttp.grant(request)));
    }

    @PostMapping("/feedback/{id}/reviews")
    public String review(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(feedback.review(id, json.object(body), KnowledgeHttp.actor(request)));
    }

    @PostMapping("/feedback/{id}/closures")
    public String close(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(feedback.close(id, json.object(body), KnowledgeHttp.actor(request)));
    }

    @PostMapping("/regressions/cases")
    public String proposeCase(@RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(feedback.proposeCase(json.object(body), KnowledgeHttp.grant(request)));
    }

    @GetMapping("/regressions/cases/{id}")
    public String regressionCase(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(feedback.regressionCase(id, KnowledgeHttp.grant(request)));
    }

    @PostMapping("/regressions/cases/{id}/approve")
    public String approveCase(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(feedback.approveCase(id, KnowledgeHttp.actor(request)));
    }

    @PostMapping("/regressions/cases/{id}/replays")
    public String replay(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(
                feedback.replay(
                        id,
                        json.object(body),
                        KnowledgeHttp.grant(request),
                        KnowledgeHttp.identity(request)));
    }

    @GetMapping("/regressions/replays/{id}")
    public String replay(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(feedback.replayRow(id, KnowledgeHttp.grant(request)));
    }
}
