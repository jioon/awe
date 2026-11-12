-- Immutable authoritative metadata observations, separated from semantic releases and row-level evidence.
CREATE TABLE awe_source_snapshot (
 id UUID PRIMARY KEY,
 source_system VARCHAR(100) NOT NULL,
 external_ref VARCHAR(500) NOT NULL,
 source_version VARCHAR(250) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 metadata JSONB NOT NULL,
 observed_by VARCHAR(100) NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_source_snapshot_asset ON awe_source_snapshot(source_system,external_ref,observed_at DESC);
CREATE TABLE awe_source_check (
 id BIGSERIAL PRIMARY KEY,
 source_system VARCHAR(100) NOT NULL,
 external_ref VARCHAR(500) NOT NULL,
 expected_fingerprint CHAR(64),
 observed_fingerprint CHAR(64),
 snapshot_id UUID REFERENCES awe_source_snapshot(id),
 status VARCHAR(30) NOT NULL CHECK (status IN ('IMPORTED','VERIFIED','DRIFTED','NOT_CONFIGURED','NOT_FOUND','UNAVAILABLE','INVALID_RESPONSE')),
 checked_by VARCHAR(100) NOT NULL,
 checked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 limitations JSONB NOT NULL
);
CREATE INDEX idx_source_check_asset ON awe_source_check(source_system,external_ref,checked_at DESC);
-- Application code and ordinary SQL must not rewrite the provenance used by a published release.
CREATE FUNCTION awe_reject_source_observation_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Source observations and checks are append-only';
END;
$$;
CREATE TRIGGER immutable_source_snapshot BEFORE UPDATE OR DELETE ON awe_source_snapshot
 FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
CREATE TRIGGER immutable_source_check BEFORE UPDATE OR DELETE ON awe_source_check
 FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation();
ALTER TABLE awe_source_snapshot ADD CONSTRAINT chk_source_snapshot_fingerprint CHECK (fingerprint ~ '^[a-f0-9]{64}$');
ALTER TABLE awe_source_snapshot ADD CONSTRAINT chk_source_snapshot_metadata CHECK (jsonb_typeof(metadata)='object');
ALTER TABLE awe_source_check ADD CONSTRAINT chk_source_check_verified CHECK (
 status NOT IN ('VERIFIED','DRIFTED','IMPORTED') OR
 (snapshot_id IS NOT NULL AND observed_fingerprint IS NOT NULL)
);
