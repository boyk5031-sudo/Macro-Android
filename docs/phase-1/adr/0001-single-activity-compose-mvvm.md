# ADR-0001 — Single-activity Jetpack Compose app with MVVM and a thin domain layer

- Status: Accepted (2026-09-23)
- Deciders: project owner, architect

## Context
The product is a moderately sized on-device tool with ~15 screens, no network layer, and one complex pure-Kotlin subsystem (the automation engine). The prompt allows "MVVM or Clean Architecture" and asks not to over-engineer.

## Decision
- One `MainActivity` hosting a Compose `NavHost`; a second, trivial `ShortcutTrampolineActivity` (no UI, `excludeFromRecents`, `noHistory`) for pinned shortcuts so launches are audited and re-resolved.
- MVVM with unidirectional data flow. Use cases only where logic is shared or complex (validation, import/export, run enqueue, schedule computation).
- Repositories are the single source of truth; the domain model lives in `automation:engine` (macro) and `core:common` (errors, results); Room entities are mapped explicitly.

## Consequences
- Less ceremony than full Clean Architecture; testability retained because all Android-dependent code sits behind interfaces at the data/infrastructure edge.
- Features cannot depend on each other; cross-feature actions go through interfaces in `core:common` bound in `app` (`MacroRunner`, `ConsentGate`, `Navigator` routes).
