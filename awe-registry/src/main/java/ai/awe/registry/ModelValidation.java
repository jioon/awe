package ai.awe.registry;
import ai.awe.kernel.EntityRef;
import ai.awe.kernel.Grain;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
@Component public final class ModelValidation {
  private static final Set<String> TYPES=Set.of("BusinessEntity","BusinessEvent","Dataset","Metric","Dimension","Feature","Pipeline","DataProduct");
  private static final Pattern DOMAIN=Pattern.compile("[a-z][a-z0-9_]{1,79}");
  public void domain(String code,String owner) {
    if(code==null || !DOMAIN.matcher(code).matches()) throw new IllegalArgumentException("Invalid domain code");
    if(owner==null || owner.isBlank()) throw new IllegalArgumentException("ownerTeam required");
  }
  public void validate(String domain,String entityId,String type,JsonNode spec) {
    if(!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported model type: "+type);
    new EntityRef(entityId,"1.0.0");
    if(!entityId.startsWith(prefix(type)+"."+domain+"."))
      throw new IllegalArgumentException("Entity ID must match domain and type");
    if(!spec.isObject()) throw new IllegalArgumentException("spec must be object");
    required(spec,"name"); required(spec,"definition"); required(spec,"ownerTeam");
    if(Set.of("Dataset","Metric","Feature").contains(type)) {
      var keys=spec.path("grain").path("keys");
      if(!keys.isArray() || keys.isEmpty()) throw new IllegalArgumentException("grain.keys required");
      var names=new ArrayList<String>();
      keys.forEach(k->{if(!k.isTextual()||k.asText().isBlank())
        throw new IllegalArgumentException("Invalid grain key"); names.add(k.asText());});
      new Grain(names,Grain.GrainKind.FACT);
      var time=spec.path("time");
      required(time,"field"); required(time,"role"); required(time,"zone");
      try { ZoneId.of(time.path("zone").asText()); }
      catch(Exception e) { throw new IllegalArgumentException("Invalid time.zone",e); }
      if(!Set.of("EVENT_TIME","BUSINESS_DATE","AVAILABLE_AT","OBSERVED_AT","SYSTEM_TIME").contains(time.path("role").asText()))
        throw new IllegalArgumentException("Unsupported time.role");
    }
    if("Metric".equals(type)) {
      required(spec,"subjectRef"); required(spec,"datasetRef");
      var measure=spec.path("measure"); required(measure,"field"); required(measure,"kind");
      if(!Set.of("SUM","COUNT","COUNT_DISTINCT","AVG","RATIO","LAST").contains(measure.path("kind").asText()))
        throw new IllegalArgumentException("Unsupported measure.kind");
    }
    if("Pipeline".equals(type) && (!spec.path("inputs").isArray() || !spec.path("outputs").isArray()))
      throw new IllegalArgumentException("Pipeline inputs and outputs required");
    var deps=spec.path("dependsOn");
    if(!deps.isMissingNode() && !deps.isArray()) throw new IllegalArgumentException("dependsOn must be array");
    if(deps.isArray()) deps.forEach(ref->{if(!ref.isTextual()||ref.asText().isBlank())
      throw new IllegalArgumentException("Invalid dependsOn ref");});
    var bindings=spec.path("bindings");
    if(!bindings.isMissingNode() && !bindings.isArray()) throw new IllegalArgumentException("bindings must be array");
    if(bindings.isArray()) bindings.forEach(b->{required(b,"sourceSystem");required(b,"externalRef");required(b,"fingerprint");});
  }
  private void required(JsonNode n,String key) {
    if(!n.path(key).isTextual() || n.path(key).asText().isBlank())
      throw new IllegalArgumentException("Missing or empty: "+key);
  }
  private String prefix(String type) {
    return switch(type) {
      case "BusinessEntity" -> "entity"; case "BusinessEvent" -> "event";
      case "Dataset" -> "dataset"; case "Metric" -> "metric";
      case "Dimension" -> "dimension"; case "Feature" -> "feature";
      case "Pipeline" -> "pipeline"; default -> "product";
    };
  }
}