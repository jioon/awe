package ai.awe.kernel;

import java.util.*;

/** Business and data-engineering object type, not an individual customer or warehouse row. */
public record ObjectType(EntityRef ref, Family family, String description,
                         List<Property> properties, List<String> identityKeys)
        implements AweDefinition {
    public enum Family {
        BUSINESS_ENTITY, BUSINESS_EVENT, DATASET, PIPELINE, DATA_PRODUCT, PROFILE, PHYSICAL_ASSET
    }
    public enum DataType {
        STRING, BOOLEAN, INTEGER, DECIMAL, DATE, TIMESTAMP, ENTITY_REF, JSON
    }
    public record Property(String name, DataType type, boolean required, boolean sensitive) {
        public Property {
            if(name==null || name.isBlank()) throw new IllegalArgumentException("Property name required");
            Objects.requireNonNull(type);
        }
    }
    public ObjectType {
        Objects.requireNonNull(ref);Objects.requireNonNull(family);
        if(description==null || description.isBlank()) throw new IllegalArgumentException("Object definition required");
        properties=List.copyOf(Objects.requireNonNull(properties));
        identityKeys=List.copyOf(Objects.requireNonNull(identityKeys));
        var defined=new HashSet<String>();
        for(var property:properties)
            if(!defined.add(property.name())) throw new IllegalArgumentException("Duplicate property: "+property.name());
        if(identityKeys.isEmpty()) throw new IllegalArgumentException("Object must declare identity");
        for(String key:identityKeys)
            if(!defined.contains(key)) throw new IllegalArgumentException("Missing identity property: "+key);
        if(new HashSet<>(identityKeys).size()!=identityKeys.size())
            throw new IllegalArgumentException("Duplicate identity key");
    }
}
