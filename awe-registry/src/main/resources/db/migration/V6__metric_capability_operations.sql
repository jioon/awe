CREATE TABLE awe_metric_task (
 id UUID PRIMARY KEY,
 principal VARCHAR(100) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 request_doc JSONB NOT NULL,
 function_release_id UUID NOT NULL REFERENCES awe_release(id),
 function_content_hash CHAR(64) NOT NULL,
 policy_hash CHAR(64) NOT NULL,
 resolved JSONB NOT NULL,
 status VARCHAR(40) NOT NULL CHECK(status IN ('RUNNING','QUERIED','NOT_READY','NOT_CONFIGURED','BLOCKED','UNAVAILABLE','INCONCLUSIVE','FAILED_VERIFICATION')),
 report JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 completed_at TIMESTAMPTZ,
 UNIQUE(principal,idempotency_key),
 CHECK((status='RUNNING' AND completed_at IS NULL) OR(status<>'RUNNING' AND completed_at IS NOT NULL))
);
CREATE INDEX idx_metric_task_principal ON awe_metric_task(principal,created_at DESC);
CREATE TABLE awe_metric_task_result (
 operation_id UUID PRIMARY KEY REFERENCES awe_metric_task(id),
 rows_doc JSONB NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_metric_task_result_expiry ON awe_metric_task_result(expires_at);
CREATE TRIGGER immutable_metric_payload BEFORE UPDATE ON awe_metric_task_result
 FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE FUNCTION awe_protect_metric_task() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Metric operation evidence cannot be deleted'; END IF;
 IF OLD.status<>'RUNNING' THEN RAISE EXCEPTION 'Completed metric operation is immutable'; END IF;
 IF NEW.status='RUNNING' OR NEW.completed_at IS NULL THEN RAISE EXCEPTION 'Metric operation must complete once'; END IF;
 IF ROW(NEW.id,NEW.principal,NEW.idempotency_key,NEW.request_hash,NEW.request_doc,NEW.function_release_id,NEW.function_content_hash,NEW.policy_hash,NEW.resolved,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.principal,OLD.idempotency_key,OLD.request_hash,OLD.request_doc,OLD.function_release_id,OLD.function_content_hash,OLD.policy_hash,OLD.resolved,OLD.created_at)
 THEN RAISE EXCEPTION 'Accepted metric request, policy and model manifest are immutable'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_metric_task BEFORE UPDATE OR DELETE ON awe_metric_task
 FOR EACH ROW EXECUTE FUNCTION awe_protect_metric_task();
