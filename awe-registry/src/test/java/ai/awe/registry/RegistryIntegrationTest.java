package ai.awe.registry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL integration: Flyway, draft, review, immutable release, dependency and binding. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="AWE_TEST_POSTGRES",matches="true")
final class RegistryIntegrationTest {
  @Autowired ModelRegistry registry;
  @Autowired JsonDocuments json;
  private final String author="bootstrap-author",reviewer="bootstrap-reviewer";

  @Test void endToEndModelPublication() {
    registry.createDomain(json.parse("""
      {"code":"video","displayName":"视频业务","ownerTeam":"video_dw"}
      """),author);
    var user=registry.createDraft(json.parse("""
      {"domain":"video","entityId":"entity.video.user","entityType":"BusinessEntity",
       "spec":{"name":"用户","definition":"经确认的统一用户主体","ownerTeam":"video_dw"}}
      """),author);
    UUID userId=UUID.fromString((String)user.get("id"));
    assertEquals("DRAFT",user.get("state"));
    registry.submit(userId,author);
    assertThrows(IllegalArgumentException.class,()->registry.publish(userId,json.parse("{\"version\":\"1.0.0\"}"),reviewer));
    registry.approve(userId,reviewer);
    assertEquals("PUBLISHED",registry.publish(userId,json.parse("{\"version\":\"1.0.0\"}"),reviewer).get("state")==null?"PUBLISHED":"INVALID");

    var dataset=registry.createDraft(json.parse("""
      {"domain":"video","entityId":"dataset.video.activity_events","entityType":"Dataset",
       "spec":{"name":"有效活跃事件","definition":"每行一个已去重事件","ownerTeam":"video_dw",
        "grain":{"keys":["event_id"]},
        "time":{"field":"event_time","role":"EVENT_TIME","zone":"Asia/Shanghai"},
        "dependsOn":["entity.video.user"],
        "bindings":[{"sourceSystem":"hive","externalRef":"hive.dwd_activity",
          "fingerprint":"sha256:imported-physical-schema-not-yet-verified"}]}}
      """),author);
    UUID datasetId=UUID.fromString((String)dataset.get("id"));
    registry.submit(datasetId,author);
    registry.approve(datasetId,reviewer);
    var pub=registry.publish(datasetId,json.parse("{\"version\":\"1.0.0\"}"),reviewer);
    assertEquals("dataset.video.activity_events",pub.get("entity_id"));
    assertEquals(1,((java.util.List<?>)pub.get("bindings")).size());
    assertEquals("NOT_VERIFIED",
      ((Map<?,?>)((java.util.List<?>)pub.get("bindings")).get(0)).get("verification_status"));
    assertFalse(registry.impact("entity.video.user").isEmpty());
    assertThrows(IllegalArgumentException.class,
      ()->registry.publish(datasetId,json.parse("{\"version\":\"1.0.1\"}"),reviewer));
    assertEquals("PUBLISHED",registry.draft(datasetId).get("state"));
    assertFalse(registry.auditTrail("dataset.video.activity_events").isEmpty());
  }
}
