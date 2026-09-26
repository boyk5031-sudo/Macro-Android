# ADR-0005 — All scheduling through WorkManager; no exact alarms

- Status: Accepted

## Context
`SCHEDULE_EXACT_ALARM` is denied by default on Android 14+ and is policy-restricted to alarm/calendar-class core functionality; `USE_EXACT_ALARM` is limited to alarm clock/calendar apps. Android 16 tightens job runtime quotas by standby bucket. The product's schedules are "run my macro around 07:30", not "at 07:30:00".

## Decision
- **Every kind of schedule is a unique one-time `OneTimeWorkRequest`** with `setInitialDelay`, unique name `schedule:<id>`, policy `REPLACE` (`WorkManagerScheduler`). `PeriodicWorkRequest` is *not* used, even for intervals: "daily at 07:30", weekly days and DST transitions are computed exactly by `NextRunCalculator` in the schedule's zone with `java.time`, and a periodic request's 15-minute minimum / flex window would only approximate that.
- `ScheduleWorker` fires one occurrence: it hands the macro to the application-scoped `MacroRunner`, waits at most `AWAIT_MAX = 9 min` (below WorkManager's 10-minute worker limit) for the outcome, records `lastFiredAt`, then enqueues the next occurrence via `planAfter` (so a delayed run can never fan out into duplicates). If WorkManager stops the worker, the execution is cancelled explicitly so history never shows a phantom "running" row.
- **Missed-run policy** (`RUN_LATE` / `SKIP` / `RUN_ONCE_COALESCED`, per-schedule `lateThreshold`) is evaluated in two places with the same pure function (`NextRunCalculator.decideMissed`): in the worker when it wakes up late, and in `reconcileAll()` when a planned `nextRunAt` is already in the past (process death, reboot, app update). Skipped occurrences are recorded as `SKIPPED_MISSED` on the schedule row so the decision is auditable.
- Constraints: battery-not-low default on; charging optional; device-idle optional only for macros without UI steps (validator rule `REQUIRES_DEVICE_IDLE_WITH_UI`).
- `BOOT_COMPLETED` → `BootReconciler` enqueues a single unique reconcile job (`KEEP`) that only calls `reconcileAll()`; it never starts a macro directly and WorkManager restores its own persisted work.
- The UI states inexactness explicitly (Android 12+ WorkManager may defer by minutes; Android 16 job quotas by standby bucket apply even while the app is foreground).
- No `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`; both are on the CI forbidden-permission list. An inexact `AlarmManager.setWindow` path remains a possible post-MVP option and needs no permission.

## Consequences
- No exact-alarm permission prompts, no policy exposure, correct Doze behaviour by construction.
- Users needing minute-precise triggers are told the app does not provide them.
