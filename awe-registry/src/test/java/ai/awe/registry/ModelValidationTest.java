package ai.awe.registry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class ModelValidationTest {
  final JsonDocuments json=new JsonDocuments();
  final ModelValidation validation=new ModelValidation();
  @Test void canonicalHashIndependentOfObjectKeyOrder() {
    assertEquals(json.sha256(json.parse("{\"b\":2,\"a\":1}")),
                 json.sha256(json.parse("{\"a\":1,\"b\":2}")));
  }
  @Test void validDatasetContract() {
    var spec=json.parse("""
       {"name":"活跃明细","definition":"一行一个有效事件","ownerTeam":"video_dw",
        "grain":{"keys":["event_id"]},
        "time":{"field":"event_time","role":"EVENT_TIME","zone":"Asia/Shanghai"},
        "bindings":[{"sourceSystem":"hive","externalRef":"hive.dwd_activity",
                     "fingerprint":"DDL_SHA256_PENDING"}]}
       """);
    assertDoesNotThrow(()->validation.validate("video","dataset.video.activity","Dataset",spec));
  }
  @Test void invalidMetricGrainAndTimeRejected() {
    var spec=json.parse("""
       {"name":"DAU","definition":"日活用户","ownerTeam":"video_dw",
        "grain":{"keys":["user_id","user_id"]},
        "time":{"field":"activity_date","role":"BUSINESS_DATE","zone":"Asia/Shanghai"},
        "subjectRef":"entity.video.user","datasetRef":"dataset.video.activity",
        "measure":{"field":"user_id","kind":"COUNT_DISTINCT"}}
       """);
    assertThrows(IllegalArgumentException.class,()->validation.validate("video","metric.video.dau","Metric",spec));
  }
  @Test void missingBindingsFingerprintRejected() {
    var spec=json.parse("""
       {"name":"表","definition":"事件表","ownerTeam":"video_dw",
        "grain":{"keys":["event_id"]},"time":{"field":"dt","role":"BUSINESS_DATE","zone":"Asia/Shanghai"},
        "bindings":[{"sourceSystem":"hive","externalRef":"hive.dwd"}]}
       """);
    assertThrows(IllegalArgumentException.class,()->validation.validate("video","dataset.video.activity","Dataset",spec));
  }
  @Test void prefixEnforced() {
    var spec=json.parse("""
      {"name":"x","definition":"x","ownerTeam":"team"}
    """);
    assertThrows(IllegalArgumentException.class,()->validation.validate("video","metric.video.dau","BusinessEntity",spec));
  }
}