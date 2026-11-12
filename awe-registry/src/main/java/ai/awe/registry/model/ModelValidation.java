package ai.awe.registry.model;

import ai.awe.kernel.model.ActionDefinition;
import ai.awe.kernel.model.AweDefinition;
import ai.awe.kernel.model.ContractDefinition;
import ai.awe.kernel.model.EvidenceAssertion;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.LinkType;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class ModelValidation {
    private static final Pattern DOMAIN = Pattern.compile("[a-z][a-z0-9_]{1,79}");
    private final TypedModels typed = new TypedModels();

    public void domain(String code, String owner) {
        if (code == null || !DOMAIN.matcher(code).matches())
            throw new IllegalArgumentException("Invalid domain code");
        if (owner == null || owner.isBlank())
            throw new IllegalArgumentException("ownerTeam required");
    }

    public void validate(String domain, String entityId, String type, JsonNode spec) {
        domain(domain, "validation");
        AweDefinition model = typed.decode(type, spec);
        if (!model.ref().id().equals(entityId))
            throw new IllegalArgumentException("Model ref.id must match entityId");
        if (!entityId.startsWith(prefix(model) + "." + domain + "."))
            throw new IllegalArgumentException("Entity ID must match domain and model type");
    }

    private String prefix(AweDefinition definition) {
        return switch (definition) {
            case ObjectType o ->
                    switch (o.family()) {
                        case BUSINESS_ENTITY -> "entity";
                        case BUSINESS_EVENT -> "event";
                        case DIMENSION -> "dimension";
                        case DATASET -> "dataset";
                        case PIPELINE -> "pipeline";
                        case DATA_PRODUCT -> "product";
                        case PROFILE -> "profile";
                        case PHYSICAL_ASSET -> "asset";
                    };
            case LinkType ignored -> "link";
            case SemanticModel.Dataset ignored -> "dataset";
            case SemanticModel.Metric ignored -> "metric";
            case SemanticModel.Feature ignored -> "feature";
            case FunctionDefinition ignored -> "function";
            case ActionDefinition ignored -> "action";
            case ContractDefinition ignored -> "contract";
            case EvidenceAssertion ignored -> "assertion";
        };
    }
}
