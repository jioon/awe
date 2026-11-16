-- 正式加工准入：接受上下文、检查、审批、消费、物化证据分别追加，不管理外部部署工作流。
CREATE TABLE awe_production_proposal (
 id UUID PRIMARY KEY,
 created_by VARCHAR(100) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 request_doc JSONB NOT NULL,
 pipeline_release_id UUID NOT NULL REFERENCES awe_release(id),
 pipeline_content_hash CHAR(64) NOT NULL,
 pipeline_entity_id VARCHAR(240) NOT NULL,
 environment VARCHAR(120) NOT NULL,
 artifact_id VARCHAR(240) NOT NULL,
 expected_artifact_hash CHAR(64) NOT NULL,
 artifact_doc JSONB NOT NULL,
 impact_doc JSONB NOT NULL,
 impact_hash CHAR(64) NOT NULL,
 baseline_doc JSONB NOT NULL,
 platform_config_hash CHAR(64) NOT NULL,
 target_config_hash CHAR(64) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('PREPARED','NOT_CONFIGURED','UNAVAILABLE','BLOCKED')),
 limitations JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(created_by,idempotency_key)
);
CREATE TABLE awe_production_check (
 id UUID PRIMARY KEY,
 proposal_id UUID NOT NULL REFERENCES awe_production_proposal(id),
 checked_by VARCHAR(100) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('PASSED','FAILED','NOT_CONFIGURED','UNAVAILABLE','STALE','INCONCLUSIVE')),
 context_hash CHAR(64) NOT NULL,
 evidence_doc JSONB NOT NULL,
 checked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_production_check_proposal ON awe_production_check(proposal_id,checked_at DESC);
CREATE TABLE awe_production_receipt (
 id UUID PRIMARY KEY,
 proposal_id UUID NOT NULL REFERENCES awe_production_proposal(id),
 check_id UUID NOT NULL UNIQUE REFERENCES awe_production_check(id),
 approved_by VARCHAR(100) NOT NULL,
 receipt_hash CHAR(64) NOT NULL,
 receipt_doc JSONB NOT NULL,
 approved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE awe_production_consumption (
 id UUID PRIMARY KEY,
 proposal_id UUID NOT NULL UNIQUE REFERENCES awe_production_proposal(id),
 receipt_id UUID NOT NULL UNIQUE REFERENCES awe_production_receipt(id),
 platform_actor VARCHAR(100) NOT NULL,
 platform_policy_hash CHAR(64) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 authorization_doc JSONB NOT NULL,
 consumed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_production_consumption_targets ON awe_production_consumption USING GIN(authorization_doc);
CREATE TABLE awe_dataset_realization (
 id UUID PRIMARY KEY,
 proposal_id UUID NOT NULL REFERENCES awe_production_proposal(id),
 consumption_id UUID NOT NULL REFERENCES awe_production_consumption(id),
 dataset_release_id UUID NOT NULL REFERENCES awe_release(id),
 dataset_content_hash CHAR(64) NOT NULL,
 row_schema_release_id UUID NOT NULL REFERENCES awe_release(id),
 row_schema_content_hash CHAR(64) NOT NULL,
 deployment_ref VARCHAR(240) NOT NULL,
 run_ref VARCHAR(240) NOT NULL,
 attempt_ref VARCHAR(120) NOT NULL,
 artifact_hash CHAR(64) NOT NULL,
 target_binding_ref VARCHAR(240) NOT NULL,
 binding_doc JSONB NOT NULL,
 source_snapshot_id UUID NOT NULL REFERENCES awe_source_snapshot(id),
 evidence_doc JSONB NOT NULL,
 observed_by VARCHAR(100) NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(consumption_id,dataset_release_id)
);
CREATE TABLE awe_production_realization_attempt (
 id UUID PRIMARY KEY,
 proposal_id UUID NOT NULL REFERENCES awe_production_proposal(id),
 consumption_id UUID NOT NULL REFERENCES awe_production_consumption(id),
 platform_actor VARCHAR(100) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 physical_targets JSONB NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('PASSED','BLOCKED','NOT_CONFIGURED','UNAVAILABLE','INCONCLUSIVE')),
 report_doc JSONB NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(consumption_id,idempotency_key)
);
CREATE INDEX idx_realization_attempt_time ON awe_production_realization_attempt(observed_at DESC);
CREATE INDEX idx_dataset_realization_release ON awe_dataset_realization(dataset_release_id,observed_at DESC);
CREATE TRIGGER immutable_production_proposal BEFORE UPDATE OR DELETE ON awe_production_proposal FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE TRIGGER immutable_production_check BEFORE UPDATE OR DELETE ON awe_production_check FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE TRIGGER immutable_production_receipt BEFORE UPDATE OR DELETE ON awe_production_receipt FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE TRIGGER immutable_production_consumption BEFORE UPDATE OR DELETE ON awe_production_consumption FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE TRIGGER immutable_dataset_realization BEFORE UPDATE OR DELETE ON awe_dataset_realization FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE TRIGGER immutable_realization_attempt BEFORE UPDATE OR DELETE ON awe_production_realization_attempt FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
