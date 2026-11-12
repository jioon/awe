# AWE — Analytical World Engine

Long-lived Java engineering project for an AI-native data warehouse: typed and versioned domain/analytical/engineering models, governed compilation and derivation, authoritative physical bindings, evidence, and task-driven capability evolution. Agent frameworks are external consumers; AWE is not another LLM agent.

## Build
JDK 21 and Maven 3.9+: `mvn -B -ntp verify`.

## Current implementation
The Java 21 kernel defines seven model families. The PostgreSQL Registry, HTTP API and Web workbench share their strict versioned representation, enforce immutable releases and versioned dependencies, and gate publication on model compatibility and fresh source metadata checks. Hive/BI imports use an extensible metadata gateway adapter; unconfigured sources explicitly return `NOT_CONFIGURED`.

This is an increment toward the production AWE product. It does not yet execute warehouse tasks, prove row-level quality or BI numerical equivalence, or complete a repair/replay workflow. Local gateway fixtures validate the adapter protocol without claiming an enterprise Hive/BI connection. See the [Registry setup and API](awe-registry/README.md) and [ADR-013](docs/architecture/adr-013-typed-registry-and-source-gates.md).

Read [architecture decisions](docs/architecture/decisions.md), [failure modes](docs/architecture/guardrails.md), [scenario matrix](docs/architecture/scenario-matrix.md) and [delivery plan](docs/roadmap.md). The next product milestone is a real domain's source-to-model-to-task validation and repair loop, built on this Registry.

## Non-negotiable
- Semantic identity, physical identity and runtime facts are distinct.
- Grain, identity, time and aggregation rules are first-class typed contracts.
- User-supplied text/LLM output can propose candidates but never become authoritative data semantics or permissions.
- A missing catalog hit is UNKNOWN unless coverage is explicitly certified complete.
- Authorization, readiness, verifiability and task completion are separate states.
- Model publishing and changes must be checked against actual warehouse bindings.
