# ADR-0005 — All scheduling through WorkManager; no exact alarms

- Status: Accepted

## Context
`SCHEDULE_EXACT_ALARM` is denied by default on Android 14+ and is policy-restricted to alarm/calendar-class core functionality; `USE_EXACT_ALARM` is limited to alarm clock/calendar apps. Android 16 tightens job runtime quotas by standby bucket. The product's schedules are "run my macro around 07:30", not "at 07:30:00".

## Decision
- One-time: `OneTimeWorkRequest` with `setInitialDelay`, unique name `schedule-<id>`, `REPLACE`.
- Interval (≥ 15 min): `PeriodicWorkRequest` with a 5-minute flex window.
- Daily/weekly at a local time: one-time work for the next occurrence computed in the schedule's zone with `java.time`; the worker re-enqueues the next occurrence (unique name, `KEEP`), so a missed or delayed run cannot fan out into duplicates.
- Constraints: battery-not-low default on; charging optional; device-idle optional only for non-UI macros.
- Missed-run policy evaluated in the worker (RUN_LATE / SKIP / RUN_ONCE_COALESCED).
- `BOOT_COMPLETED` → `ReconcileWorker` (expedited=false) only repairs bookkeeping; WorkManager restores its own work.
- The UI states inexactness explicitly. A future inexact `AlarmManager.setWindow` option is possible without permission but is post-MVP.

## Consequences
- No exact-alarm permission prompts, no policy exposure, correct Doze behaviour by construction.
- Users needing minute-precise triggers are told the app does not provide them.
