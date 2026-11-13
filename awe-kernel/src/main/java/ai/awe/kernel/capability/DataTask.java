package ai.awe.kernel.capability;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 版本化数据能力请求；只保存调用方声明，不包含可信身份、已解析对象或整体任务状态。 */
public record DataTask(
        int protocolVersion,
        Intent intent,
        ExternalTaskRef externalTaskRef,
        ProposedFunction proposedFunction,
        Requirements requirements,
        Consumer consumer,
        Constraints constraints,
        String idempotencyKey) {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_ROWS = 100_000;
    public static final int MAX_BYTES = 1_048_576;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");

    public enum Intent {
        EXTRACT
    }

    public enum View {
        OBSERVED_SNAPSHOT
    }

    public record ExternalTaskRef(String issuer, String id) {
        public ExternalTaskRef {
            boundedText(issuer, "externalTaskRef.issuer", 120);
            boundedText(id, "externalTaskRef.id", 240);
        }
    }

    public record ProposedFunction(UUID releaseId, String contentHash) {
        public ProposedFunction {
            Objects.requireNonNull(releaseId, "Function releaseId required");
            if (contentHash == null || !contentHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Function contentHash must be SHA-256");
        }
    }

    public record Requirements(List<String> fields, Instant from, Instant to, View view) {
        public Requirements {
            Objects.requireNonNull(fields, "Requested fields required");
            if (fields.isEmpty() || fields.size() > 64)
                throw new IllegalArgumentException("Request between 1 and 64 fields");
            if (fields.stream().anyMatch(f -> f == null || !FIELD.matcher(f).matches()))
                throw new IllegalArgumentException(
                        "Requested fields must be supported identifiers");
            if (new HashSet<>(fields).size() != fields.size())
                throw new IllegalArgumentException("Duplicate requested field");
            fields = List.copyOf(fields);
            Objects.requireNonNull(from, "from required");
            Objects.requireNonNull(to, "to required");
            if (!from.isBefore(to)) throw new IllegalArgumentException("from must precede to");
            if (view != View.OBSERVED_SNAPSHOT)
                throw new IllegalArgumentException("Only OBSERVED_SNAPSHOT is supported");
        }
    }

    public record Consumer(String purpose) {
        public Consumer {
            boundedText(purpose, "consumer.purpose", 500);
        }
    }

    public record Constraints(int maxRows, int maxBytes) {
        public Constraints {
            if (maxRows < 1 || maxRows > MAX_ROWS)
                throw new IllegalArgumentException("maxRows exceeds the supported budget");
            if (maxBytes < 1 || maxBytes > MAX_BYTES)
                throw new IllegalArgumentException("maxBytes exceeds the supported budget");
        }
    }

    public DataTask {
        if (protocolVersion != PROTOCOL_VERSION)
            throw new IllegalArgumentException("Unsupported DataTask protocolVersion");
        if (intent != Intent.EXTRACT)
            throw new IllegalArgumentException("Only EXTRACT is supported");
        // 外部关联是可选的非授权引用；真实调用者与委托由服务端认证渠道提供。
        Objects.requireNonNull(proposedFunction, "Proposed function required");
        Objects.requireNonNull(requirements, "Data requirements required");
        Objects.requireNonNull(consumer, "Consumer purpose required");
        Objects.requireNonNull(constraints, "Constraints required");
        if (idempotencyKey == null || !IDEMPOTENCY.matcher(idempotencyKey).matches())
            throw new IllegalArgumentException("Invalid idempotencyKey");
    }

    private static void boundedText(String value, String field, int limit) {
        if (value == null
                || value.isBlank()
                || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + field);
    }
}
