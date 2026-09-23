# 01 — Functional Requirements

Conventions
- ID format: `FR-<AREA>-<n>`. Areas: APP (installed apps), APK (imported APKs), MAC (macro authoring), EXE (execution), SCH (scheduling), MON (monitoring), PRM (permissions & consent), SET (settings), HLP (help), SEC (security behaviours visible to users).
- Priority: **M** = MVP (built in Phases 3–9), **P** = post-MVP (designed for, not built).
- Each requirement has acceptance criteria in Given/When/Then form. Criteria are the basis for the tests written in later phases; test IDs will reference FR IDs.
- "The app" = Macro-Android. "A11y service" = the app's `AccessibilityService`.

---

## APP — Installed applications

### FR-APP-1 (M) List launchable installed apps
The app lists every installed package that has an activity matching `ACTION_MAIN` + `CATEGORY_LAUNCHER` and is visible under the app's `<queries>` declaration.
- Given the device has N launchable apps visible to the app, When the Installed Apps screen is opened, Then N entries are shown, each with label, icon, package name, version name, and version code.
- Given the list is loading, Then a loading state is shown; the main thread is never blocked (verified by StrictMode in debug and by a Macrobenchmark frame-time check in Phase 10).
- Given no launchable apps are visible (e.g. restricted profile), Then an empty state explains package-visibility limits and links to Help.
- Given a package is installed, removed, or updated while the screen is open, Then the list updates within 2 s (via `ACTION_PACKAGE_ADDED/REMOVED/CHANGED/REPLACED` receiver registered while the screen is resumed).

### FR-APP-2 (M) Search, sort, filter
- Given the list, When the user types in the search field, Then results filter by label or package name (case- and diacritic-insensitive) with each keystroke debounced ≤ 300 ms.
- Sort options: label A→Z (default), most recently installed, most recently updated, favorites first. The selection persists in DataStore.
- Filter options: favorites only; system apps hidden/shown (default hidden; "system" = `FLAG_SYSTEM` set and not updated by user).

### FR-APP-3 (M) Favorites
- When the user toggles the star on an app, Then the favorite state persists (Room, keyed by package name) across process death and reboot.
- Given a favorite app is uninstalled, Then it is shown greyed with an "Uninstalled" chip until the user removes it; it is never silently deleted.

### FR-APP-4 (M) Launch app
- When the user taps Launch, Then the app calls `startActivity` with the resolved launch intent and `FLAG_ACTIVITY_NEW_TASK`.
- Given the target has no launch intent, is disabled, or resolution throws `ActivityNotFoundException`/`SecurityException`, Then a non-technical error snackbar is shown and the failure is written to the audit log with error code `APP_LAUNCH_FAILED`.

### FR-APP-5 (M) Pin shortcut
- Given `ShortcutManagerCompat.isRequestPinShortcutSupported` is true, When the user taps "Add to Home screen", Then a pinned shortcut request is issued with the app's icon and label; the launch intent targets the app's `ShortcutTrampolineActivity`, which relaunches the target and records an audit entry.
- Given pinning is unsupported by the launcher, Then the action is disabled with an explanatory tooltip.
- Dynamic shortcuts: the 4 most recently *run* macros are published as dynamic shortcuts (updated after each run; respects `getMaxShortcutCountPerActivity`).

### FR-APP-6 (M) App detail
- Shows: label, package, version, first-install and last-update time, enabled state, whether it is a system app, number of macros referencing it, and actions Launch / Favorite / Pin / Open system App Info (`ACTION_APPLICATION_DETAILS_SETTINGS`).

---

## APK — Imported APK files

### FR-APK-1 (M) Import via SAF
- When the user taps Import, Then `ACTION_OPEN_DOCUMENT` is launched with `CATEGORY_OPENABLE`, MIME types `application/vnd.android.package-archive` and `application/octet-stream`, and `EXTRA_ALLOW_MULTIPLE = true`.
- On result, for each URI the app calls `takePersistableUriPermission(READ)` and enqueues analysis; the UI shows per-file progress.
- Given more than 25 URIs are returned, Then the app imports the first 25 and reports the rest as skipped (`LIMIT_EXCEEDED`).

### FR-APK-2 (M) Validation and metadata extraction
For each imported URI, in a `Dispatchers.IO` worker:
1. Query `OpenableColumns.SIZE` and `DISPLAY_NAME`. If size > configured max (default 512 MiB), fail with `FILE_TOO_LARGE` without reading.
2. Stream the content to `cacheDir/apk-import/<uuid>.apk` while computing SHA-256; abort on `IOException` with `FILE_UNREADABLE`.
3. Verify ZIP magic `PK\x03\x04` at offset 0; otherwise `NOT_AN_APK`.
4. Call `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES | GET_META_DATA)`; `null` → `APK_PARSE_FAILED`.
5. Extract package name, version name/code, min/target SDK, label, icon (via `applicationInfo.sourceDir = publicSourceDir = path`), signing certificate SHA-256 digests, split name (if any), and whether it declares `<uses-feature>`s not on this device.
6. Persist an `ImportedApk` record with the URI, display name, size, SHA-256, metadata, `status = READY`, and delete the cached copy unless "Keep local copy" is on.
- Given any step fails, Then the record is stored with `status = INVALID`, the error code, and the display name so the user can see and delete it.

### FR-APK-3 (M) Duplicate detection
- Exact duplicate: identical SHA-256 already present → the import is rejected with `DUPLICATE_EXACT` and the existing entry is highlighted.
- Semantic duplicate: same package name + version code + signing digest but different SHA-256 → imported, flagged `DUPLICATE_SEMANTIC`, both shown with a "Possible duplicate" chip.

### FR-APK-4 (M) Installed-state resolution
- For each `READY` APK, the app resolves whether the same package is installed and visible: `INSTALLED_SAME_VERSION`, `INSTALLED_OLDER`, `INSTALLED_NEWER`, `INSTALLED_DIFFERENT_SIGNATURE`, `NOT_VISIBLE` (package not resolvable under `<queries>` — shown as "Unknown", never "Not installed").

### FR-APK-5 (M) Revoked or missing URI permission
- Before any read, the app checks `contentResolver.persistedUriPermissions` contains the URI with read permission; if not, or if opening throws `SecurityException`/`FileNotFoundException`, the record transitions to `status = UNAVAILABLE` with `URI_PERMISSION_REVOKED` and the UI offers "Re-select file" (which re-runs import and, on matching SHA-256, relinks the record).

### FR-APK-6 (M) APK list and detail
- List shows display name, package, version, size, status chip, installed-state chip, import date; sortable by name/date/size; searchable.
- Detail shows all metadata, both checksums (SHA-256 of file and of signing certs), permissions declared by the APK (`requestedPermissions`), and actions: Re-verify checksum, Share (via `ACTION_SEND` with explicit `FLAG_GRANT_READ_URI_PERMISSION`), Open containing folder if the provider supports `DocumentsContract`, Delete.

### FR-APK-7 (M) Delete
- When the user confirms delete, Then the Room record is removed, the persisted URI permission is released (`releasePersistableUriPermission`) if no other record uses it, and any local copy is deleted. The source file is never deleted.

### FR-APK-8 (excluded) Install / uninstall
- **Not implemented.** No `REQUEST_INSTALL_PACKAGES`, no `PackageInstaller` usage. The APK detail screen shows a help link "Why can't I install from here?" explaining the decision (ADR-0007).

---

## MAC — Macro authoring

### FR-MAC-1 (M) Macro list
- Shows all macros with name, description (truncated), tags, profile, enabled state, step count, last run result and time, schedule indicator, and whether it requires the a11y service.
- Group by profile or tag; filter by enabled/disabled, requires-a11y, has-schedule; search by name/description/tag.
- Actions per macro: Run, Edit, Duplicate, Enable/Disable, Export, Delete (confirmation).

### FR-MAC-2 (M) Create / edit
- Editor fields: name (1–80 chars, required, unique per profile), description (≤ 500), tags (≤ 10, each ≤ 30 chars), profile (select or create), enabled toggle, execution policy (timeouts, retries, concurrency class), and an ordered step list.
- Unsaved-change protection: back navigation or process death with unsaved edits → draft is persisted to `SavedStateHandle` + a Room `macro_drafts` row; on return the user is offered Restore / Discard.

### FR-MAC-3 (M) Step management
- Add step (picker grouped by category with a description and "requires a11y" badge), edit step in a typed form, remove (with undo snackbar), duplicate, reorder by drag handle or up/down buttons (buttons required for TalkBack), enable/disable a step without deleting it.
- Nested blocks (`If`, `Repeat`) render as indented groups; max nesting depth 4.

### FR-MAC-4 (M) Validation before save
The validator (pure Kotlin, in `automation:engine`) returns a list of `ValidationIssue(path, code, severity)`. Save is blocked on any `ERROR`; `WARNING`s are shown and can be acknowledged. Rules are listed in `08-macro-schema.md §6`. Examples: empty macro, unknown package, URL scheme not allowed, repeat count > 100, expanded step budget > 200, variable referenced before set, a11y step present while consent not granted (WARNING, not ERROR — the macro can be saved but not run).

### FR-MAC-5 (M) Test a single step
- From the editor, the user can run one step in isolation with the current variable set; it goes through the real engine as a one-step execution with `origin = STEP_TEST`, appears in history flagged as a test, and obeys all the same permission checks.

### FR-MAC-6 (M) Preview
- A read-only, TalkBack-friendly summary of the macro as numbered sentences ("1. Launch *Settings*. 2. Wait 2 s. 3. If node 'Wi-Fi' exists → click it, else → log 'not found'."), including estimated maximum runtime from the static time budget.

### FR-MAC-7 (M) Export
- Single macro or selection → JSON file via `ACTION_CREATE_DOCUMENT` (`application/json`). Format is `08-macro-schema.md §4` with `schemaVersion = 1`. Values flagged `sensitive` are exported as `{"redacted": true}` unless the user explicitly toggles "Include sensitive values" in the export dialog (with a warning). Exported files are never written outside the user-chosen location.

### FR-MAC-8 (M) Import
- Via `ACTION_OPEN_DOCUMENT` (`application/json`, `*/*` fallback). File size limit 1 MiB. Parsing uses strict `kotlinx.serialization` (`ignoreUnknownKeys = false`, `isLenient = false`). Schema version lower than current → migrated by the migration chain; higher → rejected with `SCHEMA_TOO_NEW`. Name collisions → user chooses Rename / Replace / Skip per macro. Imported macros are always created `enabled = false` and with consent-requiring steps flagged until the user reviews them.

### FR-MAC-9 (M) Duplicate
- Creates a copy named "<name> (copy)" in the same profile, disabled, with no schedules, preserving steps and policies.

### FR-MAC-10 (M) Profiles and tags
- Profiles are named groups (e.g., "Work", "Home"); a macro belongs to exactly one profile (default "General"). Deleting a profile moves its macros to "General". Tags are free-form labels with autocomplete from existing tags.

---

## EXE — Execution

### FR-EXE-1 (M) Run a macro manually
- From list, detail, dynamic shortcut, or "Run now" notification. Running starts an `Execution` with `origin = MANUAL`, checks preconditions (§FR-EXE-6), and transitions through the state machine in `07-execution-state-machine.md`.

### FR-EXE-2 (M) Sequential execution with structured concurrency
- Steps run in order inside a per-execution `CoroutineScope` (`SupervisorJob` + `Dispatchers.Default`); each step has its own child `Job`; step timeout via `withTimeout`; cancellation of the execution cancels all children and awaits their completion before the record is finalized.

### FR-EXE-3 (M) Safe parallel groups
- A `Parallel` block runs its child steps concurrently **only if** every child is `ConcurrencyClass.BACKGROUND_SAFE` (Wait, Log, SetVariable, SendNotification, Condition on variables). If any child is `UI` or `FOREGROUND`, the validator rejects the block (`PARALLEL_CONTAINS_UI_STEP`). Max 8 children.

### FR-EXE-4 (M) Concurrency limits across executions
- Global limits (configurable in Settings within bounds): max 3 concurrent executions overall; exactly 1 execution may hold the UI lock at a time; queue depth 20. A new run beyond limits enters `QUEUED`; beyond queue depth it is rejected with `QUEUE_FULL`.
- The same macro cannot run concurrently with itself unless its policy sets `allowConcurrentSelf = true` (default false) — otherwise the second run is `QUEUED`.

### FR-EXE-5 (M) UI lock and conflict detection
- Any step with `ConcurrencyClass.UI` (all a11y steps, LaunchApp, OpenUrl) acquires the single `uiLock` (`Mutex`) for the duration of the **contiguous UI segment** of the macro, releasing it at the first non-UI step, so short background macros can interleave. Lock wait time is bounded by `executionPolicy.lockAcquireTimeout` (default 60 s) → `BLOCKED(UI_LOCK_TIMEOUT)`.
- Conflict detection: two executions that both declare the same `targetPackage` in a UI segment are serialized even if one has not yet reached its UI step (reservation at execution start).

### FR-EXE-6 (M) Preconditions and BLOCKED state
Before a UI segment: a11y service connected (if any a11y step), `PowerManager.isInteractive`, `KeyguardManager.isKeyguardLocked == false`, app may launch activities (foreground or launched from notification tap within the last 10 s). Failure → `BLOCKED(reason)`. A blocked execution posts a notification "Tap to continue" (if notifications permitted); tapping opens the app, re-checks, and resumes. Blocked executions expire after `executionPolicy.blockedTimeout` (default 10 min) → `CANCELLED(BLOCKED_TIMEOUT)`.

### FR-EXE-7 (M) Timeouts
- Per step: `step.timeout` (default per action type; max 10 min). Per macro: `executionPolicy.totalTimeout` (default 30 min; max 2 h). Timeout → step `FAILED(STEP_TIMEOUT)`, then retry policy.

### FR-EXE-8 (M) Retry policy
- `RetryPolicy(maxAttempts 1–5, backoff FIXED|EXPONENTIAL, initialDelay, maxDelay, retryOn: set of error categories)`. Only errors whose category is in `retryOn` and whose `retryable` flag is true are retried. Retries are recorded per attempt.

### FR-EXE-9 (M) Failure handling
- `step.onFailure ∈ {ABORT_MACRO (default), CONTINUE, JUMP_TO_LABEL}`. `JUMP_TO_LABEL` may only jump forward (validator enforces) to prevent loops outside `Repeat`.

### FR-EXE-10 (M) Cancellation
- User can cancel from the monitor screen, the foreground-service notification action, or the a11y service's stop affordance. Cancellation is delivered via `Job.cancel()`; a11y steps check `isActive` between node operations; `Wait` is a cancellable `delay`. The execution finalizes as `CANCELLED(USER)` after all children complete; the record stores the step index at cancellation.

### FR-EXE-11 (M) Persistence and process-death recovery
- An `execution_records` row is inserted at `QUEUED`; updated on every state change and at every step boundary (start and end) in a single transaction with the step's log entries. On app start and on `BOOT_COMPLETED`, any record in `RUNNING`/`PAUSED`/`BLOCKED` whose owning process is gone is transitioned to `INTERRUPTED(PROCESS_DEATH | REBOOT)`. Executions are **not** automatically resumed after process death (UI state is unknowable); the user is offered "Run again".

### FR-EXE-12 (M) Idempotency
- A `runRequestId` (UUID) is supplied by every trigger (UI action, worker, shortcut). Enqueueing the same `runRequestId` twice within 24 h is a no-op returning the existing execution id (protects against duplicated WorkManager delivery and double taps).

### FR-EXE-13 (M) Foreground service
- When an execution with static time budget > 30 s starts from a user action while the app is visible, the engine starts `ExecutionForegroundService` (type `specialUse`, subtype property `user_initiated_macro_execution`; `shortService` when budget ≤ 3 min) with a persistent notification showing macro name, current step, progress, elapsed time, and a Cancel action. The service stops when no executions are active. It is never started from a `BOOT_COMPLETED` receiver or from a worker.
- If the FGS cannot be started (`ForegroundServiceStartNotAllowedException`), the execution still runs in-process; the record notes `fgsUnavailable = true` and Help explains the reduced reliability.

### FR-EXE-14 (M) Background-safe execution from a worker
- Scheduled executions run inside a `CoroutineWorker` (`MacroRunWorker`). If the macro contains any `UI` step, the worker performs the precondition check; if not satisfied, the execution becomes `BLOCKED` (see FR-EXE-6) and the worker returns `Result.success()` (it does not hold a job slot waiting for the user).

### FR-EXE-15 (M) Rate limits
- Per execution: ≤ 10 notifications/minute, ≤ 5 a11y actions/second, ≤ 20 app launches/minute. Exceeding → step `FAILED(RATE_LIMITED)`; category `POLICY` (never retried).

---

## SCH — Scheduling

### FR-SCH-1 (M) One-time schedule
- User picks a macro, a date/time, and a time zone (default device zone). Persisted as `Schedule(kind = ONE_TIME, atEpochMillis, zoneId)`. Enqueued as `OneTimeWorkRequest` with `setInitialDelay`, unique name `schedule-<id>`, `ExistingWorkPolicy.REPLACE`.
- The UI states "Runs at approximately <time>; Android may delay it by up to 15 minutes or more in battery saver."

### FR-SCH-2 (M) Repeating schedule
- Kinds: `INTERVAL(minutes ≥ 15)`, `DAILY(localTime, zoneId, daysOfWeek ⊆ Mon..Sun)`. `INTERVAL` → `PeriodicWorkRequest` with `flexTimeInterval = 5 min`. `DAILY` → one-time work for the next occurrence; the worker computes and enqueues the next occurrence after running (idempotent via unique name).
- DST: next occurrence computed with `java.time.ZonedDateTime` in the schedule's zone; a skipped local time (spring forward) rolls to the next valid instant; an ambiguous time (fall back) uses the earlier offset.

### FR-SCH-3 (M) Constraints
- Per schedule: `requiresCharging`, `requiresBatteryNotLow` (default true), `requiresDeviceIdle` (only for macros with no UI steps; validator enforces), `requiresNetwork` (never — the app has no network permission; option absent).

### FR-SCH-4 (M) Enable / disable / delete
- Disabling cancels the unique work and keeps the row; enabling re-enqueues; deleting cancels and removes.

### FR-SCH-5 (M) Missed-run policy
- `missedRunPolicy ∈ {RUN_LATE (default), SKIP, RUN_ONCE_COALESCED}` evaluated by the worker: if `now - scheduledFor > lateThreshold (default 30 min)`, `SKIP` records `SKIPPED(MISSED)`; `RUN_LATE` runs; `RUN_ONCE_COALESCED` runs once even if multiple occurrences were missed (relevant after reboot/Doze).

### FR-SCH-6 (M) Reboot
- WorkManager restores its own work. The `BootReconcileReceiver` (`RECEIVE_BOOT_COMPLETED`) enqueues `ReconcileWorker`, which (a) marks stale `RUNNING` records `INTERRUPTED(REBOOT)`, (b) recomputes the next occurrence for `DAILY` schedules whose next-run work is missing, (c) posts a summary notification if any schedule was skipped. It never starts an execution or a foreground service.

### FR-SCH-7 (M) Schedule screen
- Lists schedules with macro name, kind, next planned run (from WorkManager `WorkInfo.nextScheduleTimeMillis` where available, else computed), last outcome, constraints, enabled state. Empty state explains inexactness and battery behaviour.

---

## MON — Monitoring

### FR-MON-1 (M) Execution monitor (live)
- Shows all active executions (`QUEUED`, `RUNNING`, `PAUSED`, `BLOCKED`) with macro name, state, current step index/name, progress (`completedSteps / totalStaticSteps` — indeterminate inside `Repeat` with variable bounds), elapsed time, retry count, and Cancel. Updates via Room `Flow` (no polling).

### FR-MON-2 (M) Execution history
- Paginated list (Paging 3, 50/page) of finished executions: state, macro, origin, start/end, duration, error code, retry total. Filters: state, macro, origin, date range. Detail shows the full step timeline and logs.

### FR-MON-3 (M) Structured logs
- `LogEntry(executionId, stepIndex?, timestamp, level ∈ {DEBUG, INFO, WARN, ERROR}, code?, message, redacted: Boolean)`. Debug-level entries are written only when Settings → Diagnostics → Verbose is on. Filter by level, step, text.

### FR-MON-4 (M) Retention and clear
- Default retention: 30 days or 5 000 executions or 200 000 log rows, whichever is hit first, enforced by a daily `RetentionWorker`. "Clear history" (confirmation) deletes finished executions and their logs; active executions are untouched.

### FR-MON-5 (M) Export diagnostics
- Produces a JSON file (via `ACTION_CREATE_DOCUMENT`) containing: app version, Android version, device model (no serial/IDs), permission states, schedule summaries, last 200 executions with logs. Redaction rules from `04-threat-model §6` are applied; the dialog states exactly what is included.

---

## PRM — Permissions and consent

### FR-PRM-1 (M) Permission center
- One screen showing each capability with state (granted / denied / not applicable on this Android version / restricted setting) and a button that opens the correct system screen: Notifications (runtime request on 33+; App notification settings otherwise), Accessibility service, Battery optimization status (opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` list — never the direct request dialog), Pinned shortcuts support (informational).

### FR-PRM-2 (M) Accessibility disclosure and consent
- Shown in the normal flow the first time the user (a) adds an a11y step, (b) runs or schedules a macro with one, or (c) opens the Accessibility row in the Permission center. It is a full-screen, separate dialog containing: what the service can read (content of the active window of any app while a macro is running), what it does with it (executes the user's macro steps on-device; nothing is transmitted; nothing stored except the audit log described), what it never does (no password fields, no lock screen, no autonomous actions), and a checkbox "I understand and agree". Only after affirmative consent does the "Open Accessibility settings" button enable. Consent is versioned (`consentVersion`), stored with timestamp; a change in disclosure text bumps the version and re-prompts.
- Withdrawal: Permission center → "Withdraw consent" disables all macros with a11y steps and links to the system toggle.

### FR-PRM-3 (M) Restricted-settings guidance
- If the a11y toggle is blocked ("Restricted setting", Android 13+ for sideloaded installs), the Permission center explains the App-info → "Allow restricted settings" path. (Play installs are not affected; the copy says so.)

### FR-PRM-4 (M) Notification permission flow
- Requested contextually (first run of a macro, first schedule, first a11y execution) with a pre-prompt rationale. If denied twice or "Don't ask again", the Permission center deep-links to system settings. All features that would notify degrade to in-app status.

---

## SET — Settings

### FR-SET-1 (M) Settings (DataStore-backed)
- Appearance: theme (System/Light/Dark), dynamic color (on 31+).
- Execution: max concurrent executions (1–3), queue depth (5–20), default step timeout, default total timeout, default retry policy, verbose logging.
- Import limits: max APK size (64 MiB–2 GiB), keep local copies (off).
- Retention: days (7–90), max executions, max log rows.
- Privacy: "Include sensitive values in exports" default (off; per-export override), "Confirm before running macros with a11y steps" (on).
- Diagnostics: export diagnostics, clear history, reset onboarding.
- About: version, licences (generated), privacy policy link, limitations.

---

## HLP — Help

### FR-HLP-1 (M) Help and limitations
- Static, searchable, offline content: what UI automation can/cannot do, package-visibility limits, schedule inexactness, OEM battery managers, why installation is not offered, how to write robust macros (prefer view ids, add waits, use conditions), troubleshooting (a11y service disabled by system, blocked executions, revoked file access).

---

## SEC — User-visible security behaviours

### FR-SEC-1 (M) Sensitive parameters
- Any `EnterText.text` and any `SetVariable.value` may be flagged `sensitive`. Sensitive values are encrypted at rest (ADR-0006), masked in the editor after entry (reveal on tap with confirmation), excluded from logs/exports/diagnostics by default, and never used in error messages.

### FR-SEC-2 (M) Audit log
- Append-only table of security-relevant events: consent granted/withdrawn, a11y service connected/disconnected, macro run started (with origin), a11y action performed (type + target package + node id/text hash, never text content), export performed (with sensitive flag), import performed (source display name), settings change of a security-relevant setting. Visible in Settings → Audit log; included in diagnostics export; subject to the same retention.

### FR-SEC-3 (M) Confirmation for destructive operations
- Delete macro/profile/APK record/schedule, clear history, withdraw consent, reset onboarding: modal confirmation naming the object; deletions of macros with schedules warn that schedules are removed.
