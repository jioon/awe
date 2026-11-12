# AWE F1a: Persistent model authoring and publishing

This is a **real PostgreSQL-backed authoring service**, not an LLM or query-execution mock.
It is one increment toward F1, not the whole AWE system.

## Prerequisites
Java 21, Maven 3.9+, PostgreSQL 15+ database created in advance.

Set variables (values shown below are local development placeholders, never production credentials):

- AWE_DB_URL = jdbc:postgresql://127.0.0.1:5432/awe
- AWE_DB_USER = awe
- AWE_DB_PASSWORD = (your own local database password)
- AWE_AUTHOR_TOKEN = (long random token)
- AWE_REVIEWER_TOKEN = (different long random token)
- AWE_BIND_ADDRESS = 127.0.0.1 (default)

Build and start from repository root:
~~~
mvn -B -ntp verify
mvn -pl awe-registry -am spring-boot:run
~~~
If plugin execution targets the aggregator instead of runnable child, use:
~~~
mvn -B -ntp -pl awe-kernel install
mvn -B -ntp -pl awe-registry spring-boot:run
~~~
Open http://127.0.0.1:8090 . The page prompts for a bearer token and holds it in volatile JS memory.

## HTTP endpoints
- GET/POST /api/domains
- GET/POST /api/drafts
- GET/PUT /api/drafts/{id}
- POST /api/drafts/{id}/submit (author)
- POST /api/drafts/{id}/approve (reviewer)
- POST /api/drafts/{id}/publish (reviewer, request {"version":"1.0.0"})
- GET /api/releases , GET /api/releases/{id}
- GET /api/impact?entityId=...
- GET /api/audit?entityId=...

Published versions are immutable; edits create a new draft, never mutate a release.
Dataset publication requires an explicit binding with fingerprint. **Bindings are registered as NOT_VERIFIED until actual BI/Hive source comparison is implemented.** Publishing asserts approval and static validation, not real-world data truth. References must already have published models.

## Security limitations / production gate
F1a uses two distinct bootstrap **service** bearer tokens for author and reviewer, checked on every API request. They are not individual human identities and cannot supply domain-level access control. By default the server listens only on loopback. Before exposure beyond an isolated development environment, implement OIDC/SSO, real reviewer identity, domain-level RBAC, HTTPS, CSRF/cross-origin review, limits, audit redaction and secret management. **Do not expose F1a directly to an enterprise network or public internet.**

## Integration tests
The CI workflow provisions PostgreSQL and sets AWE_TEST_POSTGRES=true.
Integration tests validate Flyway migration, draft workflow, reviewer separation at API filter, immutable version, source binding, audit and dependency impact. Without AWE_TEST_POSTGRES, only unit tests run.

## Next F1 engineering increments
- Real model import adapters for Hive DDL, BI metric catalog and existing knowledge product.
- Physical binding drift detection (fingerprints compared to authoritative sources).
- Domain/role security integration, review provenance and policy-version pinning.
- Model compatibility/impact through SQL and downstream assets, not only direct semantic links.
- Strongly typed model schemas, JSON Schema publish checks, and safe migration from historical models.
- More complete Web ontology graph and publication review experience.
