# 05 — Architecture

## 1. Style

Single-activity Jetpack Compose app, **MVVM with a thin domain layer** (use cases only where logic is shared between two or more ViewModels or is complex enough to unit-test in isolation). Unidirectional data flow: `ViewModel` exposes one `StateFlow<UiState>` and receives `UiEvent`s; one-shot effects via a `Channel`. Repositories are the single source of truth and expose `Flow`s backed by Room/DataStore. The automation engine is a pure-Kotlin library (`automation:engine`) with Android bindings injected.

## 2. Modules

```
app
├── feature:apps          (installed apps UI + repository)
├── feature:apkimport     (SAF import, analysis, APK list UI)
├── feature:macros        (macro list, editor, import/export UI)
├── feature:execution     (monitor, history, FGS, notifications, run triggers)
├── feature:scheduling    (schedules UI, workers, boot reconcile)
├── feature:settings      (settings, permission center, help, onboarding, consent)
├── automation:engine     (pure Kotlin: model, validator, executor, actions API)
├── automation:android    (Android action implementations + a11y service + gateway)   ← added (see §2.1)
├── core:common           (Result/Either, dispatchers, clock, error codes, redaction, logging API)
├── core:database         (Room: entities, DAOs, migrations, converters, secure value store)
├── core:datastore        (Preferences DataStore + typed accessors)
├── core:security         (Keystore cipher, hashing)                                    ← added
├── core:ui               (theme, design components, previews, navigation types, window-size helpers)
└── core:testing          (test fakes, rules, fixtures — test-only consumers)             ← added
```

### 2.1 Deviations from the requested module list (and why)
- **`automation:android` added.** The prompt asks `automation:engine` to hold the execution engine. Keeping the engine free of the Android SDK makes concurrency/cancellation/timeouts fully testable on the JVM with `runTest` virtual time — the most valuable tests in the project. Everything that needs `Context`, `AccessibilityService`, `PackageManager`, or `NotificationManager` lives in `automation:android`, implementing engine interfaces.
- **`core:security` added** so that Keystore code (with its instrumented tests) does not force `core:database` into needing an emulator for its unit tests.
- **`core:testing` added** for shared fakes (`FakeClock`, `FakeAccessibilityGateway`, `TestDispatchers`, `InMemoryRepositories`). Production code never depends on it (Gradle configuration `testImplementation`/`androidTestImplementation` only).

### 2.2 Module responsibilities and allowed dependencies

Enforced by `checkModuleBoundaries` (`build-logic/convention/src/main/kotlin/com/macroandroid/buildlogic/ModuleBoundaries.kt`).
The table is the rule set as implemented.

| Module | May depend on (project modules) | Public surface |
|---|---|---|
| `app` | anything except `core:testing` in `main` | `MacroApplication` (Hilt root, WorkManager `Configuration.Provider`), `MainActivity`, `MacroApp` nav shell, `AppShortcuts` (binds `ShortcutsContract`), `IntentRoutes` |
| `feature:execution` | `core:*`, `automation:engine`, `automation:android` (pause/resume/cancel need the runner directly) | `executionGraph`, `ExecutionsDestination`, `ExecutionDetailDestination`, `EXECUTION_DEEP_LINK` |
| other `feature:*` | `core:*`, `automation:engine` — never another feature | `xxxGraph(...)` + `@Serializable` route objects; cross-feature actions go through `core:common` contracts |
| `automation:android` | `automation:engine`, `core:common`, `core:database`, `core:security`, `core:datastore` | `MacroRunner`, `MacroAccessibilityService`, `AccessibilityConsent`, `WorkManagerScheduler`, `ScheduleWorker`, `BootCompletedReceiver`; binds `MacroRunnerContract`, `SchedulerContract`, `ConsentContract`, `AuditContract` |
| `automation:engine` | `core:common` only — pure Kotlin/JVM, no `android.*`/`androidx.*` | model, `MacroValidator`, `MacroExecutor`, `NextRunCalculator`, serialization/migrations |
| `core:database` | `core:common`, `core:security`, `automation:engine` (entities map engine models) | repositories (`MacroRepository`, `ExecutionRepository`, `ScheduleRepository`), `SecureValueStore` |
| `core:testing` | `core:*`, `automation:engine` (+ its test fixtures) | test doubles, rules |
| `core:security`, `core:datastore`, `core:ui`, `core:platform` | `core:common` | cipher/hash; `UserPreferencesRepository`; theme + components; `AndroidLogger` |
| `core:common` | nothing | `AppResult`, `ErrorCode`, `AppDispatchers`, `Logger`, contracts |

Contracts in `core/common/contract/Contracts.kt` and where they are bound:

| Contract | Bound in | Used by |
|---|---|---|
| `MacroRunnerContract` | `automation:android` | `feature:macros`, `feature:apps` |
| `SchedulerContract` | `automation:android` | `feature:scheduling`, `app` (startup reconcile) |
| `ConsentContract`, `AuditContract` | `automation:android` | `feature:settings`, `feature:macros`, `feature:apkimport`, `app` |
| `MacroUsageContract` | `feature:macros` | `feature:apps` |
| `ShortcutsContract` | `app` | `feature:apps`, `feature:macros` |

## 3. Layering inside a feature

```
feature:x
 ├── ui/          Composables + previews; no Android framework calls beyond Compose
 ├── presentation/ ViewModels (@HiltViewModel), UiState, UiEvent, mappers to UI models
 ├── domain/      use cases (optional), feature-local models
 └── data/        repository implementation, Android data sources (PackageManager, SAF), mappers to/from Room entities
```

Rules: `ui` → `presentation` → `domain` → `data`. `data` implements interfaces declared in `domain` (or `core:common` when shared). ViewModels get `AppDispatchers`, never hardcode `Dispatchers.*`. All Room access on `Dispatchers.IO` via DAO `suspend`/`Flow`.

## 4. Dependency injection

Hilt. Components: `SingletonComponent` (repos, engine, DB, DataStore, dispatchers, clock), `ViewModelComponent`, `ServiceComponent` (FGS), and `@AndroidEntryPoint` on `MainActivity`, `ExecutionForegroundService`, `BootReconcileReceiver`, `ShortcutTrampolineActivity`. The a11y service cannot be a Hilt entry point in the usual way if the system instantiates it before `Application`? — it is instantiated by the system after `Application.onCreate`, so `@AndroidEntryPoint` on an `AccessibilityService` works; nevertheless we keep the service thin: it registers itself with a singleton `AccessibilityServiceRegistry` obtained via `EntryPointAccessors`, so the service class has no constructor injection requirements. Workers use `@HiltWorker` + `HiltWorkerFactory` with `WorkManager` on-demand initialization (`Configuration.Provider`, default initializer removed in manifest).

## 5. Navigation

**Navigation 3** (`androidx.navigation3`, 1.2.0-rc01 today; 1.1.x stable) is not adopted for MVP: it reached 1.0 in 2025 but its adaptive/list-detail integration (`material3-adaptive-navigation3`) and Hilt ViewModel scoping story are still moving. We use **Navigation-Compose 2.10.1** with type-safe routes (`@Serializable` route objects) and `hiltViewModel()` scoping per destination; `material3-adaptive-navigation-suite` provides the bottom bar / rail / drawer switch by window size class, and `material3-adaptive-layout` gives `ListDetailPaneScaffold`. ADR-0004 records this and the migration trigger.

Top-level destinations: Dashboard, Apps, APKs, Macros, Schedules, Monitor (badge with active count). Secondary: App detail, APK detail, Macro editor, Step editor (as a dialog/pane), Macro preview, Execution detail, History, Permission center, Settings, Help, Onboarding, Consent.

Deep links (internal only, validated): `macroandroid://execution/{id}` (from notifications), `macroandroid://consent`, `macroandroid://run/{macroId}?req={uuid}` (from dynamic shortcuts; the id is looked up, never trusted for anything but a DB lookup).

## 6. Concurrency model

- `AppDispatchers(io, default, main, mainImmediate)` injected; tests supply `StandardTestDispatcher`.
- Application-scoped `CoroutineScope(SupervisorJob() + default + CoroutineExceptionHandler(logging))` for fire-and-forget work that must outlive a ViewModel (e.g., APK analysis after the screen is closed) — bounded; long work goes to WorkManager.
- Engine: `MacroExecutor.run(request): ExecutionHandle` creates `CoroutineScope(SupervisorJob(parentJob) + default + CoroutineName("exec-$id"))`; each step `withTimeout` in a child `launch`/`async`; `Parallel` uses `coroutineScope { children.map { async {...} }.awaitAll() }` so a failure cancels siblings per the block's policy; global limits via a `Semaphore(maxConcurrent)` and `Mutex` for UI; a `Channel<RunRequest>(capacity = queueDepth)` gives backpressure — `trySend` failure → `QUEUE_FULL`.
- Lifecycle-aware collection in UI: `collectAsStateWithLifecycle()` only.
- Cancellation: every loop in actions checks `ensureActive()`/`yield()`; blocking a11y calls are wrapped in `runInterruptible(Dispatchers.IO)` where they can block; `NonCancellable` only around the final state write.

## 7. Persistence overview

Room database `macro.db`, version 1 at Phase 3 (each later phase that changes schema adds a migration + test). Tables: `profiles`, `macros`, `macro_steps` (step JSON + ordinal + parent + label), `secure_values`, `macro_drafts`, `tags`, `macro_tags`, `app_favorites`, `imported_apks`, `schedules`, `executions`, `execution_steps`, `log_entries`, `audit_entries`. DataStore `user_prefs` for settings/consent. Files: `cacheDir/apk-import/` (transient), `filesDir/apk-copies/` (opt-in).

Backup: `android:allowBackup="false"` (ADR-0009): Keystore keys do not transfer, so restoring the DB would yield undecryptable sensitive values and stale URI grants; explicit export/import is the migration path.

## 8. Pinned dependency list

Versions read on **2026-09-23** from the sources given. "Latest stable" = highest version without alpha/beta/rc/RC qualifier in the metadata. Kotlin/AGP/KSP/Hilt interplay is governed by ADR-0002.

### 8.1 Build plugins

| Plugin | Version | Source | Why |
|---|---|---|---|
| `com.android.application` / `com.android.library` (AGP) | **9.4.1** | dl.google.com maven-metadata (latest stable: 9.4.1) | Required for compileSdk 37 (max API 37 per AGP 9.4 notes); Studio Quail 4 compatible |
| Gradle wrapper | **9.7.1** | github.com/gradle/gradle releases | Latest stable; AGP 9.4 requires ≥ 9.6.0 |
| `org.jetbrains.kotlin.android` / `org.jetbrains.kotlin.plugin.compose` / `org.jetbrains.kotlin.plugin.serialization` (KGP) | **2.3.21** | repo1.maven.org metadata (2.4.20 is latest; 2.3.21 chosen) | KSP 2.3.12 is built against Kotlin 2.3.20 (`kotlinBaseVersion` in the KSP repo at tag 2.3.12) and Hilt 2.60 updated to Kotlin 2.3.21; staying on the 2.3.x line is the verified-compatible choice. 2.4.x is noted as a Phase-10 upgrade candidate once KSP publishes a 2.4-based release. |
| `com.google.devtools.ksp` | **2.3.12** | repo1.maven.org metadata + GitHub release 2026-09-09 | Annotation processing for Room and Hilt; min AGP 8.12 per release notes; has AGP-9 built-in-Kotlin support since 2.3.1 with R-class fix in 2.3.10 |
| `com.google.dagger.hilt.android` | **2.60.1** | repo1.maven.org metadata | Hilt Gradle plugin; AGP 9 support since 2.59 |
| `io.gitlab.arturbosch.detekt` | **1.23.8** | repo1.maven.org metadata (2.0.0-alpha.6 is prerelease) | Static analysis; used **without** type resolution to avoid the Kotlin 2.0.21 compile-classpath mismatch; if it fails to apply against AGP 9 new DSL the fallback is to run detekt via its CLI jar in CI (ADR-0002) |
| `org.jlleitschuh.gradle.ktlint` | **14.2.0** | plugins.gradle.org metadata | Formatting; 14.1.0+ supports AGP 9 new DSL/built-in Kotlin |
| `com.pinterest.ktlint:ktlint-cli` (engine used by the plugin) | **1.8.0** | repo1.maven.org metadata | Pinned explicitly for reproducibility |
| `org.jetbrains.kotlinx.kover` | latest stable resolved in Phase 2 (not fetched today) | — | Coverage report; non-blocking initially |
| `androidx.baselineprofile` | **1.5.0** (same as benchmark) | dl.google.com (benchmark-macro-junit4 1.5.0) | Baseline profile generation, Phase 10 |
| `androidx.room` (Gradle plugin) | **2.8.5** | dl.google.com | Sets `room.schemaLocation` properly for KSP and config cache |

### 8.2 Libraries

| Library | Version | Source | Used by | Why |
|---|---|---|---|---|
| `androidx.compose:compose-bom` | **2026.09.00** | dl.google.com | all UI | Pins compose-ui/foundation/runtime 1.12.1, material3 1.4.0, material3-adaptive 1.3.0, window-size-class 1.4.0, adaptive-navigation-suite 1.4.0 |
| `androidx.compose.material3:material3`, `material3-window-size-class`, `material3-adaptive-navigation-suite` | via BOM | `core:ui`, `app` | Material 3, responsive layout |
| `androidx.compose.material3.adaptive:adaptive`, `adaptive-layout`, `adaptive-navigation` | via BOM (1.3.0) | `core:ui`, features | List-detail scaffold |
| `androidx.compose.material:material-icons-extended` | via BOM (1.7.8) | `core:ui` | Icons; R8 strips unused |
| `androidx.compose.ui:ui-tooling(-preview)`, `ui-test-junit4`, `ui-test-manifest` | via BOM | debug/test | Previews, Compose tests |
| `androidx.activity:activity-compose` | **1.13.0** | dl.google.com | `app` | `setContent`, back handling, permission launchers |
| `androidx.core:core-ktx` | **1.19.0** | dl.google.com | many | `ShortcutManagerCompat`, `NotificationCompat`, `ContextCompat` |
| `androidx.core:core-splashscreen` | **1.2.0** | dl.google.com | `app` | Splash on 26+ |
| `androidx.appcompat:appcompat` | **1.8.0** | dl.google.com | `app` | Only for `AppCompatDelegate.setApplicationLocales`/night mode compat on < 33 and `ShortcutTrampolineActivity` theme; may be dropped in Phase 9 if unused |
| `androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-service`, `lifecycle-process` | **2.11.0** | dl.google.com (2.12.0-alpha03 is prerelease) | features, FGS | `collectAsStateWithLifecycle`, `LifecycleService`, process lifecycle for "app visible" gate |
| `androidx.navigation:navigation-compose` | **2.10.1** | dl.google.com | `app`, features | Type-safe navigation (ADR-0004) |
| `androidx.hilt:hilt-navigation-compose` | **1.4.0** | dl.google.com | features | `hiltViewModel()` |
| `androidx.hilt:hilt-work`, `hilt-compiler` | **1.4.0** | dl.google.com | `feature:scheduling` | `@HiltWorker` |
| `com.google.dagger:hilt-android`, `hilt-android-compiler` (ksp), `hilt-android-testing` | **2.60.1** | repo1.maven.org | all | DI |
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler` (ksp), `room-testing`, `room-paging` | **2.8.5** | dl.google.com | `core:database` | Persistence, migrations tests, paging source |
| `androidx.paging:paging-runtime`, `paging-compose` | latest stable resolved in Phase 2 (not fetched today) | — | history | Paginated history |
| `androidx.datastore:datastore-preferences` | **1.2.1** | dl.google.com (1.3.0-alpha11 prerelease) | `core:datastore` | Preferences |
| `androidx.work:work-runtime-ktx`, `work-testing` | **2.11.2** | dl.google.com (2.12.0-rc01 prerelease) | `feature:scheduling` | Scheduling, `TestDriver` |
| `androidx.window:window` | **1.5.1** | dl.google.com | `core:ui` | Window metrics fallback |
| `androidx.documentfile:documentfile` | **1.1.0** | dl.google.com | `feature:apkimport` | SAF helpers |
| `androidx.profileinstaller:profileinstaller` | **1.4.1** | dl.google.com | `app` | Baseline profile install |
| `androidx.benchmark:benchmark-macro-junit4` | **1.5.0** | dl.google.com | `:benchmark` module (Phase 10) | Startup/frame metrics |
| `androidx.test.uiautomator:uiautomator` | **2.4.0** | dl.google.com | benchmark, E2E | Driving the emulator |
| `androidx.test:core`, `runner`, `rules` | **1.7.0** | dl.google.com | androidTest | Instrumentation |
| `androidx.test.ext:junit` | **1.3.0** | dl.google.com | androidTest | `AndroidJUnit4` |
| `androidx.test.espresso:espresso-core`, `espresso-accessibility` | **3.7.0** | dl.google.com | androidTest | Accessibility checks |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `-android`, `-test` | **1.11.0** | repo1.maven.org | all | Coroutines |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.11.0** | repo1.maven.org (1.12.0-RC prerelease) | engine, features | Macro JSON |
| `org.jetbrains.kotlinx:kotlinx-datetime` | **0.8.0** | GitHub release | engine, scheduling | `LocalTime`/`DayOfWeek` in the pure-Kotlin schema; conversions to `java.time` at the Android edge |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable` | **0.5.2** | GitHub release | UI state | Stable collections for Compose |
| `junit:junit` | **4.13.2** | GitHub | tests | JUnit 4 (Compose/AndroidX test rules are JUnit 4) |
| `io.mockk:mockk`, `mockk-android` | **1.14.11** | GitHub release | tests | Mocking Android framework objects where fakes are impractical (`PackageManager`) |
| `com.google.truth:truth` | **1.4.5** | GitHub release | tests | Assertions |
| `app.cash.turbine:turbine` | **1.2.1** | GitHub release | tests | Flow testing |
| `org.robolectric:robolectric` | **4.17** | GitHub release | selected unit tests | Fast tests of `PackageManager`-based discovery with shadow packages |
| `com.squareup.leakcanary:leakcanary-android` | **2.14** (3.0 is alpha) | repo1.maven.org | debug only | Leak detection |

Not added (and why): Timber (last release 2021; `core:common` ships a 40-line `Logger` with release redaction instead); Coil (icons come from `PackageManager` as `Drawable`s — a small LRU cache in `core:ui` suffices; no network images); Jetpack Security (deprecated); any analytics/crash SDK (no network, no collection).

### 8.3 Version-compatibility expectations to be verified in Phase 2 (first CI run)
1. AGP 9.4.1 + `android.builtInKotlin=false` + explicit KGP 2.3.21 + KSP 2.3.12 + Hilt plugin 2.60.1 + `android.newDsl=true`.
2. Compose compiler plugin 2.3.21 with Compose BOM 2026.09.00 (runtime 1.12.1).
3. Room 2.8.5 KSP processor with KSP 2.3.12.
4. detekt 1.23.8 Gradle plugin applying on Gradle 9.7.1 (plugin built against Gradle 8.12; if incompatible → CLI fallback).
5. ktlint-gradle 14.2.0 on AGP 9.4 with built-in Kotlin disabled.

If (1) fails, ADR-0002 fallback order: (a) KGP 2.3.21 → 2.3.20; (b) enable built-in Kotlin (`android.builtInKotlin=true`, drop KGP plugin application, keep KSP 2.3.12 which supports it since 2.3.1) and verify Hilt; (c) AGP 8.13.2 with KGP 2.3.21 (last 8.x line, compileSdk 37 not supported there → compileSdk 36 temporarily, documented).

## 9. Build configuration decisions (implemented in Phase 2)

- `compileSdk 37`, `targetSdk 36`, `minSdk 26`; JDK 17 toolchain; Kotlin `jvmTarget 17`; `allWarningsAsErrors = true`; explicit API mode off (app, not a library).
- Build types: `debug` (StrictMode, LeakCanary, `applicationIdSuffix ".debug"`, verbose logger), `release` (R8 full mode, resource shrinking, `proguard-android-optimize.txt` + module `consumer-rules.pro`), `benchmark` (release-like, signed with debug key, `profileable`).
- Signing: `release` reads from `keystore.properties` (git-ignored) or CI secrets; a template is committed; without it release builds are unsigned (they still compile on CI).
- `gradle.properties`: config cache, build cache, parallel, `android.builtInKotlin=false` (with comment and AGP-10 deadline), `android.newDsl=true`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `kotlin.code.style=official`.
- Dependency verification metadata committed; Dependabot for Gradle + Actions.
- CI (`.github/workflows/ci.yml`): `build` job (assembleDebug, lint, detekt, ktlintCheck, testDebugUnitTest, kover), `instrumented` job on PR/manual with emulator API 34 (later matrix), artefacts: APK, lint HTML, test reports, coverage.
