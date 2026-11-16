# AWE — Analytical World Engine

AWE is the shared modeling, production, consumption and verification foundation for an AI-native data warehouse. Immutable entities, semantic models, contracts and capabilities connect existing tables, SQL, jobs and reports to governed releases and inspectable evidence. Hive, Spark, BI and scheduling systems retain their execution and storage roles.

The existing unified knowledge base remains the external product surface. AWE is its internal semantic, capability-decision and controlled-consumption foundation. Discovery pages and documents explain published knowledge; data-task decisions and evidence come from the governed system rather than each downstream Agent's interpretation of retrieved text.

AWE provides shared semantics, capabilities, constraints and evidence. Downstream Agents own overall task decisions, workflows, code changes and deliverables; existing platforms perform physical execution. DataTask is a lightweight, versioned data-capability request and validation context, not a general workflow engine or an eighth model family. See [ADR-016: DataTask and responsibility boundaries](docs/architecture/adr-016-data-task-and-responsibility-boundaries.md).

Roll out one mature domain at a time: reconcile existing assets and establish task coverage; deliver reliable domain Q&A and extraction; enforce admission for new and changed assets in that domain; repeat for the next domain. Existing BI, metric and MCP interfaces can be governed execution adapters. Legacy direct SQL/MCP fallback is a separately authorized, traced exception and cannot bypass denied access or invalid semantics.

The product target includes the F3 warehouse improvement loop, while trusted consumption is the next main product milestone. F0–F6 are a capability map, not a requirement to finish every management subsystem before useful consumption. Individual vertical slices do not establish completion of every stage. See [ADR-015: domain knowledge and controlled consumption](docs/architecture/adr-015-domain-knowledge-and-controlled-consumption.md) for the updated authority, rollout, task and acceptance contracts.

## Build and development

Use a full JDK 21, Maven 3.9+ and PostgreSQL for integration tests. From the repository root:

```sh
mvn -B -ntp spotless:apply
mvn -B -ntp spotless:check verify
```

Spotless uses Google Java Format in AOSP style with four-space Java indentation. CI enforces formatting and requires executed PostgreSQL/HTTP integration tests. Runtime setup, configuration and API examples are in the [Registry guide](awe-registry/README.md).

## Current implementation

- Seven model families share the Registry, immutable releases, source gates and workbench. Strict v3 facets add executable Metric, Dataset and Pipeline contracts while preserving v2 canonical releases and hashes.
- DataTask v1 EXTRACT and v2 QUERY_METRIC drive actual bounded PostgreSQL reads, typed relational plans and version-bound BI DIRECT calls. OIDC delegation and server policies constrain input/output fields, purposes, expanded windows and mandatory row filters. REST and MCP share the same services.
- Authorized knowledge discovery, reviewed coverage and aliases retain ambiguity/unknown states. Scoped feedback, reviewed references and real replays connect failures to externally managed repairs. Reviewed model replacements retain the original failure and require fresh replay after approval; typed refusal/readiness regressions test control outcomes separately from data values.
- Pipeline artifacts bind formal transformation intent to actual platform bytes, transitive/shared-asset impact and independent regression. Short-lived online one-time receipts bind the full shared-target revision vector and monotonic write intents for atomic platform CAS deployment; verified realizations make newly materialized logical datasets consumable without changing their release hashes. New writes fence old semantic proofs; consumption rechecks actual active target ownership instead of relying on historical PASSED records.
- Real PostgreSQL/HTTP tests execute candidate and independent SQL, deployment into actual new tables and subsequent queries, including drift, revoked access, wrong results/artifacts, incomplete coverage, expired proofs and concurrent admission. Missing connections return NOT_CONFIGURED.

M1/M2 are not fully complete. Structured data requirements still need server-side legal planning and capability assessment beyond selecting a published Function; domain identity/event/time rules, release retirement and consumer migration, and bounded admission for existing external SQL/artifacts remain engineering work. Enterprise identity/platform integration, domain acceptance and enforced rollout remain separate. Arbitrary SQL equivalence, unrestricted natural-language analysis, AS_OF/SCD, many-to-many joins, historical snapshot restoration and autonomous engineering actions are not established by these tests. Existing platforms perform physical execution and deployment.

See the [implementation plan](docs/implementation-plan.md), [ADR-018](docs/architecture/adr-018-executable-capabilities-and-production-admission.md), [capability APIs](awe-registry/CAPABILITIES.md), [identity guide](awe-registry/IDENTITY.md), [repair regression guide](awe-registry/REPAIR.md) and [production admission guide](awe-registry/PRODUCTION.md).

## Agent Skills and custom workflows

Agents can keep Skills and workflows in their own repositories or platforms, combine business steps, and call AWE's stable APIs with explicit release IDs and content hashes. AWE owns server-side authorization, registered checks, model/source validation and evidence. A custom Skill cannot turn missing configuration, incomplete coverage or a failed proof obligation into a successful execution.

Discovery/coverage, resolution, capability, access, readiness, execution and verification must remain distinct from externally reported task acceptance. AWE owns its data-operation states, not the consumer's overall workflow or success decision. Caller-proposed models and purposes require server resolution and authorization. A certified data result does not certify every downstream explanation; governance cannot control all offline reasoning over previously exported content. DataTask v1 retains extraction compatibility; v2 adds governed metric operations. Enterprise platform connections and business acceptance must be established independently.

See [ADR-014: Agent Skills and the data verification loop](docs/architecture/adr-014-agent-skills-and-data-verification-loop.md), [ADR-013: typed source gates](docs/architecture/adr-013-typed-registry-and-source-gates.md), the [architecture decisions](docs/architecture/decisions.md), [guardrails](docs/architecture/guardrails.md), [scenario matrix](docs/architecture/scenario-matrix.md) and [roadmap](docs/roadmap.md).

## Code boundaries

The framework-independent kernel separates `model`, `contract`, `identity`, `release` and `capability`. The application separates `api`, `authoring`, `model`, `audit`, `support`, `source`, `runtime`, `consumption`, `identity`, `knowledge` and `production`. Keeping the sealed metamodel in one package preserves its closed type boundary; protocol, persistence and execution adapters do not enter the kernel.

Semantic identity, physical implementation, source observations, run evidence and task completion remain separate facts. Model approval is governance; a scoped check is evidence for its named obligation, not absolute business truth.
