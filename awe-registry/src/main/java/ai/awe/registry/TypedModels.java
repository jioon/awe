package ai.awe.registry;

import ai.awe.kernel.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Strict boundary between authoring documents and the same immutable contracts used by consumers. */
public final class TypedModels {
  private static final Map<String,Class<? extends AweDefinition>> TYPES=Map.ofEntries(
      Map.entry("ObjectType",ObjectType.class),Map.entry("LinkType",LinkType.class),
      Map.entry("Dataset",SemanticModel.Dataset.class),Map.entry("Metric",SemanticModel.Metric.class),
      Map.entry("Feature",SemanticModel.Feature.class),Map.entry("FunctionDefinition",FunctionDefinition.class),
      Map.entry("ActionDefinition",ActionDefinition.class),Map.entry("ContractDefinition",ContractDefinition.class),
      Map.entry("EvidenceAssertion",EvidenceAssertion.class));
  private static final JsonNodeFactory N=JsonNodeFactory.instance;
  private final JsonDocuments json=new JsonDocuments();

  public Set<String> types() { return TYPES.keySet(); }

  public AweDefinition decode(String type,JsonNode spec) {
    if(spec==null || !spec.isObject()) throw new IllegalArgumentException("spec must be an object");
    var version=spec.path("schemaVersion");
    if(!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue()!=2)
      throw new IllegalArgumentException("Migrate legacy document explicitly to schemaVersion 2 before submission");
    Class<? extends AweDefinition> target=type==null?null:TYPES.get(type);
    if(target==null) throw new IllegalArgumentException("Unsupported typed model: "+type);
    fields(spec,"spec",Set.of("schemaVersion","name","definition","ownerTeam","model","bindings","dependsOn"));
    text(spec.path("name"),"name");text(spec.path("definition"),"definition");
    String owner=text(spec.path("ownerTeam"),"ownerTeam");
    AweDefinition definition=(AweDefinition)read(spec.path("model"),target,"model");
    if(definition instanceof ContractDefinition c && !c.ownerTeam().equals(owner))
      throw new IllegalArgumentException("Contract ownerTeam must match envelope ownerTeam");
    if(definition instanceof EvidenceAssertion e && !e.modelReleaseHash().matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Evidence modelReleaseHash must be an exact SHA-256 release digest");
    if(spec.has("dependsOn")) read(spec.get("dependsOn"),new Parameterized(Set.class,EntityRef.class),"dependsOn");
    if(spec.has("bindings")) {
      JsonNode bindings=spec.get("bindings");
      if(!bindings.isArray()) throw new IllegalArgumentException("bindings must be an array");
      Set<String> seen=new HashSet<>();
      for(JsonNode b:bindings) {
        fields(b,"binding",Set.of("sourceSystem","externalRef","fingerprint","timeFields"));
        String system=text(b.path("sourceSystem"),"binding.sourceSystem");
        String external=text(b.path("externalRef"),"binding.externalRef");
        String fingerprint=text(b.path("fingerprint"),"binding.fingerprint");
        if(!fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Binding fingerprint must be an exact SHA-256 source digest");
        Set<TemporalContract.TimeRole> mappedRoles=new HashSet<>();
        if(b.has("timeFields")) {
          JsonNode mapping=b.get("timeFields");
          if(!mapping.isObject()) throw new IllegalArgumentException("binding.timeFields must be an object");
          mapping.fields().forEachRemaining(entry->{
            TemporalContract.TimeRole role;
            try { role=TemporalContract.TimeRole.valueOf(entry.getKey()); }
            catch(IllegalArgumentException e) { throw new IllegalArgumentException("Unsupported time role in binding.timeFields"); }
            text(entry.getValue(),"binding.timeFields."+entry.getKey());
            mappedRoles.add(role);
          });
        }
        if(definition instanceof SemanticModel semantic && !mappedRoles.equals(semantic.time().roles()))
          throw new IllegalArgumentException("binding.timeFields must map exactly the declared semantic time roles");
        if(!seen.add(system+"\u0000"+external)) throw new IllegalArgumentException("Duplicate source binding");
      }
    }
    return definition;
  }

  /** Version-qualified typed references; the defining ref itself is not a dependency. */
  public Set<EntityRef> references(AweDefinition definition) {
    var result=new LinkedHashSet<EntityRef>();
    for(RecordComponent component:definition.getClass().getRecordComponents())
      if(!component.getName().equals("ref")) collect(componentValue(component,definition),result);
    return Collections.unmodifiableSet(result);
  }

  public JsonNode canonicalSpec(String type,JsonNode spec) {
    AweDefinition definition=decode(type,spec);
    ObjectNode result=N.objectNode();
    result.put("schemaVersion",2);
    for(String key:List.of("name","definition","ownerTeam")) result.set(key,spec.get(key));
    result.set("model",encode(definition));
    if(spec.has("dependsOn")) result.set("dependsOn",encode(read(spec.get("dependsOn"),new Parameterized(Set.class,EntityRef.class),"dependsOn")));
    if(spec.has("bindings")) {
      List<JsonNode> bindings=new ArrayList<>();spec.get("bindings").forEach(bindings::add);
      bindings.sort(Comparator.comparing(json::canonical));
      ArrayNode array=N.arrayNode();bindings.forEach(array::add);result.set("bindings",array);
    }
    return result;
  }

  private void collect(Object value,Set<EntityRef> refs) {
    if(value instanceof EntityRef ref) refs.add(ref);
    else if(value instanceof Collection<?> list) list.forEach(x->collect(x,refs));
    else if(value!=null && value.getClass().isRecord())
      for(RecordComponent c:value.getClass().getRecordComponents()) collect(componentValue(c,value),refs);
  }

  private Object read(JsonNode node,Type type,String path) {
    if(node==null || node.isNull() || node.isMissingNode()) throw new IllegalArgumentException("Missing required field: "+path);
    if(type instanceof ParameterizedType parameterized) {
      if(!node.isArray()) throw new IllegalArgumentException(path+" must be an array");
      var values=new ArrayList<Object>();
      for(JsonNode child:node) values.add(read(child,parameterized.getActualTypeArguments()[0],path+"[]"));
      if(parameterized.getRawType()==Set.class) {
        var unique=new LinkedHashSet<>(values);
        if(unique.size()!=values.size()) throw new IllegalArgumentException("Duplicate value in "+path);
        return unique;
      }
      if(parameterized.getRawType()==List.class) return values;
      throw new IllegalArgumentException("Unsupported collection: "+path);
    }
    Class<?> target=(Class<?>)type;
    if(target==String.class) return text(node,path);
    if(target==boolean.class) {
      if(!node.isBoolean()) throw new IllegalArgumentException(path+" must be boolean");
      return node.booleanValue();
    }
    if(target==ZoneId.class) {
      try { return ZoneId.of(text(node,path)); }
      catch(Exception e) { throw new IllegalArgumentException("Invalid time zone: "+path); }
    }
    if(target==Instant.class) {
      try { return Instant.parse(text(node,path)); }
      catch(Exception e) { throw new IllegalArgumentException("Invalid ISO instant: "+path); }
    }
    if(target.isEnum()) {
      String value=text(node,path);
      for(Object option:target.getEnumConstants()) if(((Enum<?>)option).name().equals(value)) return option;
      throw new IllegalArgumentException("Unsupported enum value: "+path);
    }
    if(target.isRecord()) {
      RecordComponent[] components=target.getRecordComponents();
      Set<String> names=new HashSet<>();for(var c:components) names.add(c.getName());
      fields(node,path,names);
      Class<?>[] parameters=new Class<?>[components.length];Object[] values=new Object[components.length];
      for(int i=0;i<components.length;i++) {
        var c=components[i];parameters[i]=c.getType();values[i]=read(node.path(c.getName()),c.getGenericType(),path+"."+c.getName());
      }
      try { return target.getDeclaredConstructor(parameters).newInstance(values); }
      catch(InvocationTargetException e) {
        Throwable cause=e.getCause();
        String detail=cause instanceof IllegalArgumentException?cause.getMessage():"invalid required value";
        throw new IllegalArgumentException("Invalid "+path+": "+detail);
      } catch(ReflectiveOperationException e) { throw new IllegalStateException("Unsupported metamodel record",e); }
    }
    throw new IllegalArgumentException("Unsupported model field: "+path);
  }

  private JsonNode encode(Object value) {
    if(value instanceof String s) return N.textNode(s);
    if(value instanceof Boolean b) return N.booleanNode(b);
    if(value instanceof Enum<?> e) return N.textNode(e.name());
    if(value instanceof ZoneId z) return N.textNode(z.getId());
    if(value instanceof Instant i) return N.textNode(i.toString());
    if(value instanceof Collection<?> collection) {
      List<JsonNode> values=new ArrayList<>();collection.forEach(x->values.add(encode(x)));
      if(value instanceof Set<?>) values.sort(Comparator.comparing(json::canonical));
      ArrayNode result=N.arrayNode();values.forEach(result::add);return result;
    }
    if(value!=null && value.getClass().isRecord()) {
      ObjectNode result=N.objectNode();
      for(RecordComponent c:value.getClass().getRecordComponents()) result.set(c.getName(),encode(componentValue(c,value)));
      return result;
    }
    throw new IllegalStateException("Unsupported typed serialization");
  }

  private Object componentValue(RecordComponent component,Object record) {
    try { return component.getAccessor().invoke(record); }
    catch(ReflectiveOperationException e) { throw new IllegalStateException("Cannot read metamodel record",e); }
  }
  private void fields(JsonNode node,String path,Set<String> allowed) {
    if(node==null || !node.isObject()) throw new IllegalArgumentException(path+" must be an object");
    node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key)) throw new IllegalArgumentException("Unknown field: "+path+"."+key);});
  }
  private String text(JsonNode node,String path) {
    if(node==null || !node.isTextual() || node.textValue().isBlank()) throw new IllegalArgumentException("Nonempty string required: "+path);
    return node.textValue();
  }
  private record Parameterized(Type raw,Type element) implements ParameterizedType {
    @Override public Type[] getActualTypeArguments() { return new Type[]{element}; }
    @Override public Type getRawType() { return raw; }
    @Override public Type getOwnerType() { return null; }
  }
}
