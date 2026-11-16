package ai.awe.registry.production;

import ai.awe.kernel.model.ContractDefinition;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

/** 义务由目标模型、已发布契约和完整影响闭包派生，不接受调用方挑选容易通过的子集。 */
@Component
public final class ProductionObligations {
    public static final Set<String> RULES =
            Set.of(
                    "check.pipeline.output.schema",
                    "check.pipeline.output.grain",
                    "check.pipeline.result.equivalence",
                    "check.pipeline.consumer.regression");
    private final ProductionModels models;
    private final JsonDocuments json;
    private final TypedModels typed = new TypedModels();

    public ProductionObligations(ProductionModels models, JsonDocuments json) {
        this.models = models;
        this.json = json;
    }

    public List<Map<String, Object>> derive(
            ProductionModels.Plan plan,
            ProductionImpactGraph.Snapshot impact,
            ProductionRegressionSuites.Suite suite) {
        var byScope = new TreeMap<String, Map<String, Object>>();
        for (var target : plan.targets())
            for (String rule :
                    List.of(
                            "check.pipeline.output.schema",
                            "check.pipeline.output.grain",
                            "check.pipeline.result.equivalence"))
                add(byScope, rule, ProductionModels.pointer(target.dataset()), plan, impact, suite);
        for (var affected : impact.releases())
            add(byScope, "check.pipeline.consumer.regression", affected, plan, impact, suite);
        for (var reference : plan.contract().requiredContracts()) {
            var release = models.dependency(plan.pipeline(), reference, "ContractDefinition");
            var contract =
                    (ContractDefinition)
                            typed.decode("ContractDefinition", (JsonNode) release.get("spec"));
            var subject =
                    plan.dependencies().stream()
                            .filter(
                                    r ->
                                            contract.subject().id().equals(r.get("entity_id"))
                                                    && contract.subject()
                                                            .modelVersion()
                                                            .equals(r.get("version")))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Required verification contract subject is outside the fixed dependency closure"));
            boolean executed = false;
            for (var obligation : contract.obligations())
                if (obligation.at() == ContractDefinition.Enforcement.EXECUTE
                        && obligation.blocking()) {
                    if (!RULES.contains(obligation.ruleId()))
                        throw new IllegalArgumentException(
                                "Production admission has no checker for blocking rule: "
                                        + obligation.ruleId());
                    add(
                            byScope,
                            obligation.ruleId(),
                            ProductionModels.pointer(subject),
                            plan,
                            impact,
                            suite);
                    executed = true;
                }
            if (!executed)
                throw new IllegalArgumentException(
                        "Required production contract has no supported blocking EXECUTE obligation");
        }
        if (byScope.size() > 2048)
            throw new IllegalStateException(
                    "Production regression obligation set exceeds the complete verification budget");
        return List.copyOf(byScope.values());
    }

    private void add(
            Map<String, Map<String, Object>> result,
            String rule,
            Map<String, Object> subject,
            ProductionModels.Plan plan,
            ProductionImpactGraph.Snapshot impact,
            ProductionRegressionSuites.Suite suite) {
        var scope = new TreeMap<String, Object>();
        for (String key : List.of("releaseId", "entityId", "modelVersion", "contentHash"))
            scope.put(key, subject.get(key));
        scope.put("ruleId", rule);
        scope.put("artifactHash", plan.artifactHash());
        scope.put("pipeline", ProductionModels.pointer(plan.pipeline()));
        scope.put("impactHash", impact.hash());
        scope.put("suite", suite.descriptor());
        String hash = json.sha256(json.parse(json.write(scope)));
        result.putIfAbsent(
                hash,
                Map.of(
                        "id",
                        "obligation:" + hash,
                        "scopeHash",
                        hash,
                        "ruleId",
                        rule,
                        "subject",
                        subject,
                        "independentRequired",
                        rule.equals("check.pipeline.result.equivalence")
                                || rule.equals("check.pipeline.consumer.regression")));
    }
}
