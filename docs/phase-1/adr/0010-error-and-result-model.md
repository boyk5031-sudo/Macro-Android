# ADR-0010 — Typed `AppError`/`AppResult` across all layers; no exceptions for expected failures

- Status: Accepted

## Context
The engine, repositories, workers, and UI all need to agree on what went wrong, whether to retry, and what to show. Exceptions carry platform messages that may echo user content and are hard to make exhaustive.

## Decision
- `AppResult<T>` (Ok/Err) is the return type of every repository and use-case operation that can fail for a *expected* reason; `ActionResult` for engine actions.
- `AppError(code, category, retryable, userMessageRes, detail?, cause?)` with the closed `ErrorCode` enum from `09-error-taxonomy.md`.
- `CancellationException` is always rethrown, never wrapped.
- Unexpected exceptions are converted at module boundaries to `INTERNAL/UNEXPECTED` with the cause attached (debug) and a stack logged only in debug builds.
- Compose UI receives `UiError(messageRes, actionRes?, target?)`, never `Throwable`.

## Consequences
- Retry policies and BLOCKED/FAILED decisions are data-driven and unit-testable.
- Adding a code means adding a string resource (a lint-like unit test asserts every `ErrorCode` has one).
