# Phase 0 — Feasibility and Constraints

Status: **complete, awaiting confirmation before Phase 1**
Date: 2026-09-23
Applies to: `boyk5031-sudo/Macro-Android`, branch `arena/01a0cd30-macro-android`

---

## 0. Objective

Establish, before any application code is written, what this product can legally and reliably do on current Android, what it cannot do, what the compliant alternatives are, which permissions are justified, which Android versions are supported, and how the project will be built and verified. Everything below that is stated as a fact was either verified in this session (marked **[verified]**) or is cited to the official Android/Play documentation (marked with a source). Anything that is a decision is marked **[decision]** and is open to your override.

---

## 1. Verified environment facts (they shape every later phase)

### 1.1 Repository
- Repository is empty apart from `README.md` (a single "Initial commit"). **[verified]**
- Default branch `main`; this session works exclusively on `arena/01a0cd30-macro-android`. Pushing works. **[verified]**

### 1.2 The sandbox this agent runs in **cannot compile Android code**
- No JDK installed; no Android SDK; `apt` is not usable. **[verified]**
- Outbound HTTPS to `maven.google.com`, `repo.maven.apache.org`, `repo1.maven.org`, `services.gradle.org`, `plugins.gradle.org`, `dl.google.com`, `raw.githubusercontent.com` and `objects.githubusercontent.com` is **blocked** (TLS handshake dropped). Only `api.github.com`, `pypi.org` and `registry.npmjs.org` are reachable. **[verified]**
- Consequence: Gradle cannot resolve AGP, Kotlin, AndroidX, or even its own distribution here.

### 1.3 GitHub Actions is available and fully equipped — it is the build/verification environment
A probe workflow (`.github/workflows/toolchain-probe.yml`, committed on this branch, run id 35833642733 and successors) reported on `ubuntu-latest`: **[verified]**

| Fact | Value |
|---|---|
| JDK | Temurin 17.0.20.1 and 21.0.12.1 pre-installed |
| Android platforms pre-installed | android-34, 35, 36, 36.1, **37.0, 37.1, 37.2** (plus ext/beta variants) |
| Build-tools pre-installed | 34.0.0, 35.0.0, 35.0.1, 36.0.0, 36.1.0, 37.0.0 |
| `sdkmanager` | 12.0 |
| `maven.google.com` / `services.gradle.org` | reachable (HTTP 301 / 200) |
| `/dev/kvm` | present → hardware-accelerated emulator for instrumented/E2E tests is possible |
| Runner | 4 vCPU, 15 GB RAM, 87 GB free disk |

**Operating rule for the whole project (Output Rule 10):** "Builds" and "tests pass" will only be claimed when a GitHub Actions run on this branch says so, and the run id will be quoted. Every phase from Phase 2 onward ends with a CI run that is green on: `assemble`, `lint`, `detekt`/`ktlint`, unit tests, and (where the phase adds them) instrumented tests on an emulator. Nothing will be described as compiled or tested on the basis of local execution, because local execution is impossible here.

### 1.4 Current platform and toolchain state (September 2026)
- **Android 17 (API 37)** is the current stable platform, released 16 June 2026. Source: [itechguides](https://www.itechguides.com/android-17-release-schedule-is-officially-set/), [mungomash](https://mungomash.com/software/android/versions/). Confirmed indirectly by the pre-installed `android-37.x` platforms on the runner. **[verified]**
- Google Play: since **31 Aug 2026** new apps and updates must **target API 36** or higher; existing apps must target ≥35 to remain discoverable. Source: [Play target API requirements](https://orangeoma.zendesk.com/hc/en-us/articles/21001579350172-Google-Play-s-Target-API-level-requirements-for-2026).
- **AGP 9.4.0** (Sept 2026) is the current stable Android Gradle Plugin; minimum Gradle 9.6.0, JDK 17, max API 37. Source: [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes). Android Studio Quail 4 (2026.1.4) supports AGP 7.1–9.4. Source: [About AGP](https://developer.android.com/build/releases/about-agp).
- AGP 9 ships **built-in Kotlin** and rejects the `org.jetbrains.kotlin.android` plugin by default; **KSP (required by Hilt and Room) is not compatible with built-in Kotlin** and requires `android.builtInKotlin=false` plus an explicitly applied KGP. Kotlin/KSP pairing has changed to KSP's own versioning scheme. Sources: [issue #51](https://github.com/Moritz-Staat/block-instagram-reels/issues/51), [open_settings_plus #46](https://github.com/yanncabral/open_settings_plus/issues/46). This is a known-friction item that Phase 2 must resolve against live Maven metadata (reachable from CI), not from memory. **Exact versions will be pinned in Phase 2 from the actual `maven-metadata.xml` files, not invented.**

---

## 2. Platform baseline **[decision]**

| Setting | Value | Justification |
|---|---|---|
| `minSdk` | **26** (Android 8.0) | Notification channels, adaptive icons, `ShortcutManager` (25+), `PackageInstaller` sessions, `AccessibilityService.dispatchGesture` (24+), `PersistableBundle` extras, `java.time` without desugaring headaches. Covers the overwhelming majority of active devices; going lower adds compat branches with no product value. |
| `compileSdk` | **37** | Latest stable; lets us compile against `ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` (SDK 36), `Service.onTimeout` (35) etc. Pre-installed on CI. |
| `targetSdk` | **36** for MVP, **37** gate in Phase 10 | 36 satisfies the Play requirement in force today. The Android-17-targeted behavior changes (RemoteViews bitmap memory cap, lock-free `MessageQueue`, immutable `static final`, ECH, `ACCESS_LOCAL_NETWORK`) do not touch this app's feature set ([source](https://developer.android.com/about/versions/17/behavior-changes-17)), so raising to 37 is a one-line change — but it will only be flipped after an Android 17 emulator run of the full instrumented suite in Phase 10, because a claim of "works on 17" must be backed by a test run. If you prefer 37 from the start, say so and Phase 2 will use it. |
| Form factors | Phones and tablets (adaptive layouts, `WindowSizeClass`) | Android 16+/17 ignore orientation/resizability restrictions on `sw≥600dp` ([source](https://developer.android.com/about/versions/16/behavior-changes-16)); we design responsive from the start rather than opt out. |
| Excluded | Wear OS, TV, Automotive, XR | Different input models; UI automation semantics do not transfer. |

Behavior changes reviewed and accounted for in the design:
- **Android 14**: FGS types mandatory; `POST_NOTIFICATIONS` runtime permission (13+); `SCHEDULE_EXACT_ALARM` denied by default; `PendingIntent` sender opt-in for background activity launch (BAL).
- **Android 15**: `dataSync`/`mediaProcessing` FGS 6 h/24 h cap and `Service.onTimeout`; `BOOT_COMPLETED` receivers cannot start `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` FGS; the `SYSTEM_ALERT_WINDOW` FGS-from-background exemption now requires a *visible* overlay; `PendingIntent` creator opt-in for BAL; edge-to-edge default. Source: [behavior-changes-15](https://developer.android.com/about/versions/15/behavior-changes-15).
- **Android 16**: job/WorkManager runtime quota now enforced for jobs started in top state or alongside an FGS; `JobInfo.setImportantWhileForeground` no-op; opt-in Safer Intents (`intentMatchingFlags`); edge-to-edge opt-out removed; predictive back required; MediaStore version per-app. Source: [behavior-changes-16](https://developer.android.com/about/versions/16/behavior-changes-16), [behavior-changes-all (16)](https://developer.android.com/about/versions/16/behavior-changes-all).
- **Android 17**: per-app memory limits (all apps) — relevant for icon caching and log retention; `StrictMode.detectImplicitUriPermissionGrant()` added ahead of Android 18 removing implicit URI grants on `ACTION_SEND` — we will always set `FLAG_GRANT_READ_URI_PERMISSION` explicitly when exporting diagnostics/macros; `usesCleartextTraffic` deprecation plan (irrelevant: no network). Source: [behavior-changes-all (17)](https://developer.android.com/about/versions/17/behavior-changes-all).

---

## 3. Feature classification

Legend — **Feasible**: implementable with public APIs and no special Play review. **Restricted**: implementable, but gated by a runtime opt-in, a Play declaration/review, or a hard runtime condition that must be surfaced to the user. **Unsupported**: cannot be done in a policy-compliant, reliable way; a compliant alternative is given.

### 3.1 APK and application management

| Requirement | Class | Notes / alternative |
|---|---|---|
| SAF APK selection (`ACTION_OPEN_DOCUMENT`, MIME `application/vnd.android.package-archive` + `application/octet-stream` fallback) | Feasible | No storage permission needed. |
| Persistent URI permission (`takePersistableUriPermission`) | Feasible | Grants can be revoked by the provider/user at any time; every read re-validates and downgrades the record to `UNAVAILABLE` instead of crashing. Per-app persisted-grant count is limited by the system (historically 128/512 depending on version) — we cap imports and release grants on delete. |
| APK metadata extraction | Feasible | `PackageManager.getPackageArchiveInfo(path, flags)` needs a **file path**; SAF gives a `content://` URI. The APK is streamed to app-private cache with a size cap, parsed, then the copy is deleted (or retained only if the user opts to keep a local copy). Split APKs / `.apks` / `.xapk` bundles: metadata only for the base APK; documented. |
| Validation, SHA-256, duplicate detection | Feasible | Streaming digest; duplicate = same SHA-256 (exact) or same `packageName+versionCode+signing cert digest` (semantic), both surfaced. |
| Installed-app discovery | **Restricted by package visibility** | Without `QUERY_ALL_PACKAGES` (Play-restricted to launchers, AV, file managers, device search…), the app sees only packages matched by `<queries>`. We declare an intent query for `ACTION_MAIN`/`CATEGORY_LAUNCHER` → all **launchable** apps are visible, which is exactly the "compatible installed applications" set. Non-launchable packages (services, providers) will not be listed; the UI says so. |
| "Is this imported APK's package installed?" | Restricted | Resolvable only when that package is visible via the same `<queries>`. For non-launchable packages the state is shown as "unknown", not "not installed". |
| Launch app | Feasible | `getLaunchIntentForPackage` (falls back to `queryIntentActivities` for the main activity); `ActivityNotFoundException`/`SecurityException` mapped to typed errors. |
| Shortcuts | Feasible | `ShortcutManagerCompat.requestPinShortcut` (pinned; launcher may refuse/ignore — we detect `isRequestPinShortcutSupported`). Dynamic shortcuts for the 4 most recently run macros. |
| User-confirmed installation | **Restricted — post-MVP, feature-flagged** | Requires `REQUEST_INSTALL_PACKAGES` (Play: only for apps whose core functionality includes user-initiated app installation — file managers, browsers, enterprise, backup; must be declared; reviewed). Technically: `PackageInstaller` session → `commit()` with a `PendingIntent` → the **system** shows the confirmation dialog; on Android 14+ the status receiver `PendingIntent` needs the BAL opt-ins. Can only proceed if `canRequestPackageInstalls()` is true (user toggles "Install unknown apps" for our app; on Android 16+ this toggle is blocked during phone calls — [source](https://www.inspire2rise.com/android-16-blocks-sensitive-settings-changes-during-calls.html)). **No silent install exists for non-device-owner apps** — not offered. Decision needed from you: include in scope (post-MVP) or exclude. |
| Uninstall | Feasible (post-MVP) | `ACTION_DELETE` / `PackageInstaller.uninstall` with system confirmation only. |

### 3.2 Macro actions

| Action | Class | How, and the limit |
|---|---|---|
| Launch application | Feasible **while our app/its a11y service is in a launch-permitted state** | From the foreground: plain `startActivity`. From a scheduled/background run: **blocked by Background Activity Launch restrictions** (Android 10+) unless an exemption applies ([source](https://developer.android.com/guide/components/activities/background-starts)). Compliant path: the run pauses in `BLOCKED(NEEDS_FOREGROUND)` and posts a notification "Tap to continue *Macro X*"; the tap (a system-sent `PendingIntent`) brings us foreground and the run resumes. Optional post-MVP opt-in: `SYSTEM_ALERT_WINDOW` is a listed BAL exemption; we may offer it with explicit disclosure. |
| Wait | Feasible | Coroutine `delay`, cancellable, capped (default max 10 min/step; total macro budget). |
| Open URL | Feasible | `ACTION_VIEW` with `http/https` only (allow-list of schemes, no `intent:`/`file:`/`content:`); same BAL rule as Launch. |
| Press back / home / recents / notifications | Restricted (a11y) | `AccessibilityService.performGlobalAction`. Requires the user to have enabled our accessibility service. |
| Enter text | Restricted (a11y) | `ACTION_SET_TEXT` on the **currently focused, editable, visible** node only. Refused on password fields (`isPassword`) and on nodes the target app marks `accessibilityDataSensitive` (Android 14+, invisible to non-tool services anyway). Text is stored in the macro; the macro schema marks text parameters as `sensitive=true|false` and sensitive values are excluded from logs/exports. |
| Click visible node | Restricted (a11y) | Locate by `viewIdResourceName`, text, or content-description within the **current active window** (`rootInActiveWindow`), verify `isVisibleToUser && isClickable/isEnabled`, then `ACTION_CLICK`. If the node isn't found within the step timeout → typed failure `NODE_NOT_FOUND`, retry policy applies. Coordinate-based `dispatchGesture` taps are **not** in MVP (brittle, and exactly the kind of "blind" automation that harms users); can be added later, opt-in per step. |
| Scroll visible content | Restricted (a11y) | `ACTION_SCROLL_FORWARD/BACKWARD` on a scrollable visible node. |
| Take screenshot | **Restricted — post-MVP** | Two legal APIs: (a) `AccessibilityService.takeScreenshot` (API 30+, a11y service required, rate-limited by the system); (b) `MediaProjection` (per-session user consent dialog every time on Android 14+, `mediaProjection` FGS type, cannot cache the token). Both capture other apps' content, so both sit behind a separate consent screen and are excluded from MVP. Screenshots of **our own** screens (`PixelCopy`) are trivially feasible but of little value. |
| Send notification | Feasible | `POST_NOTIFICATIONS` runtime permission on 13+; dedicated channel; rate-limited (system throttles anyway). |
| Set variable / conditional / repeat | Feasible | Typed, bounded: variables are `String/Long/Boolean`; conditions are a closed set (`equals`, `contains`, `nodeExists`, `appInstalled`, `varCompare`); repeat max 100 iterations, nesting depth max 4; total step budget per macro (e.g., 200 expanded steps). This is what "typed macro model, not scripting" means in practice. |
| Timeout / retry / stop / log | Feasible | Engine-level. |
| Run arbitrary code, shell, root, downloaded content | **Unsupported by design** | Not implemented; no plugin mechanism. |

### 3.3 Execution, scheduling, monitoring

| Requirement | Class | Notes |
|---|---|---|
| Sequential execution, structured concurrency, cancellation, timeouts, retries | Feasible | Pure Kotlin; fully unit-testable with `kotlinx-coroutines-test`. |
| Parallel execution | Feasible **only for independent, non-UI steps** | Waits, notifications, variable computation, logging may run concurrently. **All accessibility interaction is serialized through a single mutex** because there is exactly one active window and one input focus; Android does not let an app drive two foreground apps at once, and we will not pretend otherwise. |
| Long-running execution while user leaves the app | Restricted | The enabled `AccessibilityService` keeps our process alive while a11y steps run. For non-a11y long runs we use a foreground service of type **`specialUse`** with subtype `user_initiated_macro_execution`, started **only from a user action while we are visible** (satisfies Android 12+ FGS-start rules and avoids the Android 15 `dataSync` 6 h cap and the `BOOT_COMPLETED` restrictions). `specialUse` requires a Play declaration and review; the alternative `shortService` (3-minute cap) is used automatically when the macro's static time budget is ≤ 3 min. |
| One-time and periodic schedules | Feasible with WorkManager | Periodic minimum interval 15 min and inexact by design; Doze/App Standby defer further; Android 16 enforces runtime quotas by standby bucket ([source](https://developer.android.com/about/versions/16/behavior-changes-all)). Missed-run policy (`RUN_LATE` / `SKIP` / `RUN_ONCE_COALESCED`) is evaluated at execution time from the persisted schedule. |
| Exact-time schedules | **Unsupported in MVP** | `SCHEDULE_EXACT_ALARM` is denied by default on 14+ and Play permits it only for alarm-clock/calendar-class core functionality; `USE_EXACT_ALARM` is limited to alarm/calendar apps. The app is neither. Alternative: WorkManager with a ±15 min expectation displayed in the UI; a post-MVP opt-in using `AlarmManager.setWindow` (inexact, no permission) is possible if you want tighter-but-still-inexact timing. |
| Scheduled runs that need UI automation | Restricted | Precondition check at start: a11y service enabled **and** device interactive **and** keyguard not locked. Otherwise the run enters `BLOCKED` with a reason and (optionally) a "tap to run now" notification. It will never try to unlock or draw over the lock screen. |
| Reboot handling | Feasible | WorkManager re-registers its own work after reboot; we do not need `RECEIVE_BOOT_COMPLETED` for schedules. A one-time worker reconciles `RUNNING` execution records left by process death into `INTERRUPTED`. |
| Monitoring / logs / history / diagnostics export | Feasible | Room; export as a JSON file via SAF `ACTION_CREATE_DOCUMENT` with redaction. |

### 3.4 Explicitly unsupported (with the compliant alternative)

| Request one might expect | Why not | Alternative |
|---|---|---|
| Controlling apps in the background / while another app is on screen | An `AccessibilityService` only sees and acts on the **active window**; there is no API to interact with a non-visible app. | Serialize UI macros; bring the target app forward first (foreground launch or user tap). |
| Multiple apps controlled concurrently | One active window, one focus. | Parallelism only for independent non-UI steps. |
| Acting while the screen is locked or off | Keyguard blocks a11y interaction with underlying apps; drawing over the lock screen is disallowed for third-party apps. | `BLOCKED` state + notification. |
| Reading/entering passwords or OTP codes | Play "Permissions and APIs that Access Sensitive Information" + a11y policy; Android 17 delays SMS OTP delivery to non-recipients. | Refused at the engine; documented. |
| Autonomous decision-making ("AI agent" behaviour) | Play: "Any use of the Accessibility API that enables an app to autonomously initiate, plan, and execute actions or decisions is strictly prohibited. This does not prohibit deterministic, rule-based automation, where behavior follows a static, human-defined script" ([source](https://support.google.com/googleplay/android-developer/answer/10964491)). | The entire macro model is deterministic and user-authored. |
| Silent APK install, install from a scheduled macro | Requires device-owner/privileged app. | User-confirmed `PackageInstaller` flow only, from the foreground. |
| Exact alarms | Permission/policy (see 3.3). | WorkManager. |
| `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE` | Play-restricted; not needed. | `<queries>` + SAF. |
| Marking ourselves `isAccessibilityTool="true"` | Play explicitly lists "automation tools" as **not** accessibility tools; misuse leads to suspension. | Prominent disclosure + consent + Play declaration form as a non-tool user of the API. |

---

## 4. How UI automation will work — and when it cannot

1. **Enablement.** The user reads a dedicated, separate in-app disclosure (what the a11y service can read — the content of the active window of any app while a macro is running; what we do with it — nothing leaves the device; what it never does), ticks a consent box, and is sent to *Settings → Accessibility* to enable "Macro-Android Automation". This screen is shown in the normal flow the first time a macro containing a11y steps is run or saved, not buried in settings (Play prominent-disclosure requirements, [source](https://support.google.com/googleplay/android-developer/answer/10964491)). On Android 13+, sideloaded apps hit "Restricted setting" until the user allows it from App info — the onboarding explains that; the Play-installed build is not affected.
2. **Service configuration.** `accessibilityEventTypes` limited to `typeWindowStateChanged|typeWindowContentChanged`; `canRetrieveWindowContent=true`; `canPerformGestures=false` in MVP; `flagReportViewIds`; `isAccessibilityTool` **absent/false**; `notificationTimeout` ≥ 100 ms; `accessibilityFeedbackType=feedbackGeneric`. No `flagRequestFilterKeyEvents`, no `canRequestFingerprintGestures`, no `canTakeScreenshot` in MVP.
3. **Idle behaviour.** When no macro is running, the service holds no window snapshots, records nothing, and its event callback returns immediately. The engine's `AccessibilityGateway` is only attached for the duration of a run. This is auditable in code and in tests.
4. **During a run.** The engine acquires the single `uiLock`, verifies preconditions (service connected, `isInteractive`, `!isKeyguardLocked`), and executes a11y steps against `rootInActiveWindow`. Each node lookup is bounded by the step timeout and polls on window-change events rather than busy-waiting. Any exception from the a11y layer becomes a typed `ExecutionError` (`SERVICE_DISCONNECTED`, `NODE_NOT_FOUND`, `NODE_NOT_ACTIONABLE`, `WINDOW_CHANGED`, `PRECONDITION_SCREEN_LOCKED`, …).
5. **User override.** A persistent, non-dismissible "Macro running — STOP" notification (from the FGS or the a11y service) and a hardware-key stop (volume-down long-press, if you want it) cancel the run immediately. Cancellation is cooperative at step boundaries and pre-emptive within waits/lookups.
6. **When it cannot work:** target app not visible; keyguard locked / screen off; the target marks nodes `accessibilityDataSensitive` or has no ids/text (some apps intentionally strip them — see the Telegram note in [this analysis](https://chocapikk.com/posts/2026/android-a11y-god-mode/)); the user has not enabled the service or the system disabled it (OEM battery managers do this); a system dialog (permission prompt, `PackageInstaller`) is on top — those are `TYPE_SYSTEM` windows we deliberately do not touch; another accessibility service is consuming the events; the target is a different user profile (work profile) — cross-profile interaction is not possible.

---

## 5. Permissions — required, justified, and explicitly rejected

### 5.1 Requested

| Permission / special access | Type | Phase | Justification | Play declaration |
|---|---|---|---|---|
| `POST_NOTIFICATIONS` | Runtime (13+) | 8 | Execution progress, blocked-run "tap to continue", schedule results, macro "Send notification" action. App works without it (in-app status only). | none |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Normal + typed (14+) | 8 | Keep a **user-initiated** macro run alive after the user leaves the app; subtype `user_initiated_macro_execution` declared via `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. | FGS declaration in Play Console (specialUse requires it) |
| `BIND_ACCESSIBILITY_SERVICE` (on the service, system-only) | Service declaration | 7 | Visible-node click/scroll/text and global back/home. | Accessibility declaration form + demo video showing disclosure & consent |
| `<queries>` for `ACTION_MAIN`/`CATEGORY_LAUNCHER` | Manifest visibility | 4 | List launchable apps. | none |
| `<queries>` for `ACTION_VIEW` `http/https` | Manifest visibility | 6 | Resolve "Open URL" to a browser and give a clear error if none. | none |
| `WAKE_LOCK` | Normal | 8 | Pulled in by WorkManager; no direct use. | none |
| `RECEIVE_BOOT_COMPLETED` | Normal | 8 | **Only** to run a lightweight reconciliation of interrupted executions and re-post persistent notifications; WorkManager handles its own re-scheduling. No FGS started from boot (Android 15 rule). If you prefer, this can be dropped and reconciliation done on next app open. | none |
| `REQUEST_INSTALL_PACKAGES` | Special access (Settings toggle) | post-MVP, flag | User-confirmed install of an imported APK via `PackageInstaller`. | Play: "core functionality" justification required; may be rejected |
| `SYSTEM_ALERT_WINDOW` | Special access | post-MVP, opt-in | Lets scheduled runs bring a target app forward (BAL exemption). Without it, scheduled UI macros require a tap. | none, but scrutinized |

### 5.2 Explicitly **not** requested
`QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `READ/WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `INTERNET` (the app has **no network access at all**; `Open URL` hands off to the browser), `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (we link to the settings screen and explain; we do not prompt), `PACKAGE_USAGE_STATS`, `READ_SMS`, `RECORD_AUDIO`, `CAMERA`, location, contacts, `BIND_DEVICE_ADMIN`, `BIND_NOTIFICATION_LISTENER_SERVICE`.

---

## 6. Security risks (threat-model seeds for Phase 1)

| # | Risk | Mitigation direction |
|---|---|---|
| R1 | A11y service reads sensitive content of other apps | Only attached during a run; no persistence of window trees; password/sensitive nodes refused; disclosure + consent; audit log of every a11y action. |
| R2 | Malicious/corrupt APK crashes the parser or exhausts storage | Size cap (default 512 MB, configurable), streamed copy to app-private cache, `getPackageArchiveInfo` in a `try` with typed errors, ZIP-bomb-safe (we never inflate entries ourselves), cache eviction. |
| R3 | Imported macro JSON is hostile (huge, deeply nested, unknown action types, `intent:` URLs) | `kotlinx.serialization` strict mode, size cap (1 MB), schema-version check, closed `ActionType` enum, depth/step limits, URL scheme allow-list; unknown fields rejected on import (not ignored). |
| R4 | Macro used to spam notifications or hammer the UI | Per-macro and global rate limits (notifications/min, a11y actions/sec), repeat caps, total runtime cap. |
| R5 | Macro text parameters contain secrets | `sensitive` flag → excluded from logs, exports, diagnostics; stored in the Room DB which lives in app-private storage; optional Jetpack Security `EncryptedFile` for exports (decide in Phase 1 — Jetpack Security is deprecated in favour of platform Keystore usage; we will use Keystore-backed AES directly if encryption of exports is wanted). |
| R6 | Confused-deputy via exported components | `MainActivity` exported (launcher only); a11y service exported only with `BIND_ACCESSIBILITY_SERVICE`; no other exported components; `intentMatchingFlags="enforceIntentFilter"` opt-in on Android 16+. |
| R7 | Background activity launch abuse | We only launch from foreground or via user-tapped notification; `PendingIntent`s are `FLAG_IMMUTABLE`, explicit, with BAL modes set deliberately. |
| R8 | Process death / crash mid-run leaves inconsistent state | Execution records are written before/after every step in a transaction; reconciliation on start. |
| R9 | Persisted URI grant revoked | Every access checks `contentResolver.persistedUriPermissions`; typed `URI_PERMISSION_REVOKED`. |
| R10 | Diagnostics export leaks PII | Redaction layer: package names kept, text parameters flagged sensitive removed, node text truncated/hashed, device identifiers omitted. |
| R11 | Supply-chain | Version catalog pinned; Gradle dependency verification metadata generated in Phase 2; OWASP-style audit in Phase 10 via Gradle's resolution report and the GitHub Dependabot alerts. |
| R12 | User is socially engineered into enabling a11y for someone else's benefit | Disclosure wording, no deep-link that jumps straight to the a11y toggle without the consent screen, run history visible. |

---

## 7. MVP vs post-MVP **[decision]**

### MVP (Phases 2–9 build this)
- Installed launchable apps: list, search, sort, filter, favorites, launch, pin shortcut.
- APK import via SAF: persistent grant, validation, metadata, SHA-256, duplicates, revoked-grant handling, delete.
- Macro editor with typed steps: Launch app, Open URL, Wait, Set variable, If/Else (closed condition set), Repeat, Log, Send notification, Stop; and — behind the a11y consent — Press back/home, Click node, Scroll node, Enter text (non-password).
- Import/export JSON v1 with schema-version migration hooks; tags and profiles.
- Execution engine: sequential + safe parallel groups, single UI lock, cancellation, timeouts, retries, persistent records, structured logs, history, diagnostics export with redaction.
- Scheduling: one-time and periodic via WorkManager, missed-run policy, battery-aware constraints, blocked-run notification.
- Foreground service (`specialUse`/`shortService`) for user-initiated long runs.
- Permission center, settings, help/limitations, onboarding + a11y disclosure.
- CI: build, lint, detekt, unit tests, Room/WorkManager instrumented tests, Compose UI tests on an emulator.

### Post-MVP (designed for, not built in the first cut)
- User-confirmed APK install/uninstall via `PackageInstaller` (feature flag; Play justification required).
- Screenshot action (a11y `takeScreenshot` on 30+, or `MediaProjection`) behind separate consent.
- `SYSTEM_ALERT_WINDOW` opt-in so scheduled macros can bring apps forward without a tap.
- Coordinate gestures via `dispatchGesture` (per-step opt-in, marked "fragile" in UI).
- Inexact `AlarmManager.setWindow` timing option.
- Macro sharing between devices (still file-based; still no network).
- Baseline Profiles (kept in the plan for Phase 10; requires a Macrobenchmark module and an emulator run — feasible on CI given KVM).

---

## 8. Toolchain decisions **[decision]** and known risks

| Item | Decision | Risk / note |
|---|---|---|
| Build & verification location | GitHub Actions on this branch; `ubuntu-latest`, JDK 17, pre-installed SDK 36/37 | The sandbox cannot build. Every "builds/tests pass" statement will cite a run id. |
| AGP | 9.x latest stable resolved from `maven.google.com` in Phase 2 (9.4.0 as of today) | AGP 9 built-in Kotlin conflicts with KSP → `android.builtInKotlin=false` + explicit KGP, documented with the AGP 10 removal deadline. If the KGP/KSP pairing for AGP 9.4 turns out unresolved on the day, Phase 2 falls back to the newest AGP 8.13.x line, which Studio Quail still supports, and says so. |
| Kotlin / KSP | Highest Kotlin that the current KSP release targets (search results indicate Kotlin 2.3.20 ↔ KSP 2.3.x; will be confirmed from Maven metadata) | Do not invent versions — pinned from live metadata. |
| Hilt, Room, DataStore, WorkManager, Compose BOM, Navigation, Lifecycle, kotlinx.serialization/coroutines, detekt, ktlint | Latest stable from Maven metadata in Phase 2; each entry in the catalog gets a one-line justification in the Phase 2 doc | Navigation: Navigation 3 vs Navigation-Compose 2.9 — Phase 1 ADR. |
| Serialization | `kotlinx.serialization` (strict, no reflection, R8-friendly, sealed-class polymorphism suits the typed macro schema) | — |
| Static analysis | Android Lint (fatal on `NewApi`, `MissingPermission`, `ExportedService`…), detekt, ktlint | — |
| Instrumented tests on CI | `reactivecircus/android-emulator-runner` on API 30, 34, 36 (and 37 in Phase 10) | Emulator runs are slow (~10–15 min); gated to PRs and phase completion, not every push. |
| Dependency verification | `gradle --write-verification-metadata sha256` in Phase 2 | Will need refresh on every version bump. |

---

## 9. Supported devices and limitations summary (goes into the Help screen)

- Android 8.0+ phones and tablets; best on Android 13+ where notification and FGS behaviour is fully modern.
- UI automation requires: the accessibility service enabled, screen on and unlocked, target app in front. It cannot run two apps at once, cannot act on the lock screen, cannot read or type passwords, cannot interact with apps that hide their UI from accessibility services.
- OEM battery managers (Xiaomi/Huawei/Samsung "deep sleep") may disable the accessibility service or kill background work; the Permission Center detects `isIgnoringBatteryOptimizations` and shows OEM-specific guidance without prompting for the exemption.
- Schedules are inexact (WorkManager, ≥ 15 min for repeats); Doze may delay them further.
- Installed-app list contains only apps with a launcher entry.
- Installing APKs (when enabled post-MVP) always shows the system confirmation.

---

## 10. Acceptance criteria for Phase 0

- [x] Repository state and build environment verified, and the inability to build in the sandbox stated plainly, with a working alternative (GitHub Actions) proven by an actual run.
- [x] Current platform (Android 17 / API 37), Play target requirement (36), and current toolchain (AGP 9.4, Gradle 9.6, JDK 17) identified with sources.
- [x] Every requested feature classified as feasible / restricted / unsupported with reasoning and a compliant alternative.
- [x] Accessibility automation described honestly: foreground-only, single-window, consent-gated, deterministic; Play automation rule quoted.
- [x] Permission list with per-permission justification and an explicit "not requested" list.
- [x] Security risks enumerated as input for the Phase 1 threat model.
- [x] MVP / post-MVP split proposed.
- [x] Platform baseline proposed (`minSdk 26`, `compileSdk 37`, `targetSdk 36 → 37`).

## 11. Decisions I need from you before Phase 1

1. **targetSdk**: 36 now with a Phase-10 gate to 37 (recommended), or 37 from the start?
2. **APK installation (user-confirmed `PackageInstaller`)**: keep as post-MVP behind a feature flag (recommended), or exclude entirely from the product?
3. **Distribution**: Google Play (default assumption — all policy constraints above apply), or internal/sideload only? (Sideload does not relax the technical limits, but it changes the Play declaration work in Phase 11.)
4. **`RECEIVE_BOOT_COMPLETED`**: keep for post-reboot reconciliation (recommended, no FGS started from boot), or drop?
5. **Sensitive text encryption at rest**: plain Room in app-private storage (default), or Keystore-backed encryption of `sensitive` macro parameters (adds complexity, small real gain on a non-rooted device)?

Files created in this phase:
- `docs/phase-0-feasibility-and-constraints.md` (this document)
- `.github/workflows/toolchain-probe.yml` (environment probe; replaced by the real pipeline in Phase 2)
