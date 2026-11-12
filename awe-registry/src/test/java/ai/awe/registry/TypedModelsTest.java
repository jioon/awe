package ai.awe.registry;

import ai.awe.kernel.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class TypedModelsTest {
  private final TypedModels typed=new TypedModels();
  private final JsonDocuments json=new JsonDocuments();
  @Test void allSevenFamiliesAndThreeSemanticBranchesRoundTripAsKernelRecords() {
    Set<Class<?>> classes=Set.of(ObjectType.class,LinkType.class,SemanticModel.Dataset.class,SemanticModel.Metric.class,
        SemanticModel.Feature.class,FunctionDefinition.class,ActionDefinition.class,ContractDefinition.class,EvidenceAssertion.class);
    assertEquals(9,typed.types().size());
    for(String type:typed.types()) {
      var spec=TypedModelFixtures.spec(type);var decoded=typed.decode(type,spec);
      assertTrue(classes.contains(decoded.getClass()));
      var canonical=typed.canonicalSpec(type,spec);
      assertEquals(decoded,typed.decode(type,canonical));
      assertEquals(json.canonical(canonical),json.canonical(typed.canonicalSpec(type,canonical)));
    }
  }
  @Test void rejectsUnknownFieldsAtEnvelopeAndNestedBoundaries() {
    for(String type:typed.types()) {
      var spec=TypedModelFixtures.spec(type);spec.put("secretBypass",true);
      assertThrows(IllegalArgumentException.class,()->typed.decode(type,spec));
      var nested=TypedModelFixtures.spec(type);((ObjectNode)nested.get("model")).put("sideEffectFree",true);assertThrows(IllegalArgumentException.class,()->typed.decode(type,nested));
    }
  }
  @Test void legacyDocumentsRequireExplicitMigrationAndUnsupportedTypesFailClosed() {
    var legacy=json.object("{\"name\":\"old\",\"definition\":\"old\",\"ownerTeam\":\"team\"}");
    assertTrue(assertThrows(IllegalArgumentException.class,()->typed.decode("Dataset",legacy)).getMessage().contains("schemaVersion 2"));
    assertThrows(IllegalArgumentException.class,()->typed.decode("BusinessEntity",legacy));
    var spec=TypedModelFixtures.spec("ObjectType");spec.put("schemaVersion","2");
    assertThrows(IllegalArgumentException.class,()->typed.decode("ObjectType",spec));
  }
  @Test void primitiveCoercionNullCollectionsAndUnknownEnumAreRejected() {
    var spec=TypedModelFixtures.spec("ObjectType");
    var property=(ObjectNode)spec.at("/model/properties/0");property.put("required","true");
    assertThrows(IllegalArgumentException.class,()->typed.decode("ObjectType",spec));
    var nullList=TypedModelFixtures.spec("ObjectType");((ObjectNode)nullList.get("model")).putNull("identityKeys");
    assertThrows(IllegalArgumentException.class,()->typed.decode("ObjectType",nullList));
    var enumValue=TypedModelFixtures.spec("ObjectType");((ObjectNode)enumValue.get("model")).put("family","USER_ROW");
    assertThrows(IllegalArgumentException.class,()->typed.decode("ObjectType",enumValue));
  }
  @Test void duplicateSetsAreRejectedRatherThanSilentlyCoalesced() {
    var spec=TypedModelFixtures.spec("Dataset");
    ((ArrayNode)spec.at("/model/time/roles")).add("EVENT_TIME");
    assertThrows(IllegalArgumentException.class,()->typed.decode("Dataset",spec));
    var dependencies=TypedModelFixtures.spec("Dataset");
    var array=dependencies.putArray("dependsOn");var ref=json.parse("{\"id\":\"entity.video.user\",\"modelVersion\":\"1.0.0\"}");array.add(ref);array.add(ref);
    assertThrows(IllegalArgumentException.class,()->typed.decode("Dataset",dependencies));
  }
  @Test void kernelIdentityFeatureAndActionInvariantsApplyToJsonInput() {
    var object=TypedModelFixtures.spec("ObjectType");((ArrayNode)object.at("/model/identityKeys")).add("missing_id");
    assertThrows(IllegalArgumentException.class,()->typed.decode("ObjectType",object));
    var feature=TypedModelFixtures.spec("Feature");((ObjectNode)feature.at("/model/time")).putArray("roles").add("EVENT_TIME");
    assertThrows(IllegalArgumentException.class,()->typed.decode("Feature",feature));
    var action=TypedModelFixtures.spec("ActionDefinition");((ObjectNode)action.get("model")).put("rollbackOrCompensationRef","");
    assertThrows(IllegalArgumentException.class,()->typed.decode("ActionDefinition",action));
  }
  @Test void nullableIdentityAndConflatedFeatureTimesAreRejectedAtTheJsonBoundary() {
    var object=TypedModelFixtures.spec("ObjectType");
    ((ObjectNode)object.at("/model/properties/0")).put("required",false);
    assertThrows(IllegalArgumentException.class,()->typed.decode("ObjectType",object));
    var feature=TypedModelFixtures.spec("Feature");
    ((ObjectNode)feature.get("model")).put("availableAtField",feature.at("/model/observationTimeField").asText());
    assertThrows(IllegalArgumentException.class,()->typed.decode("Feature",feature));
  }
  @Test void scopedEvidenceCannotPassWithInventedHashOrNoProof() {
    var spec=TypedModelFixtures.spec("EvidenceAssertion");((ObjectNode)spec.get("model")).put("modelReleaseHash","hash:model-v1");
    assertThrows(IllegalArgumentException.class,()->typed.decode("EvidenceAssertion",spec));
    var passed=TypedModelFixtures.spec("EvidenceAssertion");((ObjectNode)passed.get("model")).put("result","PASSED");
    assertThrows(IllegalArgumentException.class,()->typed.decode("EvidenceAssertion",passed));
    var time=TypedModelFixtures.spec("EvidenceAssertion");((ObjectNode)time.get("model")).put("checkedAt","yesterday");
    assertThrows(IllegalArgumentException.class,()->typed.decode("EvidenceAssertion",time));
  }
  @Test void semanticSetOrderDoesNotChangeCanonicalReleaseContent() {
    var first=TypedModelFixtures.spec("Metric");var second=first.deepCopy();
    ((ObjectNode)second.at("/model/aggregation")).putArray("forbiddenRollupAxes").add("app").add("day");
    ((ObjectNode)second.at("/model/time")).putArray("roles").add("AVAILABLE_AT").add("EVENT_TIME");
    assertEquals(json.sha256(typed.canonicalSpec("Metric",first)),json.sha256(typed.canonicalSpec("Metric",second)));
  }
  @Test void referenceSetOrderAndNestedObjectKeyOrderHaveTheSameCanonicalDigest() {
    var first=TypedModelFixtures.spec("Dataset");var second=first.deepCopy();
    var region=json.parse("{\"id\":\"dimension.video.region\",\"modelVersion\":\"1.0.0\"}");
    var app=json.parse("{\"modelVersion\":\"1.0.0\",\"id\":\"dimension.video.app\"}");
    ((ObjectNode)first.get("model")).putArray("dimensions").add(region).add(app);
    ((ObjectNode)second.get("model")).putArray("dimensions").add(app).add(region);
    assertEquals(json.sha256(typed.canonicalSpec("Dataset",first)),json.sha256(typed.canonicalSpec("Dataset",second)));
  }
  @Test void dependenciesAreVersionQualifiedAndDefiningReferenceIsExcluded() {
    var refs=typed.references(typed.decode("Metric",TypedModelFixtures.spec("Metric")));
    assertEquals(Set.of(new EntityRef("entity.video.user","1.0.0"),new EntityRef("dataset.video.events","1.0.0")),refs);
    assertTrue(typed.references(typed.decode("ObjectType",TypedModelFixtures.spec("ObjectType"))).isEmpty());
  }
  @Test void boundSemanticModelsRequireAnExplicitPhysicalColumnForEveryTimeRole() {
    var spec=TypedModelFixtures.spec("Dataset");
    var binding=spec.putArray("bindings").addObject();
    binding.put("sourceSystem","hive").put("externalRef","video.events").put("fingerprint","a".repeat(64));
    assertThrows(IllegalArgumentException.class,()->typed.decode("Dataset",spec));
    binding.putObject("timeFields").put("EVENT_TIME","event_time").put("AVAILABLE_AT","available_at");
    assertDoesNotThrow(()->typed.decode("Dataset",spec));
    ((ObjectNode)binding.get("timeFields")).put("SYSTEM_TIME","system_time");
    assertThrows(IllegalArgumentException.class,()->typed.decode("Dataset",spec));
  }
  @Test void bindingFingerprintsCannotBeManualPlaceholders() {
    var spec=TypedModelFixtures.spec("ObjectType");
    spec.putArray("bindings").addObject().put("sourceSystem","hive").put("externalRef","video.users").put("fingerprint","MANUAL_UNVERIFIED");
    assertThrows(IllegalArgumentException.class,()->typed.decode("ObjectType",spec));
  }
  @Test void sourceBindingShapeAndDuplicatesAreCheckedBeforePublication() {
    var spec=TypedModelFixtures.spec("Dataset");var bindings=spec.putArray("bindings");
    var binding=json.parse("{\"sourceSystem\":\"hive\",\"externalRef\":\"db.events\",\"fingerprint\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"timeFields\":{\"EVENT_TIME\":\"event_time\",\"AVAILABLE_AT\":\"available_at\"}}");bindings.add(binding);bindings.add(binding);
    assertThrows(IllegalArgumentException.class,()->typed.decode("Dataset",spec));
    var wrong=TypedModelFixtures.spec("Dataset");wrong.putObject("bindings");
    assertThrows(IllegalArgumentException.class,()->typed.decode("Dataset",wrong));
  }
}
