package ai.awe.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Published content hashes must survive exact numbers and PostgreSQL JSONB normalization. */
final class JsonCanonicalizationTest {
  private final JsonDocuments json=new JsonDocuments();
  @Test void distinctHighPrecisionNumbersCannotCollapseToTheSameDigest() {
    var first=json.parse("{\"value\":0.1234567890123456789012345678901}");
    var second=json.parse("{\"value\":0.1234567890123456789012345678902}");
    assertNotEquals(json.sha256(first),json.sha256(second));
    assertEquals("{\"value\":0.1234567890123456789012345678901}",json.canonical(first));
    assertEquals(json.sha256(first),json.sha256(json.parse(json.canonical(first))));
  }
  @Test void equivalentNumericNotationHasOneCanonicalRepresentation() {
    for(String number:new String[]{"1","1.0","1.0000","1e0","100e-2"})
      assertEquals("1",json.canonical(json.parse(number)));
    for(String number:new String[]{"0","0.0","-0.0","0e4"})
      assertEquals("0",json.canonical(json.parse(number)));
    assertEquals(json.sha256(json.parse("1e-40")),json.sha256(json.parse("0.0000000000000000000000000000000000000001")));
    assertEquals(json.sha256(json.parse("10000000000000000000000000000000000000000000000000")),json.sha256(json.parse("1e49")));
  }
  @Test void nestedObjectOrderIsIrrelevantButOrderedArrayPositionsArePreserved() {
    assertEquals(json.sha256(json.parse("{\"b\":{\"y\":2,\"x\":1},\"a\":[true,null,\"s\"]}")),
        json.sha256(json.parse("{\"a\":[true,null,\"s\"],\"b\":{\"x\":1,\"y\":2}}")));
    assertNotEquals(json.sha256(json.parse("[\"a\",\"b\"]")),json.sha256(json.parse("[\"b\",\"a\"]")));
    assertNotEquals(json.sha256(json.parse("\"1\"")),json.sha256(json.parse("1")));
    assertNotEquals(json.sha256(json.parse("null")),json.sha256(json.parse("\"null\"")));
  }
  @Test void ambiguousOrNonJsonDocumentsAreRejected() {
    assertThrows(IllegalArgumentException.class,()->json.parse("{\"version\":1,\"version\":2}"));
    assertThrows(IllegalArgumentException.class,()->json.parse("{} {}"));
    assertThrows(IllegalArgumentException.class,()->json.parse("NaN"));
    assertThrows(IllegalArgumentException.class,()->json.parse(""));
  }
}
