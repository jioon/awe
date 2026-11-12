package ai.awe.kernel;
import java.time.Instant; import java.util.Objects;
/** Governance certification does not assert that source data is absolutely true. */
public record ModelRelease(String releaseId, String domain, String semanticVersion,
        String contentSha256, String ownerTeam, Instant publishedAt,
        Certification certification) {
    public enum Certification { REVIEWED, CERTIFIED, DISPUTED, DEPRECATED }
    public ModelRelease {
        for (String v : new String[]{releaseId, domain, semanticVersion, contentSha256, ownerTeam})
            if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing release field");
        Objects.requireNonNull(publishedAt); Objects.requireNonNull(certification);
        if (!contentSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Release requires SHA-256 content digest");
    }
}