# 07 — Execution State Machine

## 1. Execution states

```
                    ┌──────────────────────────────────────────────────────────┐
                    │                                                          │
  enqueue           ▼         slot free            preconditions ok            │ resume
 ─────────►  QUEUED ──────► PREPARING ───────────────────► RUNNING ◄──────────┐│
                │              │                             │  ▲             ││
                │              │ preconditions fail          │  │ retry/next  ││
                │              ▼                             │  │             ││
                │           BLOCKED ◄────────────────────────┘  │             ││
                │              │  (UI precondition lost mid-run)│             ││
                │              │ user tap + ok ─────────────────┘             ││
                │              │                                              ││
                │              │ blockedTimeout                               ││
                │              ▼                                              ││
                │         CANCELLED ◄──── user cancel (from any active state) ─┘│
                │                                                              │
                │              PAUSED ◄──── user pause (RUNNING only) ─────────┘
                │                │ user resume → RUNNING
                │
                ▼ queue timeout / queue full / macro disabled before start
              REJECTED

  RUNNING ──► COMPLETED (all steps done, or Stop action with success=true)
  RUNNING ──► FAILED    (step failed with onFailure=ABORT_MACRO after retries exhausted,
                         or total timeout, or Stop action with success=false)
  RUNNING ──► SKIPPED   (scheduled run evaluated missedRunPolicy=SKIP before any step)
  RUNNING/PAUSED/BLOCKED/PREPARING ──► INTERRUPTED (reconciliation after process death / reboot)
```

Terminal states: `COMPLETED`, `FAILED`, `CANCELLED`, `REJECTED`, `SKIPPED`, `INTERRUPTED`. Active states: `QUEUED`, `PREPARING`, `RUNNING`, `PAUSED`, `BLOCKED`.

### 1.1 State definitions

| State | Meaning | Holds slot? | Holds UI lock? | Persisted fields set |
|---|---|---|---|---|
| QUEUED | Accepted; waiting for a concurrency slot or for the same macro's previous run | no | no | `id, macroId, macroVersion, origin, runRequestId, queuedAt` |
| PREPARING | Slot acquired; snapshotting macro, resolving secure values, checking preconditions for the first segment | yes | no | `startedAt` (set when RUNNING) |
| RUNNING | Executing a step | yes | during UI segments | `startedAt, currentStepIndex, currentStepId, attempt` |
| PAUSED | User paused between steps (never inside a step) | yes | **released** | `pausedAt` |
| BLOCKED | A precondition for the next UI segment failed; waiting for user | **no** (slot released so background macros can run) | released | `blockedAt, blockedReason` |
| COMPLETED / FAILED / CANCELLED / REJECTED / SKIPPED / INTERRUPTED | Terminal | no | no | `endedAt, resultCode, errorCode?, errorCategory?, completedSteps, retryTotal, interruptedReason?` |

### 1.2 Transition table

| From | Event | Guard | To | Side effects (all inside one Room transaction where persisted) |
|---|---|---|---|---|
| — | `enqueue(request)` | `runRequestId` unseen in 24 h; queue has capacity; macro exists & enabled (or origin = STEP_TEST) | QUEUED | insert record; audit `RUN_ENQUEUED` |
| — | `enqueue` | duplicate `runRequestId` | (no-op) | return existing id |
| — | `enqueue` | queue full | REJECTED | insert terminal record `QUEUE_FULL` |
| QUEUED | slot available & (macro not running or `allowConcurrentSelf`) | — | PREPARING | acquire `Semaphore` |
| QUEUED | `queueTimeout` (default 30 min) elapsed | — | REJECTED(`QUEUE_TIMEOUT`) | |
| QUEUED | macro disabled/deleted | — | REJECTED(`MACRO_DISABLED`) | |
| PREPARING | snapshot ok; first segment is non-UI or all gates pass | — | RUNNING | set `startedAt`; start FGS if budget > 30 s and `ForegroundGate` passes; reserve `targetPackages` |
| PREPARING | gate fails for first UI segment | — | BLOCKED(reason) | release slot; post "tap to continue" (if notifications allowed) |
| PREPARING | secure value unavailable | — | FAILED(`SECURE_VALUE_UNAVAILABLE`) | |
| RUNNING | step completed | more steps | RUNNING (index+1) | persist step row `COMPLETED`; if next step is UI and no lock held → acquire lock (timeout → BLOCKED(`UI_LOCK_TIMEOUT`)); if next step is non-UI and lock held → release |
| RUNNING | step completed | no more steps | COMPLETED | persist; release lock/slot; stop FGS if idle; update dynamic shortcuts; audit `RUN_FINISHED` |
| RUNNING | step failed | retry policy allows | RUNNING (same index, attempt+1) after backoff delay | persist attempt row `FAILED`; delay is cancellable |
| RUNNING | step failed | retries exhausted & `onFailure=CONTINUE` | RUNNING (index+1) | persist |
| RUNNING | step failed | retries exhausted & `onFailure=JUMP_TO_LABEL` | RUNNING (label index) | persist |
| RUNNING | step failed | retries exhausted & `onFailure=ABORT_MACRO` | FAILED(code) | persist |
| RUNNING | step failed with category `PRECONDITION` (screen locked, a11y disconnected, foreground lost) | step is UI | BLOCKED(reason) | current step will be **re-attempted from its start** on resume; attempt counter not incremented; slot & lock released |
| RUNNING | total timeout | — | FAILED(`MACRO_TIMEOUT`) | cancel step job, await, persist |
| RUNNING | `Stop` action | `success=true/false` | COMPLETED / FAILED(`STOPPED_BY_MACRO`) | |
| RUNNING | user pause | between steps only (flag checked at boundary) | PAUSED | release lock (not slot) |
| PAUSED | user resume | gates for next step pass | RUNNING | re-acquire lock if needed |
| PAUSED | user resume | gate fails | BLOCKED | |
| BLOCKED | user tap / app foregrounded & gates pass | slot available | RUNNING (same index) | re-acquire slot then lock |
| BLOCKED | user tap & gates pass | no slot | QUEUED (priority head) | |
| BLOCKED | `blockedTimeout` | — | CANCELLED(`BLOCKED_TIMEOUT`) | dismiss notification |
| any active | user cancel | — | CANCELLED(`USER`) | `job.cancel()`; await children; persist under `NonCancellable`; release resources |
| any active | reconciliation finds record with dead owner (`ownerToken` ≠ current process token) | — | INTERRUPTED(`PROCESS_DEATH` \| `REBOOT`) | posted summary notification (boot) |
| RUNNING | worker-origin run evaluates missed policy SKIP | before step 0 | SKIPPED(`MISSED`) | |

`ownerToken` = a UUID generated per process start and written into every active record when the process takes ownership; reconciliation marks records with a different token. This is how process death is detected without relying on the killed process.

## 2. Step states (per attempt)

```
PENDING ──► RUNNING ──► COMPLETED
               │  ├──► FAILED(code, category, retryable)
               │  └──► TIMED_OUT (≡ FAILED with STEP_TIMEOUT)
               └──► CANCELLED
  SKIPPED (step disabled, or branch not taken, or JUMP passed over it)
```

Each attempt is a row in `execution_steps(executionId, stepId, stepIndex, attempt, state, startedAt, endedAt, errorCode?, errorCategory?, outputSummary?)`. Logs reference `(executionId, stepIndex, attempt)`.

## 3. Progress calculation

`totalStaticSteps` = number of leaf steps after statically expanding `Repeat` blocks with constant counts (variable-bound repeats count as 1 and mark progress `indeterminateAfterIndex`). `progress = completedLeafSteps / totalStaticSteps` when determinate; UI shows a determinate bar until `indeterminateAfterIndex` and an indeterminate one after.

## 4. Persistence points (crash-safety)

1. `enqueue` → insert (QUEUED).
2. PREPARING → update state + ownerToken.
3. RUNNING start → update state, `startedAt`.
4. Every step attempt start → insert step row (RUNNING) + update `currentStepIndex/attempt` (same transaction).
5. Every step attempt end → update step row + insert its log rows (same transaction).
6. Every state change → update.
7. Terminal → update with `endedAt`, counts (transaction under `NonCancellable`).

A crash between 4 and 5 leaves a step row in RUNNING with the record in RUNNING; reconciliation marks both INTERRUPTED. A crash before 1 loses nothing (no side effects yet). Room WAL mode is on; each transaction is small (< 10 ms typical).

## 5. Origins

`MANUAL`, `SHORTCUT`, `SCHEDULE(scheduleId)`, `STEP_TEST(stepId)`, `NOTIFICATION_RESUME` (used only as the trigger of a BLOCKED→RUNNING transition; not a new execution).

## 6. Events exposed to UI (`ExecutionEvent`)

`StateChanged(id, from, to, reason?)`, `StepStarted(id, index, attempt)`, `StepFinished(id, index, attempt, result)`, `Log(entry)`, `Progress(id, completed, total, indeterminate)`. Emitted on a `SharedFlow(replay=0, extraBufferCapacity=256, onBufferOverflow=DROP_OLDEST)`; the UI reads durable state from Room `Flow`s and uses events only for animations/snackbars, so dropped events never corrupt UI state.
