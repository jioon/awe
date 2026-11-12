# AWE — Analytical World Engine

AWE is the shared modeling, production, consumption and verification foundation for an AI-native data warehouse. Immutable entities, semantic models, contracts and capabilities connect existing tables, SQL, jobs and reports to governed releases and inspectable evidence. Hive, Spark, BI and scheduling systems retain their execution and storage roles.

The existing unified knowledge base remains the external product surface. AWE is its internal semantic, capability-decision and controlled-consumption foundation. Discovery pages and documents explain published knowledge; data-task decisions and evidence come from the governed system rather than each downstream Agent's interpretation of retrieved text.

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

- Seven Java model families share the Registry, strict v2 documents and Web workbench. Releases pin dependency versions, hashes and source provenance; metadata and compatibility checks gate publication.
- Configured Hive/BI metadata gateways provide immutable observations. Missing configuration returns `NOT_CONFIGURED`; protocol fixtures do not claim an enterprise connection.
- A registered read-only data-check runtime consumes exact Dataset and Contract releases, checks fresh metadata, and validates actual rows in an administrator-mapped PostgreSQL relation. Runs preserve transaction snapshot references, coverage limits and violations.
- Failed data obligations create runtime gaps. After an external repair of the actual data, replay reuses the same pinned model and contract; only successful matching checks against the same bound data scope close the corresponding gap. Completed run evidence stays immutable.

This runtime is a bounded verification capability. Native Hive/Spark/BI execution, the full Resolver/Semantic IR query path, production job/DDL/SQL integration, enterprise identity and cross-consumer regression remain future work. It does not execute arbitrary caller SQL, scripts, Actions or warehouse writes.

## Agent Skills and custom workflows

Agents can keep Skills and workflows in their own repositories or platforms, combine business steps, and call AWE's stable APIs with explicit release IDs and content hashes. AWE owns server-side authorization, registered checks, model/source validation and evidence. A custom Skill cannot turn missing configuration, incomplete coverage or a failed proof obligation into a successful execution.

Discovery/coverage, resolution, capability, access, readiness, execution, verification and user task completion must remain distinct. A certified data result does not certify every downstream explanation; governance cannot control all offline reasoning over previously exported content. These are target contracts beyond the current three-check runtime, not already implemented Q&A or extraction APIs.

See [ADR-014: Agent Skills and the data verification loop](docs/architecture/adr-014-agent-skills-and-data-verification-loop.md), [ADR-013: typed source gates](docs/architecture/adr-013-typed-registry-and-source-gates.md), the [architecture decisions](docs/architecture/decisions.md), [guardrails](docs/architecture/guardrails.md), [scenario matrix](docs/architecture/scenario-matrix.md) and [roadmap](docs/roadmap.md).

## Code boundaries

The framework-independent kernel separates `model`, `contract`, `identity`, `release` and `capability`. The application separates `api`, `authoring`, `model`, `audit`, `support`, `source` and `runtime`. Keeping the sealed metamodel in one package preserves its closed type boundary; protocol, persistence and execution adapters do not enter the kernel.

Semantic identity, physical implementation, source observations, run evidence and task completion remain separate facts. Model approval is governance; a scoped check is evidence for its named obligation, not absolute business truth.
