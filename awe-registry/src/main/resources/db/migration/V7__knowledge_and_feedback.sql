-- Coverage is a reviewed scope declaration; it is never inferred from search success.
CREATE TABLE awe_knowledge_coverage (
 id UUID PRIMARY KEY,
 domain VARCHAR(80) NOT NULL REFERENCES awe_domain(code),
 version VARCHAR(50) NOT NULL,
 document JSONB NOT NULL,
 content_hash CHAR(64) NOT NULL,
 proposed_by VARCHAR(100) NOT NULL,
 valid_until TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(domain,version)
);
CREATE TABLE awe_knowledge_coverage_approval (
 coverage_id UUID PRIMARY KEY REFERENCES awe_knowledge_coverage(id),
 reviewed_by VARCHAR(100) NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE awe_resolution_observation (
 id UUID PRIMARY KEY,
 principal VARCHAR(100) NOT NULL,
 policy_hash CHAR(64) NOT NULL,
 request_doc JSONB NOT NULL,
 index_hash CHAR(64) NOT NULL,
 report JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_resolution_principal ON awe_resolution_observation(principal,created_at DESC);
CREATE TABLE awe_consumption_feedback (
 id UUID PRIMARY KEY,
 principal VARCHAR(100) NOT NULL,
 observation_id UUID NOT NULL,
 observation_kind VARCHAR(20) NOT NULL CHECK(observation_kind IN ('DATA','RESOLUTION')),
 scope_hash CHAR(64) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 issue_code VARCHAR(40) NOT NULL,
 stage VARCHAR(30) NOT NULL,
 reporter_doc JSONB NOT NULL,
 evidence_snapshot JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(principal,fingerprint)
);
CREATE INDEX idx_feedback_principal ON awe_consumption_feedback(principal,created_at DESC);
CREATE TABLE awe_feedback_occurrence (
 id UUID PRIMARY KEY,
 feedback_id UUID NOT NULL REFERENCES awe_consumption_feedback(id),
 observation_id UUID NOT NULL,
 reporter_doc JSONB NOT NULL,
 evidence_snapshot JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(feedback_id,observation_id)
);
CREATE INDEX idx_feedback_occurrence ON awe_feedback_occurrence(feedback_id,created_at DESC);
CREATE TABLE awe_feedback_review (
 id UUID PRIMARY KEY,
 feedback_id UUID NOT NULL REFERENCES awe_consumption_feedback(id),
 classification VARCHAR(40) NOT NULL,
 owner_team VARCHAR(200) NOT NULL,
 external_issue_ref VARCHAR(500) NOT NULL,
 note VARCHAR(2000) NOT NULL,
 reviewed_by VARCHAR(100) NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_feedback_review ON awe_feedback_review(feedback_id,reviewed_at DESC);
CREATE TABLE awe_regression_case (
 id UUID PRIMARY KEY,
 principal VARCHAR(100) NOT NULL,
 anchor_id UUID NOT NULL,
 observation_kind VARCHAR(20) NOT NULL CHECK(observation_kind IN ('DATA','RESOLUTION')),
 request_doc JSONB NOT NULL,
 scope_hash CHAR(64) NOT NULL,
 case_hash CHAR(64) NOT NULL,
 expected_state VARCHAR(50) NOT NULL,
 expected_fingerprint CHAR(64) NOT NULL,
 reference_basis VARCHAR(2000) NOT NULL,
 reference_evidence_ref VARCHAR(500) NOT NULL,
 proposed_by VARCHAR(100) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE awe_regression_case_approval (
 case_id UUID PRIMARY KEY REFERENCES awe_regression_case(id),
 case_hash CHAR(64) NOT NULL,
 reviewed_by VARCHAR(100) NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE awe_regression_replay (
 id UUID PRIMARY KEY,
 case_id UUID NOT NULL REFERENCES awe_regression_case(id),
 principal VARCHAR(100) NOT NULL,
 idempotency_key VARCHAR(120) NOT NULL,
 accepted_case_hash CHAR(64) NOT NULL,
 scope_hash CHAR(64) NOT NULL,
 actual_observation_id UUID NOT NULL,
 actual_state VARCHAR(50) NOT NULL,
 actual_fingerprint CHAR(64) NOT NULL,
 passed BOOLEAN NOT NULL,
 proof JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(principal,case_id,idempotency_key)
);
CREATE TABLE awe_feedback_closure (
 id UUID PRIMARY KEY,
 feedback_id UUID NOT NULL REFERENCES awe_consumption_feedback(id),
 replay_id UUID NOT NULL REFERENCES awe_regression_replay(id),
 review_id UUID NOT NULL REFERENCES awe_feedback_review(id),
 classification VARCHAR(40) NOT NULL,
 reviewed_by VARCHAR(100) NOT NULL,
 note VARCHAR(2000) NOT NULL,
 closed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_feedback_closure ON awe_feedback_closure(feedback_id,closed_at DESC);

DO $$ DECLARE t TEXT; BEGIN
 FOR t IN SELECT unnest(ARRAY['awe_knowledge_coverage','awe_knowledge_coverage_approval','awe_resolution_observation','awe_consumption_feedback','awe_feedback_occurrence','awe_feedback_review','awe_regression_case','awe_regression_case_approval','awe_regression_replay','awe_feedback_closure']) LOOP
  EXECUTE format('CREATE TRIGGER immutable_%I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION awe_reject_source_observation_mutation()',t,t);
 END LOOP;
END $$;
