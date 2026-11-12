# AWE — Analytical World Engine

Long-lived Java engineering project for an AI-native data warehouse: typed and versioned domain/analytical/engineering models, governed compilation and derivation, authoritative physical bindings, evidence, and task-driven capability evolution. Agent frameworks are external consumers; AWE is not another LLM agent.

## Build
JDK 21 and Maven 3.9+: `mvn -B -ntp verify`.

## This increment
The first **production-oriented foundation** is a framework-independent kernel of immutable contracts and executable invariants, verified by automated CI tests. It does **not** claim to implement the full service. We are deliberately not shipping another mock SQL/LLM demo.

Read [architecture decisions](docs/architecture/decisions.md), [failure modes](docs/architecture/guardrails.md), [scenario matrix](docs/architecture/scenario-matrix.md) and [delivery plan](docs/roadmap.md). The next incremental implementation is a real versioned model registry with PostgreSQL, workflow and Web UI.

## Non-negotiable
- Semantic identity, physical identity and runtime facts are distinct.
- Grain, identity, time and aggregation rules are first-class typed contracts.
- User-supplied text/LLM output can propose candidates but never become authoritative data semantics or permissions.
- A missing catalog hit is UNKNOWN unless coverage is explicitly certified complete.
- Authorization, readiness, verifiability and task completion are separate states.
- Model publishing and changes must be checked against actual warehouse bindings.
