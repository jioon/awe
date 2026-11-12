-- V1 releases remain unchanged and readable. Only new v2 releases use typed envelopes.
ALTER TABLE awe_release ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE awe_release ADD COLUMN canonical_document JSONB;
ALTER TABLE awe_release ALTER COLUMN published_at SET DEFAULT clock_timestamp();
ALTER TABLE awe_release_relation ADD COLUMN target_version VARCHAR(50);
ALTER TABLE awe_release_relation ADD COLUMN target_release_id UUID REFERENCES awe_release(id);
ALTER TABLE awe_release_relation ADD COLUMN target_content_hash CHAR(64);
ALTER TABLE awe_source_binding ADD COLUMN source_snapshot_id UUID REFERENCES awe_source_snapshot(id);
ALTER TABLE awe_source_binding ADD COLUMN verified_at TIMESTAMPTZ;
CREATE TABLE awe_gap (
 id UUID PRIMARY KEY,
 release_id UUID NOT NULL REFERENCES awe_release(id),
 entity_id VARCHAR(240) NOT NULL,
 source_system VARCHAR(100) NOT NULL,
 external_ref VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
 reason VARCHAR(80) NOT NULL,
 details JSONB NOT NULL,
 occurrences INTEGER NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_checked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 resolved_at TIMESTAMPTZ,
 UNIQUE(release_id,source_system,external_ref)
);
CREATE INDEX idx_gap_status ON awe_gap(status,last_checked_at DESC);
CREATE TABLE awe_model_check (
 id BIGSERIAL PRIMARY KEY,
 entity_id VARCHAR(240) NOT NULL,
 model_version VARCHAR(50) NOT NULL,
 candidate_hash CHAR(64) NOT NULL,
 status VARCHAR(40) NOT NULL,
 details JSONB NOT NULL,
 checked_by VARCHAR(100) NOT NULL,
 checked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_model_check_entity ON awe_model_check(entity_id,checked_at DESC);
CREATE TRIGGER awe_model_check_immutable BEFORE UPDATE OR DELETE ON awe_model_check
 FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
-- Runtime verification is append-only in separate observation tables, never a release rewrite.
CREATE FUNCTION awe_reject_release_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Published AWE release manifests are immutable'; END;
$$;
CREATE TRIGGER awe_release_immutable BEFORE UPDATE OR DELETE ON awe_release
 FOR EACH ROW EXECUTE FUNCTION awe_reject_release_mutation();
CREATE TRIGGER awe_release_relation_immutable BEFORE UPDATE OR DELETE ON awe_release_relation
 FOR EACH ROW EXECUTE FUNCTION awe_reject_release_mutation();
CREATE TRIGGER awe_source_binding_immutable BEFORE UPDATE OR DELETE ON awe_source_binding
 FOR EACH ROW EXECUTE FUNCTION awe_reject_release_mutation();
