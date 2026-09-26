# ADR-0002 — Toolchain: AGP 9.4.1 with built-in Kotlin, KGP 2.3.21 pinned, KSP 2.3.12, Hilt 2.60.1

- Status: **Accepted — verified in CI** (configure green on run 35844549829; compile green through `feature:apps` on
  36240066704; later runs exercise every module). Superseded the "built-in Kotlin disabled" plan below on 2026-09-24.

## Context (verified 2026-09-23/24)
- AGP 9.x enables built-in Kotlin by default and rejects `org.jetbrains.kotlin.android` unless `android.builtInKotlin=false`.
  The opt-out is removed in AGP 10.
- AGP 9.4 is required for `compileSdk 37` (AGP 9.0 max was 36.1).
- KSP 2.3.12 is built with `kotlinBaseVersion=2.3.20` and supports AGP 9 built-in Kotlin (R-class fix in 2.3.10).
- Hilt 2.59+ supports AGP 9 in its Gradle plugin; Hilt 2.60 targets Kotlin 2.3.21.
- **CI result 35843924049:** applying `org.jetbrains.kotlin.android` explicitly on AGP 9.4.1 fails with a
  `ClassCastException` inside AGP's variant API even with `android.builtInKotlin=false`. Fallback #2 of the original
  plan (built-in Kotlin) configured and compiled on the next run and has been the configuration ever since.

## Decision (as built)

```
AGP 9.4.1 · Gradle 9.7.1 · JDK 17 · android.newDsl (default) · built-in Kotlin (default)
KGP 2.3.21 pinned via root `org.jetbrains.kotlin.jvm apply false` (AGP would otherwise resolve its own runtime KGP)
kotlin.plugin.compose + kotlin.plugin.serialization applied per module (KGP plugins, compatible with built-in Kotlin)
KSP 2.3.12 · Hilt 2.60.1 · Room 2.8.5 (Room Gradle plugin, `room.generateKotlin=true`)
```

Convention plugins (`build-logic/convention`) apply only AGP plugins plus KSP/Hilt/Compose/Serialization; Kotlin
options are set through `configure<KotlinAndroidProjectExtension> { compilerOptions { … } }` because the `kotlin {}`
block of the old KGP-Android DSL does not exist under built-in Kotlin.

Compiler flags that turned out to be necessary for `-Werror` on Kotlin 2.3 / Compose M3 1.5:
- `-Xannotation-default-target=param-property` — silences the Kotlin 2.3 "annotation applied to value parameter only"
  warning for Hilt qualifiers on constructor `val`s (the new default behaviour is what Dagger expects anyway).
- `-opt-in=androidx.compose.material3.ExperimentalMaterial3Api` in the Compose convention — M3 top-bar scroll
  behaviour, `ModalBottomSheet`, `ExposedDropdownMenuBox`, date/time pickers are all still experimental in
  BOM 2026.09.00; opting in once at the build level keeps per-file annotations out of feature code.
- `allWarningsAsErrors = true` unless `-PwarningsAsErrors=false`; deprecations are therefore fixed, not tolerated
  (`hiltViewModel` moved to `androidx.hilt.lifecycle.viewmodel.compose`, AutoMirrored icons, `PrimaryTabRow`).

Static analysis: detekt 1.23.8 applied at the root in plain (non-type-resolution) mode over all `src/**/kotlin`
sources; detekt's embedded Kotlin is not on the compile classpath. `maxIssues = 0`, 140-column limit, `Composable`
functions exempt from the cyclomatic-complexity rule. ktlint is not applied separately; detekt's formatting rule set
covers import ordering (`tools/sort_imports.py` reproduces its ordering offline).

## Consequences
- No AGP 10 migration debt from the built-in Kotlin opt-out.
- Kotlin 2.4 language features wait for a KSP release built against 2.4 (Phase 10 item).
- Every new compiler warning is a build break; that is intentional for a security-sensitive app.
