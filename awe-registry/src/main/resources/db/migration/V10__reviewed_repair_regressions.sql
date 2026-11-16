-- 回归观测只记录一次受控数据调用的事实，不管理 Agent 工作流。
CREATE TABLE awe_regression_observation (
 id UUID PRIMARY KEY,
 principal VARCHAR(100) NOT NULL,
 policy_hash CHAR(64) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 request_doc JSONB NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('RUNNING','OBSERVED')),
 report JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 completed_at TIMESTAMPTZ,
 UNIQUE(principal,idempotency_key),
 CHECK((status='RUNNING' AND completed_at IS NULL) OR(status='OBSERVED' AND completed_at IS NOT NULL))
);
CREATE INDEX idx_regression_observation_principal ON awe_regression_observation(principal,created_at DESC);
CREATE FUNCTION awe_protect_regression_observation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'RUNNING' THEN RAISE EXCEPTION 'Regression observation is immutable'; END IF;
 IF NEW.status<>'OBSERVED' OR NEW.completed_at IS NULL OR
 ROW(NEW.id,NEW.principal,NEW.policy_hash,NEW.idempotency_key,NEW.request_hash,NEW.request_doc,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.principal,OLD.policy_hash,OLD.idempotency_key,OLD.request_hash,OLD.request_doc,OLD.created_at)
 THEN RAISE EXCEPTION 'Accepted regression request cannot be rewritten'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_regression_observation BEFORE UPDATE OR DELETE ON awe_regression_observation
 FOR EACH ROW EXECUTE FUNCTION awe_protect_regression_observation();
-- 新期望是追加 facet，旧 case_hash、参考和审批不升级或重写。
CREATE TABLE awe_regression_expectation (
 case_id UUID PRIMARY KEY REFERENCES awe_regression_case(id),
 expectation JSONB NOT NULL,
 expectation_hash CHAR(64) NOT NULL
);
CREATE TABLE awe_obligation_migration (
 id UUID PRIMARY KEY,
 feedback_id UUID NOT NULL REFERENCES awe_consumption_feedback(id),
 principal VARCHAR(100) NOT NULL,
 from_case_id UUID NOT NULL REFERENCES awe_regression_case(id),
 to_case_id UUID NOT NULL REFERENCES awe_regression_case(id),
 proposal_doc JSONB NOT NULL,
 migration_hash CHAR(64) NOT NULL,
 proposed_by VARCHAR(100) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(principal,idempotency_key)
);
CREATE TABLE awe_obligation_migration_approval (
 migration_id UUID PRIMARY KEY REFERENCES awe_obligation_migration(id),
 migration_hash CHAR(64) NOT NULL,
 reviewed_by VARCHAR(100) NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE awe_obligation_migration_resolution (
 id UUID PRIMARY KEY,
 migration_id UUID NOT NULL REFERENCES awe_obligation_migration(id),
 replay_id UUID NOT NULL REFERENCES awe_regression_replay(id),
 review_id UUID NOT NULL REFERENCES awe_feedback_review(id),
 reviewed_by VARCHAR(100) NOT NULL,
 note VARCHAR(2000) NOT NULL,
 resolved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(migration_id,replay_id)
);
DO $$ DECLARE t TEXT; BEGIN
 FOR t IN SELECT unnest(ARRAY['awe_regression_expectation','awe_obligation_migration','awe_obligation_migration_approval','awe_obligation_migration_resolution']) LOOP
  EXECUTE format('CREATE TRIGGER immutable_%I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation()',t,t);
 END LOOP;
END $$;
