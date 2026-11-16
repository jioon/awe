package ai.awe.kernel.contract;

import ai.awe.kernel.identity.EntityRef;
import ai.awe.kernel.model.ObjectType;
import java.time.ZoneId;
import java.util.*;

/** 正式指标消费契约，与基础 Metric 一起规范发布；不从名称或自然语言描述猜测计算。 */
public record MetricContract(
        EntityRef statisticalSubject,
        String identityField,
        IdentityNullPolicy identityNullPolicy,
        String unit,
        TemporalContract.TimeRole timeRole,
        ZoneId businessZone,
        List<Source> sources,
        RelationalIR.Node plan,
        List<Output> outputs,
        List<String> grain,
        EmptyPolicy emptyPolicy,
        ZeroDivisionPolicy zeroDivisionPolicy,
        Optional<Direct> direct) {
    public enum EmptyPolicy {
        NO_RECORD,
        ZERO
    }

    public enum IdentityNullPolicy {
        IGNORE,
        REJECT
    }

    public enum ZeroDivisionPolicy {
        NULL,
        REJECT
    }

    public record Source(String alias, EntityRef readFunction) {
        public Source {
            RelationalIR.identifier(alias);
            Objects.requireNonNull(readFunction);
        }
    }

    public record Output(String name, ObjectType.DataType type, String unit, boolean nullable) {
        public Output {
            RelationalIR.identifier(name);
            Objects.requireNonNull(type);
            text(unit, "output unit");
            if (type == ObjectType.DataType.JSON || type == ObjectType.DataType.ENTITY_REF)
                throw new IllegalArgumentException(
                        "Metric outputs require explicit scalar encodings");
        }
    }

    public record Direct(
            String bindingRef, String implementationVersion, String implementationHash) {
        public Direct {
            text(bindingRef, "DIRECT binding");
            text(implementationVersion, "implementation version");
            if (bindingRef.length() > 240
                    || !bindingRef.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")
                    || implementationHash == null
                    || !implementationHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException(
                        "DIRECT implementation requires a registered binding and exact SHA-256");
        }
    }

    public MetricContract {
        Objects.requireNonNull(statisticalSubject);
        RelationalIR.fieldName(identityField);
        text(unit, "unit");
        Objects.requireNonNull(identityNullPolicy);
        Objects.requireNonNull(zeroDivisionPolicy);
        Objects.requireNonNull(timeRole);
        Objects.requireNonNull(businessZone);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(emptyPolicy);
        Objects.requireNonNull(direct);
        sources = List.copyOf(sources);
        outputs = List.copyOf(outputs);
        grain = List.copyOf(grain);
        if (sources.isEmpty()
                || sources.size() > 8
                || outputs.isEmpty()
                || outputs.size() > 64
                || grain.size() > 32)
            throw new IllegalArgumentException("Metric source/output/grain budget exceeded");
        var aliases = new HashSet<String>();
        for (var source : sources)
            if (!aliases.add(source.alias()))
                throw new IllegalArgumentException("Duplicate metric source alias");
        if (!aliases.equals(RelationalIR.sources(plan)))
            throw new IllegalArgumentException("Plan must reference exactly the declared sources");
        if (identityField.contains(".")
                && !aliases.contains(identityField.substring(0, identityField.indexOf('.'))))
            throw new IllegalArgumentException(
                    "Statistical identity is outside the declared source scope");
        var names = new HashSet<String>();
        for (var output : outputs)
            if (!names.add(output.name()))
                throw new IllegalArgumentException("Duplicate metric output");
        if (new HashSet<>(grain).size() != grain.size() || !names.containsAll(grain))
            throw new IllegalArgumentException("Metric grain must name unique declared outputs");
        RelationalIR.validate(plan);
    }

    private static void text(String value, String role) {
        if (value == null
                || value.isBlank()
                || value.length() > 500
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid metric " + role);
    }
}
