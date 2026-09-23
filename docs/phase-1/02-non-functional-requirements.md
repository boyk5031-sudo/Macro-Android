# 02 — Non-Functional Requirements

Each NFR names a measurable target, the measurement method, and the phase in which the measurement is first taken on CI. Targets that require a physical device are marked; they will be measured on the emulator classes CI can run (API 30/34/36/37, x86_64) and noted as such.

## Performance

| ID | Requirement | Target | Method | Phase |
|---|---|---|---|---|
| NFR-PERF-1 | Cold start to first frame of Dashboard | ≤ 800 ms on the API 34 emulator (release, R8, baseline profile) | Macrobenchmark `StartupTimingMetric`, 5 iterations, median | 10 |
| NFR-PERF-2 | No main-thread disk/network | 0 StrictMode `DiskRead/DiskWrite/Network` violations in debug during UI tests | StrictMode `penaltyDeath` for disk & network in debug builds under test; `penaltyLog` otherwise | 2 (config), 9 (enforced in UI tests) |
| NFR-PERF-3 | Installed-apps list of 300 apps | first content ≤ 1 s; icons loaded lazily; scroll jank ≤ 5 % frames > 16 ms | Macrobenchmark `FrameTimingMetric` with 300 fake packages is not possible on the emulator; measured with the real emulator app set and documented | 10 |
| NFR-PERF-4 | APK import of a 200 MiB file | SHA-256 + parse ≤ 6 s on emulator; heap growth ≤ 20 MiB during import (streaming, 64 KiB buffer) | Instrumented test with a generated 200 MiB ZIP | 4 |
| NFR-PERF-5 | Engine overhead | ≤ 2 ms per non-UI step (excluding the step's own work) | JVM unit benchmark (JUnit, 10 000 steps) | 6 |
| NFR-PERF-6 | Memory | Retained heap ≤ 96 MiB after 30 min of monitor screen open with one running macro; no leaks | LeakCanary in debug (fail UI tests on leak); heap dump review | 10 |
| NFR-PERF-7 | APK size | ≤ 8 MiB download size for the universal APK (release) | `bundletool` size report on CI | 11 |

## Reliability

| ID | Requirement | Target | Method | Phase |
|---|---|---|---|---|
| NFR-REL-1 | Crash-free execution records | Every state transition is persisted in a transaction; process kill at any step boundary leaves the DB consistent | Instrumented test kills the process (`Runtime.halt`) mid-execution and verifies reconciliation | 6, 8 |
| NFR-REL-2 | Database migrations | Every schema version has a tested migration; `exportSchema = true`; `MigrationTestHelper` runs 1→N | Room migration tests | 3 (and every later schema change) |
| NFR-REL-3 | Cancellation correctness | Cancelled executions finish within 2 s (bounded by the longest atomic a11y call) and release the UI lock | Engine unit tests with `runTest`; instrumented cancel test | 6, 7 |
| NFR-REL-4 | Timeouts | Timeout error is raised within `timeout + 100 ms` in unit tests | `runTest` virtual time | 6 |
| NFR-REL-5 | Idempotent scheduling | Duplicate WorkManager delivery does not create a second execution | WorkManager `TestDriver` test | 8 |
| NFR-REL-6 | Reboot | `DAILY` schedules have a next-run enqueued after `BOOT_COMPLETED` | Instrumented test broadcasting the boot intent to the receiver under test | 8 |
| NFR-REL-7 | Uncaught exceptions | Engine never crashes the process because of a step failure: every action runs inside `runCatching` mapped to `ExecutionError`; `CancellationException` is rethrown | Unit test injecting a throwing action | 6 |

## Security and privacy

| ID | Requirement | Target | Method | Phase |
|---|---|---|---|---|
| NFR-SEC-1 | No network | The manifest declares no `INTERNET`; no dependency adds it (merged manifest check) | Lint check `PermissionImpliesUnsupportedHardware` is irrelevant; a custom Gradle task asserts `INTERNET` absent from the merged manifest | 2 |
| NFR-SEC-2 | Sensitive values at rest | AES-256-GCM, key in AndroidKeyStore (`setUserAuthenticationRequired(false)`, `setUnlockedDeviceRequired(false)` so scheduled runs work, `StrongBox` if available), 12-byte random IV per value, AAD = macro id + parameter path | Unit test of the cipher wrapper with a fake key provider; instrumented round-trip; static review | 3 |
| NFR-SEC-3 | Input hardening | JSON import: 1 MiB limit, strict mode, depth ≤ 16, closed polymorphism; APK: size cap, magic check, no manual ZIP inflation | Fuzz tests (random/mutated JSON and ZIP corpora, 10 000 cases) | 5, 10 |
| NFR-SEC-4 | No exported components besides launcher activity and the a11y service | Lint `ExportedReceiver/Service/ContentProvider` fatal | 2 |
| NFR-SEC-5 | Redaction | No value flagged `sensitive` and no a11y node text appears in logs or exports (only SHA-256 prefixes of node text) | Unit tests over the redactor; grep-based test on a generated diagnostics file | 6, 9 |
| NFR-SEC-6 | Dependency hygiene | Gradle dependency verification (`verification-metadata.xml`, sha256) enabled; Dependabot config present; build fails on unverified artefacts | CI | 2, 10 |
| NFR-SEC-7 | Backup | `android:allowBackup="true"` with `dataExtractionRules` excluding the Room DB file containing encrypted values? — No: Keystore keys do not transfer between devices, so encrypted values would be unrecoverable. Decision: exclude `macro_secure_values` table's DB? Room is a single file. **Decision:** disable cloud backup entirely (`allowBackup=false`, `fullBackupContent` n/a) and provide explicit export/import as the migration path (ADR-0009). | Manifest review | 2 |

## Accessibility

| ID | Requirement | Target | Method | Phase |
|---|---|---|---|---|
| NFR-A11Y-1 | TalkBack | Every interactive element has a content description or visible label; custom drag reorder has button alternatives | Compose `AccessibilityChecks` (Espresso accessibility validator) enabled in UI tests; manual TalkBack pass | 9 |
| NFR-A11Y-2 | Touch targets | ≥ 48 dp | Accessibility checks | 9 |
| NFR-A11Y-3 | Font scale | Layouts usable at 200 % font scale, no clipped text | UI tests with `fontScale = 2f` screenshot review | 9 |
| NFR-A11Y-4 | Contrast | WCAG AA (4.5:1) in both themes | Accessibility checks + manual | 9 |
| NFR-A11Y-5 | Predictive back | Enabled (`enableOnBackInvokedCallback = true`); unsaved-change dialog intercepts correctly | UI test | 9 |

## Compatibility

| ID | Requirement | Target |
|---|---|---|
| NFR-COMP-1 | Android versions | minSdk 26 … API 37; instrumented CI matrix API 30, 34, 36 (Phase 8+), 37 (Phase 10 gate) |
| NFR-COMP-2 | Form factors | Compact/Medium/Expanded width classes; list-detail on Expanded; no orientation lock |
| NFR-COMP-3 | Locales | English source strings; RTL layout mirrored correctly (pseudo-locale test `ar-XB`) |
| NFR-COMP-4 | Edge-to-edge | Enforced on 35+; insets handled by Scaffold |

## Maintainability and testability

| ID | Requirement | Target |
|---|---|---|
| NFR-MAINT-1 | Static analysis | detekt (with the project config), ktlint, Android Lint with a baseline of zero; warnings-as-errors for Kotlin |
| NFR-MAINT-2 | Coverage | ≥ 80 % line coverage in `automation:engine`, `core:database`, `feature:*/domain`; reported by Kover on CI (informational gate at 70 % to start, raised in Phase 10) |
| NFR-MAINT-3 | Module rules | Enforced dependency directions (`05-architecture.md §3`) via a Gradle check task |
| NFR-MAINT-4 | Docs | Each module has a `README.md` stating its responsibility and public API; ADRs for every reversal |
| NFR-MAINT-5 | Build time | Clean CI build + unit tests ≤ 12 min on the 4-vCPU runner (config cache + build cache enabled) |

## Battery

| ID | Requirement | Target | Method | Phase |
|---|---|---|---|---|
| NFR-BAT-1 | Idle cost | When no macro runs: no wake locks, no alarms, no running services, a11y service's `onAccessibilityEvent` returns in O(1) | `adb shell dumpsys batterystats` review on emulator + code review; unit test that the gateway is detached when idle | 7, 10 |
| NFR-BAT-2 | Node polling | a11y node searches are event-driven (`TYPE_WINDOW_CONTENT_CHANGED`) with a 250 ms minimum re-check interval, never a tight loop | Unit test with fake event source | 7 |
