# 03 — User Journeys

Each journey lists the happy path, the failure branches that the UI must handle, and the FRs it exercises. These become the end-to-end test scenarios in Phases 9–10.

## J1 — First launch and onboarding
1. User opens the app → splash (`core-splashscreen`) → Onboarding pager: (a) what the app does, (b) what it deliberately does not do (no install, no background control of apps, no cloud), (c) optional notification permission pre-prompt (33+).
2. Lands on Dashboard: cards for Installed apps, Imported APKs, Macros, Schedules, Recent executions, and a "Set up permissions" card if anything is missing.
- Failure branches: notification permission denied → dashboard card explains impact; process death mid-onboarding → resumes at the same page (`rememberSaveable`).
- FRs: PRM-1, PRM-4, SET-1.

## J2 — Browse, favourite, launch, and pin an installed app
1. Installed apps → search "cam" → results filter → star Camera → tap → App detail → Launch (Camera opens) → back → Add to Home screen → launcher shows its confirmation → shortcut appears.
- Failure branches: app disabled by admin → Launch shows "This app can't be opened" (`APP_LAUNCH_FAILED`); launcher doesn't support pinning → button disabled with tooltip; app uninstalled while detail is open → screen shows "Uninstalled" state and disables actions.
- FRs: APP-1…6.

## J3 — Import APK files and inspect them
1. Imported APKs → Import → system file picker → user multi-selects 3 files → returns → three rows appear with spinners → each resolves to READY / INVALID with reason.
2. Tap a READY row → detail → see SHA-256, signing digest, declared permissions, "Installed: older version 1.2 (this file 1.3)".
3. Tap "Why can't I install from here?" → Help page section.
- Failure branches: file > 512 MiB → `FILE_TOO_LARGE`, row shows reason; a renamed `.zip` → `NOT_AN_APK`; duplicate → toast + existing row highlighted; provider revokes access later → row becomes UNAVAILABLE with "Re-select file"; picker cancelled → no change.
- FRs: APK-1…7.

## J4 — Create a macro without accessibility (background-safe)
1. Macros → New → name "Morning" → add steps: Send notification "Good morning" → Wait 2 s → Open URL https://news.example → Log "done" → Save (validation passes) → Run.
2. Monitor shows RUNNING → step 3 requires foreground: app is visible so the browser opens → COMPLETED.
- Failure branches: URL with `intent:` scheme → validation ERROR `URL_SCHEME_NOT_ALLOWED`; no browser installed → step FAILED `NO_ACTIVITY_FOR_INTENT`, `onFailure = ABORT_MACRO` → execution FAILED with clear message; user presses back with unsaved edits → "Discard changes?" dialog.
- FRs: MAC-2…6, EXE-1, EXE-2, EXE-9, MON-1.

## J5 — Create a macro that uses accessibility (foreground UI automation)
1. Editor → Add step → "Click visible element" → the a11y disclosure screen appears (first time) → user reads, ticks consent, taps "Open Accessibility settings" → enables "Macro-Android Automation" → returns → step form: match by view id `com.android.settings:id/search` (or text "Search") → Save.
2. Run: precondition check passes (service connected, screen on/unlocked, app visible) → step 1 Launch Settings (UI lock acquired) → step 2 Click → node found within timeout → click performed → COMPLETED. Audit log records the a11y action (type, target package, node id, text hash).
- Failure branches: user declines consent → step can be saved but macro shows "Needs consent" and Run is disabled with a link; service enabled but later disabled by an OEM battery manager → run BLOCKED(`A11Y_SERVICE_DISCONNECTED`) with notification; node not found → retries per policy → FAILED `NODE_NOT_FOUND`; target field is a password → FAILED `NODE_IS_PASSWORD` (policy, not retried); screen locks mid-run → the next a11y step fails with `PRECONDITION_SCREEN_LOCKED`, execution BLOCKED, resumes only if the user unlocks and taps the notification within 10 min.
- FRs: PRM-2, PRM-3, MAC-4, EXE-5, EXE-6, EXE-8, SEC-2.

## J6 — Schedule a macro and let the device sleep
1. Schedules → New → macro "Morning" → Daily 07:30, Mon–Fri, zone Asia/Kolkata, battery-not-low → Save → row shows "Next: Thu 07:30 (approx.)".
2. Overnight, Doze → at ~07:30–07:45 the worker runs → macro has an `OpenUrl` (UI) step → device is locked → execution BLOCKED → notification "Morning is waiting — tap to continue" → user unlocks, taps → app opens, precondition passes → continues → COMPLETED → next occurrence enqueued for Fri.
- Failure branches: user ignores the notification → after 10 min `CANCELLED(BLOCKED_TIMEOUT)`, history shows it; device rebooted at 07:00 → `ReconcileWorker` ensures Thu 07:30 work exists; missed by > 30 min with `SKIP` policy → `SKIPPED(MISSED)`; notifications denied → blocked execution visible only in-app; user disables the schedule → work cancelled, row greyed.
- FRs: SCH-1…7, EXE-6, EXE-14, PRM-4.

## J7 — Monitor, cancel, inspect logs, export diagnostics
1. Run a long macro (Repeat 50 × Wait 5 s) → foreground-service notification appears with progress → open Monitor → Cancel → execution CANCELLED(USER) within 2 s, service stops, notification dismissed.
2. History → tap the run → timeline shows 12 completed iterations, cancellation point, logs filtered to WARN+ → Export diagnostics → file saved → open it in a viewer: no sensitive values, node texts hashed.
- Failure branches: process killed by the system mid-run → on next open, history shows INTERRUPTED(PROCESS_DEATH) with "Run again"; retention worker trims old rows without touching the active run.
- FRs: MON-1…5, EXE-10, EXE-11, EXE-13, SEC-1.

## J8 — Export and import macros between devices
1. Macros → select 2 → Export → dialog shows "Sensitive values will be redacted" (toggle off by default) → Save to Downloads via picker.
2. On another device → Import → pick the file → preview shows 2 macros, 1 name collision → choose Rename → both imported disabled → user reviews, enables.
- Failure branches: file from a newer app version → `SCHEMA_TOO_NEW` with "Update the app"; malformed JSON → `IMPORT_PARSE_FAILED` with line/column if available; file > 1 MiB → `FILE_TOO_LARGE`; sensitive values redacted → imported steps show a "Value required" badge and validation ERROR until filled.
- FRs: MAC-7, MAC-8, SEC-1.

## J9 — Withdraw accessibility consent
1. Permission center → Accessibility → Withdraw consent → confirmation → all macros with a11y steps are disabled, their schedules cancelled, audit entry written → link to the system toggle to disable the service.
- FRs: PRM-2, SEC-2, SEC-3.

## J10 — Large screen / tablet
1. On an Expanded-width device, Installed apps and Macros use list-detail panes; the editor uses a two-pane layout (step list | step form); rotation preserves state.
- FRs: all screens; NFR-COMP-2.
