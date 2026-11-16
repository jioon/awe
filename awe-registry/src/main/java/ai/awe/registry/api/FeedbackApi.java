package ai.awe.registry.api;

import ai.awe.registry.knowledge.FeedbackService;
import ai.awe.registry.knowledge.KnowledgeHttp;
import ai.awe.registry.knowledge.ObligationMigrations;
import ai.awe.registry.knowledge.RegressionObservations;
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
    private final ObligationMigrations migrations;
    private final RegressionObservations observations;

    public FeedbackApi(
            FeedbackService feedback,
            JsonDocuments json,
            ObligationMigrations migrations,
            RegressionObservations observations) {
        this.feedback = feedback;
        this.json = json;
        this.migrations = migrations;
        this.observations = observations;
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

    @PostMapping("/regressions/observations")
    public String observe(@RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(
                observations.create(
                        json.object(body),
                        KnowledgeHttp.grant(request),
                        KnowledgeHttp.identity(request)));
    }

    @GetMapping("/regressions/observations/{id}")
    public String observation(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(observations.get(id, KnowledgeHttp.grant(request)));
    }

    @PostMapping("/feedback/{id}/migrations")
    public String proposeMigration(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(migrations.propose(id, json.object(body), KnowledgeHttp.grant(request)));
    }

    @GetMapping("/feedback/{id}/migrations")
    public String migrations(@PathVariable UUID id, HttpServletRequest request) {
        return json.write(migrations.list(id, KnowledgeHttp.grant(request)));
    }

    @GetMapping("/feedback/{id}/migrations/{migrationId}")
    public String migration(
            @PathVariable UUID id, @PathVariable UUID migrationId, HttpServletRequest request) {
        return json.write(migrations.get(id, migrationId, KnowledgeHttp.grant(request)));
    }

    @GetMapping("/feedback/{id}/migrations/{migrationId}/review")
    public String migrationReview(@PathVariable UUID id, @PathVariable UUID migrationId) {
        return json.write(migrations.review(id, migrationId));
    }

    @PostMapping("/feedback/{id}/migrations/{migrationId}/approve")
    public String approveMigration(
            @PathVariable UUID id,
            @PathVariable UUID migrationId,
            @RequestBody String body,
            HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(
                migrations.approve(
                        id, migrationId, json.object(body), KnowledgeHttp.actor(request)));
    }

    @PostMapping("/feedback/{id}/migrations/{migrationId}/resolve")
    public String resolveMigration(
            @PathVariable UUID id,
            @PathVariable UUID migrationId,
            @RequestBody String body,
            HttpServletRequest request) {
        KnowledgeHttp.bounded(body);
        return json.write(
                migrations.resolve(
                        id, migrationId, json.object(body), KnowledgeHttp.actor(request)));
    }
}
