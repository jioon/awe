-- 数据任务是已发布模型的消费者；保留原始失败证据，修复通过新任务重验。
CREATE TABLE awe_data_run (
 id UUID PRIMARY KEY,
 actor VARCHAR(100) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 request_doc JSONB NOT NULL,
 model_release_id UUID NOT NULL REFERENCES awe_release(id),
 model_content_hash CHAR(64) NOT NULL,
 contract_release_id UUID NOT NULL REFERENCES awe_release(id),
 contract_content_hash CHAR(64) NOT NULL,
 replay_of UUID REFERENCES awe_data_run(id),
 repair_note VARCHAR(2000) NOT NULL DEFAULT '',
 status VARCHAR(30) NOT NULL CHECK(status IN ('RUNNING','PASSED','FAILED','NOT_CONFIGURED','BLOCKED','UNAVAILABLE','INCONCLUSIVE')),
 source_snapshot_id UUID REFERENCES awe_source_snapshot(id),
 data_snapshot_ref VARCHAR(1000),
 rows_checked INTEGER NOT NULL DEFAULT 0,
 violations JSONB NOT NULL DEFAULT '{}',
 limitations JSONB NOT NULL DEFAULT '[]',
 gate_report JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 completed_at TIMESTAMPTZ,
 UNIQUE(actor,idempotency_key)
);
CREATE INDEX idx_data_run_created ON awe_data_run(created_at DESC);
CREATE TABLE awe_data_gap (
 id UUID PRIMARY KEY,
 original_run_id UUID NOT NULL UNIQUE REFERENCES awe_data_run(id),
 failed_run_id UUID NOT NULL REFERENCES awe_data_run(id),
 status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),
 reason VARCHAR(100) NOT NULL,
 closed_run_id UUID REFERENCES awe_data_run(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 closed_at TIMESTAMPTZ,
 CHECK ((status='OPEN' AND closed_run_id IS NULL AND closed_at IS NULL) OR
        (status='CLOSED' AND closed_run_id IS NOT NULL AND closed_at IS NOT NULL))
);
CREATE FUNCTION awe_protect_data_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Data run evidence cannot be deleted'; END IF;
 IF OLD.status <> 'RUNNING' THEN RAISE EXCEPTION 'Completed data run evidence is immutable'; END IF;
 IF NEW.status='RUNNING' OR NEW.completed_at IS NULL THEN RAISE EXCEPTION 'Data run must complete once'; END IF;
 IF ROW(NEW.id,NEW.actor,NEW.idempotency_key,NEW.request_hash,NEW.request_doc,NEW.model_release_id,NEW.model_content_hash,NEW.contract_release_id,NEW.contract_content_hash,NEW.replay_of,NEW.repair_note,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.actor,OLD.idempotency_key,OLD.request_hash,OLD.request_doc,OLD.model_release_id,OLD.model_content_hash,OLD.contract_release_id,OLD.contract_content_hash,OLD.replay_of,OLD.repair_note,OLD.created_at)
 THEN RAISE EXCEPTION 'Pinned data run request is immutable'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_data_run BEFORE UPDATE OR DELETE ON awe_data_run
 FOR EACH ROW EXECUTE FUNCTION awe_protect_data_run();
