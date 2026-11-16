-- 物理目标写意图是授权代次，不是调度任务。共享目标按环境隔离、追加且不可回退。
CREATE TABLE awe_production_target_intent (
 id BIGSERIAL PRIMARY KEY,
 environment VARCHAR(120) NOT NULL,
 target_key VARCHAR(600) NOT NULL,
 consumption_id UUID NOT NULL REFERENCES awe_production_consumption(id) DEFERRABLE INITIALLY DEFERRED,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(environment,target_key,consumption_id)
);
CREATE INDEX idx_target_intent_current ON awe_production_target_intent(environment,target_key,id DESC);
CREATE TRIGGER immutable_target_intent BEFORE UPDATE OR DELETE ON awe_production_target_intent
 FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
