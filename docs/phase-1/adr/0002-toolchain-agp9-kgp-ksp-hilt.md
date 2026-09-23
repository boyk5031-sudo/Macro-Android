# ADR-0002 — Toolchain: AGP 9.4.1 with built-in Kotlin disabled, KGP 2.3.21, KSP 2.3.12, Hilt 2.60.1

- Status: Accepted, **pending CI verification in Phase 2**

## Context (verified 2026-09-23)
- AGP 9.x enables built-in Kotlin by default and rejects `org.jetbrains.kotlin.android` unless `android.builtInKotlin=false` (AGP 9.0 release notes). The opt-out is removed in AGP 10.
- AGP 9.4 is required for `compileSdk 37` (AGP 9.0 max was 36.1).
- KSP 2.3.x has supported AGP 9 built-in Kotlin since 2.3.1 (R-class fix in 2.3.10) per its release notes; its integration tests target KGP 2.2.10/2.3.0 with AGP 9.0 betas. KSP 2.3.12 is built with `kotlinBaseVersion=2.3.20`.
- Hilt 2.59+ supports AGP 9 in its Gradle plugin; Hilt 2.60 updated to Kotlin 2.3.21.
- Community reports (Sept 2026) show KSP refusing to run under built-in Kotlin in some setups with the message "KSP is not compatible with Android Gradle Plugin's built-in Kotlin. Please disable by adding android.builtInKotlin=false and apply kotlin(\"android\")". The exact trigger is not documented; the KSP source contains an `isAgpBuiltInKotlinUsed()` code path, so both modes exist.
- Kotlin 2.4.20 is the latest KGP but no KSP release built against 2.4.x exists yet.

## Decision
Start with the configuration most likely to work and which matches what Hilt/KSP themselves test:

```
AGP 9.4.1 · Gradle 9.7.1 · JDK 17 · android.newDsl=true · android.builtInKotlin=false
KGP 2.3.21 (org.jetbrains.kotlin.android + plugin.compose + plugin.serialization applied explicitly)
KSP 2.3.12 · Hilt 2.60.1 · Room 2.8.5 (Room Gradle plugin)
```
`gradle.properties` will carry the comment: `# built-in Kotlin disabled because KSP+Hilt are verified against explicit KGP; must be resolved before AGP 10 (removal of the opt-out).`

Fallback order if the first CI run fails to configure/compile (each attempted in its own commit with the failure pasted into this ADR):
1. KGP 2.3.21 → 2.3.20 (exact KSP base version).
2. Enable built-in Kotlin: remove `android.builtInKotlin=false` and the `kotlin.android` plugin applications; keep `plugin.compose`/`plugin.serialization` (still KGP plugins — compatible with built-in Kotlin); keep KSP 2.3.12.
3. AGP 8.13.2 + KGP 2.3.21 + KSP 2.3.12, `compileSdk 36` temporarily; document the compileSdk 37 gap and revisit in Phase 10.

Static analysis: detekt 1.23.8 Gradle plugin **without type resolution** (its embedded Kotlin 2.0.21 must not be on the compile classpath). If applying the plugin fails on Gradle 9.7/AGP 9.4, run `detekt-cli` 1.23.8 directly from a Gradle `Exec` task/CI step with the same config. detekt 2.0 remains alpha and is not adopted.

## Consequences
- Predictable, documented upgrade path; the build files state why each flag exists.
- Kotlin 2.4 language features are not available until KSP catches up (tracked for Phase 10).
