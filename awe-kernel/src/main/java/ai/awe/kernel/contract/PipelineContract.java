package ai.awe.kernel.contract;

import ai.awe.kernel.identity.EntityRef;
import java.util.*;

/** 加工意图与正式输入输出，不包含代码编辑、作业调度或发布协作步骤。 */
public record PipelineContract(
        List<MetricContract.Source> sources,
        List<Target> targets,
        Set<EntityRef> requiredContracts,
        Mode mode) {
    public enum Mode {
        MODEL_COMPILED,
        REGISTERED_EXTERNAL
    }

    public record Target(EntityRef dataset, EntityRef rowSchema, RelationalIR.Node plan) {
        public Target {
            Objects.requireNonNull(dataset);
            Objects.requireNonNull(rowSchema);
            RelationalIR.validate(plan);
        }
    }

    public PipelineContract {
        sources = List.copyOf(sources);
        targets = List.copyOf(targets);
        requiredContracts = Set.copyOf(requiredContracts);
        Objects.requireNonNull(mode);
        if (sources.isEmpty() || sources.size() > 8 || targets.isEmpty() || targets.size() > 8)
            throw new IllegalArgumentException("Pipeline source/target budget exceeded");
        var aliases = new HashSet<String>();
        for (var source : sources)
            if (!aliases.add(source.alias()))
                throw new IllegalArgumentException("Duplicate pipeline source alias");
        var targetRefs = new HashSet<EntityRef>();
        for (var target : targets) {
            if (!targetRefs.add(target.dataset()))
                throw new IllegalArgumentException("Duplicate pipeline target Dataset");
            if (!aliases.containsAll(RelationalIR.sources(target.plan())))
                throw new IllegalArgumentException("Pipeline plan has an undeclared source");
        }
        if (requiredContracts.isEmpty() || requiredContracts.size() > 64)
            throw new IllegalArgumentException(
                    "Pipeline needs bounded explicit validation contracts");
    }
}
