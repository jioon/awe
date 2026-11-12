package ai.awe.registry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Component public final class JsonDocuments {
  private final ObjectMapper mapper=new ObjectMapper();
  public JsonNode parse(String json) {
    try { return mapper.readTree(json); }
    catch(Exception e) { throw new IllegalArgumentException("Invalid JSON document",e); }
  }
  public ObjectNode object(String json) {
    var node=parse(json);
    if(!node.isObject()) throw new IllegalArgumentException("JSON root must be an object");
    return (ObjectNode)node;
  }
  public String write(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch(Exception e) { throw new IllegalStateException("Cannot encode JSON",e); }
  }
  public String canonical(JsonNode node) {
    if(node.isObject()) {
      var props=new TreeMap<String,String>();
      node.fields().forEachRemaining(e->props.put(e.getKey(),canonical(e.getValue())));
      var pairs=new ArrayList<String>();
      props.forEach((k,v)->pairs.add(write(k)+":"+v));
      return "{"+String.join(",",pairs)+"}";
    }
    if(node.isArray()) {
      var values=new ArrayList<String>();
      node.forEach(v->values.add(canonical(v)));
      return "["+String.join(",",values)+"]";
    }
    return node.toString();
  }
  public String sha256(JsonNode node) {
    try {
      byte[] bytes=MessageDigest.getInstance("SHA-256").digest(canonical(node).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch(Exception e) { throw new IllegalStateException(e); }
  }
}