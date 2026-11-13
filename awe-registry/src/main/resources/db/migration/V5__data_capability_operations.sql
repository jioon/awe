-- 这里只记录 AWE 数据操作；外部工作流和业务交付不在本表建状态机。
CREATE TABLE awe_data_task (
 id UUID PRIMARY KEY,
 principal VARCHAR(100) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 request_doc JSONB NOT NULL,
 function_release_id UUID NOT NULL REFERENCES awe_release(id),
 function_content_hash CHAR(64) NOT NULL,
 policy_hash CHAR(64) NOT NULL,
 resolved JSONB NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('RUNNING','EXTRACTED','NOT_CONFIGURED','BLOCKED','UNAVAILABLE','INCONCLUSIVE')),
 report JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 completed_at TIMESTAMPTZ,
 UNIQUE(principal,idempotency_key),
 CHECK ((status='RUNNING' AND completed_at IS NULL) OR (status<>'RUNNING' AND completed_at IS NOT NULL))
);
CREATE INDEX idx_data_task_principal ON awe_data_task(principal,created_at DESC);
-- 数据载荷与永久证明分离；载荷可到期清理，证据仍保留结果摘要与 Hash。
CREATE TABLE awe_data_task_result (
 operation_id UUID PRIMARY KEY REFERENCES awe_data_task(id),
 rows_doc JSONB NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_data_task_result_expiry ON awe_data_task_result(expires_at);
CREATE TRIGGER immutable_data_task_payload BEFORE UPDATE ON awe_data_task_result
 FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE FUNCTION awe_protect_data_task() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Data capability evidence cannot be deleted'; END IF;
 IF OLD.status <> 'RUNNING' THEN RAISE EXCEPTION 'Completed data capability evidence is immutable'; END IF;
 IF NEW.status='RUNNING' OR NEW.completed_at IS NULL THEN RAISE EXCEPTION 'Data capability operation must complete once'; END IF;
 IF ROW(NEW.id,NEW.principal,NEW.idempotency_key,NEW.request_hash,NEW.request_doc,NEW.function_release_id,NEW.function_content_hash,NEW.policy_hash,NEW.resolved,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.principal,OLD.idempotency_key,OLD.request_hash,OLD.request_doc,OLD.function_release_id,OLD.function_content_hash,OLD.policy_hash,OLD.resolved,OLD.created_at)
 THEN RAISE EXCEPTION 'Accepted request, policy and resolved releases are immutable'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_data_task BEFORE UPDATE OR DELETE ON awe_data_task
 FOR EACH ROW EXECUTE FUNCTION awe_protect_data_task();
