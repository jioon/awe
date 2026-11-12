package ai.awe.registry.runtime;

import java.util.Set;

/** 服务端注册的只读检查器；外部 Skill 不能通过传入规则名执行任意代码。 */
public final class RuntimeRules {
    public static final Set<String> DATA_CHECKS =
            Set.of(
                    "check.grain.unique",
                    "check.grain.not_null",
                    "check.time.available_after_event");

    private RuntimeRules() {}
}
