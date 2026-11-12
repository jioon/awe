package ai.awe.registry;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
/** JSON-over-HTTP API. DTO boundary uses JSON; the semantic kernel remains strongly typed. */
@RestController
@RequestMapping(value="/api",produces=MediaType.APPLICATION_JSON_VALUE)
public final class RegistryApi {
  private final ModelRegistry registry; private final JsonDocuments json;
  public RegistryApi(ModelRegistry registry,JsonDocuments json){this.registry=registry;this.json=json;}
  private String actor(HttpServletRequest req){return (String)req.getAttribute("awe.actor");}
  @GetMapping("/domains") public String domains(){return json.write(registry.domains());}
  @PostMapping("/domains") public String domain(@RequestBody String body,HttpServletRequest request) {
    return json.write(registry.createDomain(json.object(body),actor(request)));
  }
  @GetMapping("/drafts") public String drafts(){return json.write(registry.drafts());}
  @GetMapping("/drafts/{id}") public String draft(@PathVariable UUID id){return json.write(registry.draft(id));}
  @PostMapping("/drafts") public String create(@RequestBody String body,HttpServletRequest request) {
    return json.write(registry.createDraft(json.object(body),actor(request)));
  }
  @PutMapping("/drafts/{id}") public String edit(@PathVariable UUID id,@RequestBody String body,HttpServletRequest request) {
    return json.write(registry.edit(id,json.object(body),actor(request)));
  }
  @PostMapping("/drafts/{id}/submit") public String submit(@PathVariable UUID id,HttpServletRequest request) {
    return json.write(registry.submit(id,actor(request)));
  }
  @PostMapping("/drafts/{id}/approve") public String approve(@PathVariable UUID id,HttpServletRequest request) {
    return json.write(registry.approve(id,actor(request)));
  }
  @PostMapping("/drafts/{id}/publish") public String publish(@PathVariable UUID id,@RequestBody String body,HttpServletRequest request) {
    return json.write(registry.publish(id,json.object(body),actor(request)));
  }
  @PostMapping("/drafts/{id}/validate") public String validate(@PathVariable UUID id,HttpServletRequest request) {
    return json.write(registry.validateDraft(id,actor(request)));
  }
  @GetMapping("/releases") public String releases(){return json.write(registry.releases());}
  @GetMapping("/releases/{id}") public String release(@PathVariable UUID id){return json.write(registry.release(id));}
  @PostMapping("/releases/{id}/verify") public String verify(@PathVariable UUID id,HttpServletRequest request) {
    return json.write(registry.verifyRelease(id,actor(request)));
  }
  @GetMapping("/gaps") public String gaps(){return json.write(registry.gaps());}
  @GetMapping("/validation-checks") public String checks(@RequestParam String entityId){return json.write(registry.validationChecks(entityId));}
  @GetMapping("/impact") public String impact(@RequestParam String entityId){return json.write(registry.impact(entityId));}
  @GetMapping("/audit") public String audit(@RequestParam String entityId){return json.write(registry.auditTrail(entityId));}
}
