# Phase 1 — Product and Technical Specification

Status: **complete, awaiting confirmation before Phase 2**
Date: 2026-09-23
Inputs: `docs/phase-0-feasibility-and-constraints.md` plus the confirmed Phase 0 decisions:

| Decision | Confirmed value |
|---|---|
| `targetSdk` | 36 now; tested migration gate to 37 in Phase 10 |
| APK installation | **Excluded** from the product. No `REQUEST_INSTALL_PACKAGES`, no `PackageInstaller` code, no UI affordance. Recorded as ADR-0007 so it is not re-introduced without a security/policy review. |
| Distribution | Google Play — all Play policy constraints apply |
| `RECEIVE_BOOT_COMPLETED` | Kept, **only** for post-reboot reconciliation of schedules and execution records; never triggers macro execution |
| Sensitive macro text | Android Keystore–backed AES-256-GCM for values flagged `sensitive`; all other macro data plaintext in Room |

## Documents in this phase

| # | File | Content |
|---|---|---|
| 1 | [01-functional-requirements.md](01-functional-requirements.md) | Numbered functional requirements with acceptance criteria (Given/When/Then), MVP vs post-MVP tags |
| 2 | [02-non-functional-requirements.md](02-non-functional-requirements.md) | Performance, reliability, security, accessibility, maintainability, testability targets and how each is measured |
| 3 | [03-user-journeys.md](03-user-journeys.md) | End-to-end journeys including failure and recovery paths |
| 4 | [04-threat-model-and-data-classification.md](04-threat-model-and-data-classification.md) | Assets, trust boundaries, STRIDE analysis, data classification, encryption design, retention |
| 5 | [05-architecture.md](05-architecture.md) | Module boundaries, dependency graph, layer rules, DI, navigation, concurrency model, **pinned dependency list with justification** |
| 6 | [06-permission-matrix.md](06-permission-matrix.md) | Every permission/special access × Android version × feature × fallback |
| 7 | [07-execution-state-machine.md](07-execution-state-machine.md) | Execution and step state machines, transitions, persistence points, process-death recovery |
| 8 | [08-macro-schema.md](08-macro-schema.md) | Versioned macro schema v1: Kotlin model, JSON wire format, limits, validation rules, migration contract |
| 9 | [09-error-taxonomy.md](09-error-taxonomy.md) | Error categories, codes, retryability, user-facing message policy |
| 10 | [adr/](adr/) | Architecture Decision Records ADR-0001 … ADR-0010 |
| 11 | [../schema/macro-v1.schema.json](../schema/macro-v1.schema.json) | Machine-readable JSON Schema (draft 2020-12) for the macro export format; used by import validation tests |
| 12 | [../schema/examples/](../schema/examples/) + [../schema/validate.py](../schema/validate.py) | Reference export document and a validator script (`python3 docs/schema/validate.py`) that checks the examples and 17 must-reject cases against the schema |

## Verification statement

No application code exists yet, so nothing in this phase is "compiled" or "tested". Dependency versions in `05-architecture.md` were read from the live Maven metadata (`dl.google.com/android/maven2`, `repo1.maven.org`, `plugins.gradle.org`) and GitHub release pages on 2026-09-23; each is annotated with its source. Version *compatibility* between them (notably AGP 9.4 × KGP 2.3.21 × KSP 2.3.12 × Hilt 2.60.1) is a documented expectation that Phase 2 will verify with a GitHub Actions run before any further code is written.

What *was* verified in this phase: `docs/schema/macro-v1.schema.json` passes `Draft202012Validator.check_schema`, accepts the reference document `docs/schema/examples/open-wifi-settings.json`, and rejects 17 deliberately invalid variants (unknown keys, reserved actions, limit violations, malformed durations/package names/times, schedule intervals below 15 minutes, etc.) — run locally with `python3 docs/schema/validate.py` (jsonschema 4.26.0).

## Acceptance criteria for Phase 1

- [x] Every MVP feature from Phase 0 has at least one numbered FR with testable acceptance criteria.
- [x] Every NFR states a measurable target and the phase in which it is measured.
- [x] Threat model covers all Phase 0 risks R1–R12 with a mitigation mapped to a component.
- [x] Every persisted field has a data classification and a retention rule.
- [x] Module graph is acyclic and each module lists its allowed dependencies.
- [x] Every dependency has a version read from a live source and a one-line justification.
- [x] Every permission maps to a feature and has a "denied" behaviour.
- [x] Execution state machine enumerates all transitions and the persistence point of each.
- [x] Macro schema is complete enough to write the serializer, validator, and migration tests from it without further design.
- [x] Error taxonomy defines the code space the engine, repositories, and UI share.

## Open items carried into Phase 2

- Confirm AGP 9.4.1 + `android.builtInKotlin=false` + KGP 2.3.21 + KSP 2.3.12 + Hilt Gradle Plugin 2.60.1 + `android.newDsl=true` resolve and compile together on CI (ADR-0002 lists the fallback order).
- Confirm detekt 1.23.8 runs in plain (non-type-resolution) mode on AGP 9 new DSL projects (ADR-0002).
- Confirm `androidx.core:core-splashscreen:1.2.0` theme parent chain works without AppCompat (Phase 2 lint).
