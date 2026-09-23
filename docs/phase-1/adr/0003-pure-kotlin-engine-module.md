# ADR-0003 — The automation engine is a pure-Kotlin module; Android bindings live in `automation:android`

- Status: Accepted

## Context
The riskiest logic in the product is concurrency: timeouts, retries, cancellation propagation, the single UI lock, queueing, and process-death persistence. These are best tested with `kotlinx-coroutines-test` virtual time on the JVM, in milliseconds, without an emulator.

## Decision
`automation:engine` depends only on kotlinx (coroutines, serialization, datetime) and `core:common`. It defines:
- the macro model and validator,
- `MacroExecutor` (state machine + scheduler + locks),
- `Action` interfaces: `interface Action<P : ActionParameters> { suspend fun execute(params: P, ctx: ActionContext): ActionResult }`,
- `ActionContext` exposing `variables`, `logger`, `clock`, `gates` (`suspend fun requireUiPreconditions()`), `accessibility: AccessibilityPort?`, `apps: AppLauncherPort`, `notifications: NotificationPort`, `secureValues: SecureValuePort`,
- `ExecutionStore` (persistence port) and `ExecutionEvent`s.

`automation:android` implements the ports with real Android APIs (`AccessibilityGateway` over `AccessibilityService`, `PackageManager`, `NotificationManagerCompat`, Room-backed `ExecutionStore`). `core:testing` provides fakes for every port.

## Consequences
- Engine tests run in the unit-test task; instrumented tests are reserved for the Android bindings.
- The extra module is justified by test speed and by making "no Android API inside the engine" mechanically enforceable (the module has no Android plugin).
