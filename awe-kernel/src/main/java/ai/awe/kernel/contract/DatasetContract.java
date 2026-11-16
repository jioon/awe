package ai.awe.kernel.contract;

import ai.awe.kernel.identity.EntityRef;
import java.util.*;

/** 逻辑目标可先于物理资产发布；未物化本身不产生来源验证或运行许可。 */
public record DatasetContract(
        EntityRef rowSchema,
        Mode mode,
        Optional<String> targetBindingRef,
        ReadMode readMode,
        TemporalContract.TimeRole rangeRole) {
    public enum Mode {
        OBSERVED_SOURCE,
        LOGICAL_TARGET
    }

    public enum ReadMode {
        EVENT_WINDOW,
        STATIC_SNAPSHOT
    }

    public DatasetContract {
        Objects.requireNonNull(rowSchema);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(targetBindingRef);
        Objects.requireNonNull(readMode);
        Objects.requireNonNull(rangeRole);
        if (readMode == ReadMode.STATIC_SNAPSHOT
                && rangeRole != TemporalContract.TimeRole.OBSERVED_AT)
            throw new IllegalArgumentException(
                    "Static snapshot time is the observed transaction, not a fabricated event column");
        if (mode == Mode.LOGICAL_TARGET && targetBindingRef.isEmpty())
            throw new IllegalArgumentException(
                    "Logical target requires a registered target binding reference");
        if (mode == Mode.OBSERVED_SOURCE && targetBindingRef.isPresent())
            throw new IllegalArgumentException(
                    "Observed source cannot also be an unresolved target binding");
        targetBindingRef.ifPresent(
                value -> {
                    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}"))
                        throw new IllegalArgumentException("Invalid target binding reference");
                });
    }
}
