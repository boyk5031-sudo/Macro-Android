# 09 — Error Taxonomy

## 1. Structure

```kotlin
package com.macroandroid.core.common.error

enum class ErrorCategory(val defaultRetryable: Boolean) {
    VALIDATION(false),     // input rejected before anything ran
    PERMISSION(false),     // a permission/consent/gate is missing — user must act
    PRECONDITION(false),   // runtime condition not met (screen locked, service disconnected); → BLOCKED for UI steps
    TARGET_UI(true),       // the target app's UI did not behave as expected (node not found…) — commonly transient
    TRANSIENT(true),       // timeouts, IO hiccups, lock contention
    POLICY(false),         // our own limits (rate limit, budget) — never retried
    DATA(false),           // persistence/crypto/schema problems
    INTERNAL(false),       // bugs; logged with stack in debug
    CANCELLED(false),      // cooperative cancellation; not an error for the user
}

data class AppError(
    val code: ErrorCode,
    val category: ErrorCategory = code.category,
    val retryable: Boolean = code.retryable,
    val userMessageRes: Int = code.userMessageRes,   // string resource id, never raw text
    val detail: String? = null,                      // debug-only, redacted in release logs
    val cause: Throwable? = null,                    // never serialised
)

sealed interface AppResult<out T> { data class Ok<T>(val value: T); data class Err(val error: AppError) }
class AppException(val error: AppError) : RuntimeException(error.code.name, error.cause)
```

Rules
- Engine actions return `ActionResult.Failure(AppError)`; they never throw except `CancellationException` (rethrown) — enforced by a `runCatching`-style wrapper in the executor that converts stray exceptions to `INTERNAL/UNEXPECTED`.
- UI shows `userMessageRes` only; `detail` goes to debug logs. No `Throwable.message` from other apps in release.
- `retryable` may be narrowed per code but never widened beyond the category default (e.g., `NODE_IS_PASSWORD` is `TARGET_UI` but `retryable=false`).

## 2. Codes

### VALIDATION
`NAME_INVALID`, `NAME_DUPLICATE`, `NO_STEPS`, `STEP_LIMIT`, `NESTING_LIMIT`, `REPEAT_BOUNDS`, `PARALLEL_CONTAINS_UI_STEP`, `PARALLEL_SIZE`, `TIMEOUT_RANGE`, `RETRY_RANGE`, `PACKAGE_NAME_INVALID`, `URL_SCHEME_NOT_ALLOWED`, `SELECTOR_EMPTY`, `REGEX_INVALID`, `LABEL_DUPLICATE`, `JUMP_TARGET_MISSING`, `JUMP_BACKWARD`, `VARIABLE_NAME_INVALID`, `SECURE_IN_TEMPLATE`, `SENSITIVE_FLAG_REQUIRED`, `SECURE_VALUE_REDACTED`, `ACTION_NOT_SUPPORTED`, `CONTINUE_ON_CANCEL_RESERVED`, `REQUIRES_DEVICE_IDLE_WITH_UI`, `SCHEDULE_TIME_IN_PAST`, `SCHEDULE_INTERVAL_TOO_SHORT`, `IMPORT_PARSE_FAILED`, `IMPORT_DEPTH_EXCEEDED`, `SCHEMA_TOO_NEW`, `SCHEMA_NO_MIGRATION_PATH`, `FILE_TOO_LARGE`, `LIMIT_EXCEEDED`, `NOT_AN_APK`.

### PERMISSION
`NOTIFICATIONS_DENIED`, `A11Y_SERVICE_NOT_ENABLED`, `A11Y_CONSENT_MISSING`, `URI_PERMISSION_REVOKED`, `SHORTCUT_PIN_UNSUPPORTED`, `FGS_START_NOT_ALLOWED` (informational; execution continues).

### PRECONDITION
`PRECONDITION_SCREEN_OFF`, `PRECONDITION_SCREEN_LOCKED`, `A11Y_SERVICE_DISCONNECTED`, `FOREGROUND_REQUIRED` (activity launch not permitted from background), `UI_LOCK_TIMEOUT`, `BLOCKED_TIMEOUT`, `TARGET_WINDOW_NOT_ACTIVE` (expected package's window is not the active window when a selector has `packageName`).

### TARGET_UI (retryable unless noted)
`NODE_NOT_FOUND`, `NODE_NOT_VISIBLE`, `NODE_NOT_CLICKABLE`, `NODE_NOT_EDITABLE`, `NODE_NOT_SCROLLABLE`, `NODE_ACTION_REJECTED` (performAction returned false), `NODE_STALE` (node recycled between find and act), `WINDOW_CHANGED`, `NODE_IS_PASSWORD` (**not retryable**), `NODE_AMBIGUOUS` (index out of range for matches; **not retryable**), `GLOBAL_ACTION_FAILED`, `NO_ACTIVITY_FOR_INTENT` (**not retryable**), `APP_LAUNCH_FAILED`, `APP_NOT_INSTALLED` (**not retryable**), `APP_WINDOW_TIMEOUT`.

### TRANSIENT
`STEP_TIMEOUT`, `MACRO_TIMEOUT` (**not retryable** — terminal), `IO_ERROR`, `FILE_UNREADABLE`, `QUEUE_TIMEOUT` (**not retryable**), `LOCK_CONTENTION`.

### POLICY
`RATE_LIMITED`, `QUEUE_FULL`, `MACRO_DISABLED`, `CONCURRENT_SELF_NOT_ALLOWED`, `STOPPED_BY_MACRO`, `MISSED` (schedule skipped), `DUPLICATE_EXACT`, `DUPLICATE_SEMANTIC` (warning-level; import proceeds).

### DATA
`DB_ERROR`, `DB_CORRUPT`, `SECURE_VALUE_UNAVAILABLE`, `KEYSTORE_UNAVAILABLE`, `KEY_INVALIDATED`, `APK_PARSE_FAILED`, `CHECKSUM_MISMATCH`, `MACRO_NOT_FOUND`, `SCHEDULE_NOT_FOUND`, `EXECUTION_NOT_FOUND`.

### INTERNAL
`UNEXPECTED`, `ACTION_NOT_REGISTERED`, `INVARIANT_VIOLATION`.

### CANCELLED
`USER`, `PROCESS_DEATH`, `REBOOT`, `SUPERSEDED` (a new revision of the macro replaced a queued run when `replaceQueuedOnEdit` is on).

## 3. Mapping to execution outcomes

| Category of the final step error | Execution state | Notes |
|---|---|---|
| VALIDATION (pre-run) | REJECTED | never starts |
| PERMISSION | FAILED (or BLOCKED for `A11Y_SERVICE_NOT_ENABLED` when the user can fix it in place) | UI offers the fix |
| PRECONDITION on a UI step | BLOCKED | resumable |
| TARGET_UI / TRANSIENT after retries | per `onFailure` | |
| POLICY | FAILED (or REJECTED pre-run) | never retried |
| DATA / INTERNAL | FAILED | logged with detail in debug |
| CANCELLED | CANCELLED / INTERRUPTED | |

## 4. User-facing message policy

- One string resource per code (`error_<code>` in `core:common` res), written for non-technical users, ≤ 120 chars, with an optional action label (`error_<code>_action`) that maps to a navigation target (Permission center, Editor step, Help anchor).
- Messages never include package names of *other* apps' internal identifiers unless the user typed them (macro-authored values are fine to show).
- Snackbars for transient/informational, dialogs for anything that requires a decision, inline field errors for VALIDATION in the editor.

## 5. Logging levels for errors

| Category | Level | Includes stack trace |
|---|---|---|
| VALIDATION, POLICY, CANCELLED | INFO | no |
| PERMISSION, PRECONDITION, TARGET_UI, TRANSIENT | WARN | no |
| DATA, INTERNAL | ERROR | debug builds only |
