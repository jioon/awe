package ai.awe.registry.runtime;

import java.util.regex.Pattern;

/** 不能把管理员切换到另一张干净表的结果，当成原物理关系已经修复。 */
public final class DataRunScope {
    private static final Pattern SNAPSHOT =
            Pattern.compile("postgresql:binding:([a-f0-9]{64}):tx_snapshot_sha256:[a-f0-9]{64}");

    private DataRunScope() {}

    public static boolean sameProjection(String failedSnapshot, String replaySnapshot) {
        if (failedSnapshot == null || replaySnapshot == null) return false;
        var before = SNAPSHOT.matcher(failedSnapshot);
        var after = SNAPSHOT.matcher(replaySnapshot);
        return before.matches() && after.matches() && before.group(1).equals(after.group(1));
    }
}
