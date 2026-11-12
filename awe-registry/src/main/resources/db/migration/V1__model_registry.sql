CREATE TABLE awe_domain (
 code VARCHAR(80) PRIMARY KEY, display_name VARCHAR(200) NOT NULL,
 owner_team VARCHAR(160) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE awe_draft (
 id UUID PRIMARY KEY, domain VARCHAR(80) NOT NULL REFERENCES awe_domain(code),
 entity_id VARCHAR(240) NOT NULL, entity_type VARCHAR(40) NOT NULL,
 spec JSONB NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
 state VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
 author_actor VARCHAR(100) NOT NULL, reviewed_by VARCHAR(100),
 reviewed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT chk_awe_draft_state CHECK(state IN ('DRAFT','IN_REVIEW','APPROVED','PUBLISHED'))
);
CREATE INDEX idx_draft_domain ON awe_draft(domain,updated_at DESC);
CREATE TABLE awe_release (
 id UUID PRIMARY KEY, draft_id UUID NOT NULL REFERENCES awe_draft(id),
 domain VARCHAR(80) NOT NULL REFERENCES awe_domain(code),
 entity_id VARCHAR(240) NOT NULL, entity_type VARCHAR(40) NOT NULL,
 version VARCHAR(50) NOT NULL, content_hash CHAR(64) NOT NULL,
 spec JSONB NOT NULL, published_by VARCHAR(100) NOT NULL,
 published_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(entity_id,version)
);
CREATE INDEX idx_release_entity ON awe_release(entity_id,published_at DESC);
CREATE TABLE awe_release_relation (
 release_id UUID NOT NULL REFERENCES awe_release(id),
 target_entity_id VARCHAR(240) NOT NULL, relation_type VARCHAR(50) NOT NULL,
 PRIMARY KEY(release_id,target_entity_id,relation_type)
);
CREATE INDEX idx_relation_target ON awe_release_relation(target_entity_id);
CREATE TABLE awe_source_binding (
 release_id UUID NOT NULL REFERENCES awe_release(id),
 source_system VARCHAR(100) NOT NULL, external_ref VARCHAR(500) NOT NULL,
 source_fingerprint VARCHAR(250) NOT NULL,
 verification_status VARCHAR(30) NOT NULL DEFAULT 'NOT_VERIFIED',
 PRIMARY KEY(release_id,source_system,external_ref)
);
CREATE TABLE awe_audit (
 id BIGSERIAL PRIMARY KEY, entity_id VARCHAR(240), draft_id UUID,
 event_type VARCHAR(50) NOT NULL, actor VARCHAR(100) NOT NULL,
 details JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_entity ON awe_audit(entity_id,created_at DESC);
