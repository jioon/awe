package ai.awe.registry;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Valid authoring documents, never used as a runtime or production data source. */
final class TypedModelFixtures {
  private static final JsonDocuments JSON=new JsonDocuments();
  static ObjectNode spec(String type) {
    String id=switch(type) {
      case "ObjectType" -> "entity.video.user";case "LinkType" -> "link.video.user_dataset";
      case "Dataset" -> "dataset.video.events";case "Metric" -> "metric.video.dau";
      case "Feature" -> "feature.video.active_days";case "FunctionDefinition" -> "function.video.query_dau";
      case "ActionDefinition" -> "action.video.rerun";case "ContractDefinition" -> "contract.video.events";
      case "EvidenceAssertion" -> "assertion.video.events";default -> throw new IllegalArgumentException(type);
    };
    String user="{\"id\":\"entity.video.user\",\"modelVersion\":\"1.0.0\"}";
    String dataset="{\"id\":\"dataset.video.events\",\"modelVersion\":\"1.0.0\"}";
    String temporal="\"grain\":{\"keys\":[\"event_id\"],\"kind\":\"EVENT\"},\"time\":{\"roles\":[\"EVENT_TIME\",\"AVAILABLE_AT\"],\"businessZone\":\"Asia/Shanghai\",\"backfillPossible\":true}";
    String fields=switch(type) {
      case "ObjectType" -> "\"family\":\"BUSINESS_ENTITY\",\"description\":\"Unique business user\",\"properties\":[{\"name\":\"user_id\",\"type\":\"STRING\",\"required\":true,\"sensitive\":false}],\"identityKeys\":[\"user_id\"]";
      case "LinkType" -> "\"fromType\":"+dataset+",\"toType\":"+user+",\"meaning\":\"HAS_DIMENSION\",\"cardinality\":\"MANY_TO_ONE\",\"temporalPolicy\":\"AS_OF\",\"identityMappingRef\":\"identity.video.user_map\",\"certified\":false";
      case "Dataset" -> temporal+",\"subjectType\":"+user+",\"dimensions\":[]";
      case "Metric" -> temporal+",\"subjectType\":"+user+",\"sourceDataset\":"+dataset+",\"aggregation\":{\"kind\":\"COUNT_DISTINCT\",\"permittedRollupAxes\":[],\"forbiddenRollupAxes\":[\"day\",\"app\"]},\"allowedDimensions\":[]";
      case "Feature" -> temporal+",\"subjectType\":"+user+",\"sourceDataset\":"+dataset+",\"observationTimeField\":\"event_time\",\"availableAtField\":\"available_at\"";
      case "FunctionDefinition" -> "\"ownerType\":"+dataset+",\"operation\":\"dataset.inspect\",\"parameters\":[{\"name\":\"date\",\"type\":\"DATE\",\"required\":true}],\"resultSchemaRef\":\"schema.video.events\",\"executorBindingRef\":\"adapter.hive.inspect\",\"requiredAssertions\":[]";
      case "ActionDefinition" -> "\"ownerType\":"+dataset+",\"operation\":\"dataset.backfill\",\"sideEffect\":\"DATA_BACKFILL\",\"executorBindingRef\":\"adapter.scheduler.backfill\",\"authorizationPolicyRef\":\"policy.video.write\",\"idempotencyKeySpec\":\"job.date\",\"requiresApproval\":true,\"rollbackOrCompensationRef\":\"restore.partition\",\"postconditionAssertions\":[\"assertion.video.partition_ready@1.0.0\"]";
      case "ContractDefinition" -> "\"subject\":"+dataset+",\"kind\":\"DATA\",\"ownerTeam\":\"video_dw\",\"obligations\":[{\"ruleId\":\"check.source.consistency\",\"at\":\"PUBLISH\",\"blocking\":true}]";
      case "EvidenceAssertion" -> "\"subject\":"+dataset+",\"kind\":\"SCHEMA\",\"result\":\"INCONCLUSIVE\",\"checkerRef\":\"checker.hive.schema\",\"modelReleaseHash\":\""+"a".repeat(64)+"\",\"sourceSnapshotRef\":\"snapshot:events-1\",\"checkedAt\":\"2026-11-12T00:00:00Z\",\"evidenceRefs\":[],\"limitations\":[\"No production quality reconciliation\"]";
      default -> throw new IllegalArgumentException(type);
    };
    return JSON.object("{\"schemaVersion\":2,\"name\":\"Model\",\"definition\":\"Governed model definition\",\"ownerTeam\":\"video_dw\",\"model\":{\"ref\":{\"id\":\""+id+"\",\"modelVersion\":\"1.0.0\"},"+fields+"}}");
  }
}
