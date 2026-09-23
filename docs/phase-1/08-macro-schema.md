# 08 — Macro Schema (v1)

The schema is defined once in Kotlin (`automation:engine`) with `kotlinx.serialization`, and its JSON projection is the import/export wire format. `docs/schema/macro-v1.schema.json` is the JSON-Schema rendering used by import fuzz/validation tests to cross-check the serializer.

## 1. Versioning

- `schemaVersion: Int` at the document root. Current = **1**.
- The Kotlin model is always the *current* version. Import: parse the root envelope only (`schemaVersion` + raw `JsonObject`), run `MacroMigrations.migrate(fromVersion, json): JsonObject` step-by-step (`1→2`, `2→3`, …), then decode with the current serializer. Export always writes the current version.
- A `MacroVersion` counter also exists **per macro** (`macro.revision: Int`, incremented on each save) so executions can record which revision ran (`Execution.macroRevision`).
- Compatibility rules: adding an optional field with a default is a non-breaking change within the same `schemaVersion`; anything else bumps it and adds a migration + test.

## 2. Kotlin model (authoritative)

```kotlin
package com.macroandroid.automation.model

@Serializable
data class MacroDocument(               // export envelope
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Instant,            // kotlinx.datetime
    val appVersionCode: Int,
    val macros: List<Macro>,
    val schedules: List<ScheduleSpec> = emptyList(),
) { companion object { const val CURRENT_SCHEMA_VERSION = 1 } }

@Serializable
data class Macro(
    val id: MacroId,                    // value class over String UUID
    val revision: Int = 1,
    val name: String,                   // 1..80
    val description: String = "",       // 0..500
    val profile: String = "General",    // 1..40
    val tags: List<String> = emptyList(), // ≤10, each 1..30, lowercase-normalised
    val enabled: Boolean = true,
    val executionPolicy: ExecutionPolicy = ExecutionPolicy(),
    val variables: Map<String, VariableValue> = emptyMap(), // initial variables, ≤32
    val steps: List<MacroStep>,         // 1..100 top-level; ≤200 expanded leaves
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class MacroStep(
    val id: StepId,                     // UUID, stable across edits (used for logs, secure values)
    val label: String? = null,          // optional user label; also JUMP target (unique per macro)
    val enabled: Boolean = true,
    val action: ActionParameters,       // sealed, polymorphic by "type"
    val timeout: Duration? = null,      // null → ActionType.defaultTimeout; max 10 min
    val retry: RetryPolicy? = null,     // null → macro policy default
    val onFailure: FailureBehavior = FailureBehavior.AbortMacro,
    val continueOnCancel: Boolean = false, // reserved, must be false in v1 (validator)
)

@Serializable
sealed interface FailureBehavior {
    @Serializable @SerialName("abort")    data object AbortMacro : FailureBehavior
    @Serializable @SerialName("continue") data object Continue : FailureBehavior
    @Serializable @SerialName("jump")     data class JumpToLabel(val label: String) : FailureBehavior
}

@Serializable
data class ExecutionPolicy(
    val totalTimeout: Duration = 30.minutes,       // ≤ 2 h
    val defaultStepTimeout: Duration = 30.seconds, // ≤ 10 min
    val defaultRetry: RetryPolicy = RetryPolicy(),
    val lockAcquireTimeout: Duration = 60.seconds,
    val blockedTimeout: Duration = 10.minutes,
    val allowConcurrentSelf: Boolean = false,
    val requiresConfirmationBeforeRun: Boolean = false,
)

@Serializable
data class RetryPolicy(
    val maxAttempts: Int = 1,                       // 1..5 (1 = no retry)
    val backoff: Backoff = Backoff.FIXED,           // FIXED | EXPONENTIAL
    val initialDelay: Duration = 1.seconds,         // ≤ 60 s
    val maxDelay: Duration = 30.seconds,            // ≤ 5 min
    val retryOn: Set<ErrorCategory> = setOf(ErrorCategory.TRANSIENT, ErrorCategory.TARGET_UI),
)

@Serializable enum class Backoff { FIXED, EXPONENTIAL }

@Serializable
sealed interface VariableValue {
    @Serializable @SerialName("str")  data class Str(val value: String) : VariableValue
    @Serializable @SerialName("int")  data class Int64(val value: Long) : VariableValue
    @Serializable @SerialName("bool") data class Bool(val value: Boolean) : VariableValue
    @Serializable @SerialName("secure") data class Secure(val ref: SecureValueRef) : VariableValue
}

/** Reference to an encrypted value stored outside the macro JSON. */
@Serializable data class SecureValueRef(val id: String, val redacted: Boolean = false)

/** Text that may be a literal, a variable reference, or a secure reference. */
@Serializable
sealed interface TextValue {
    @Serializable @SerialName("literal") data class Literal(val text: String) : TextValue        // ≤ 4 000 chars
    @Serializable @SerialName("var")     data class Var(val name: String) : TextValue
    @Serializable @SerialName("secure")  data class Secure(val ref: SecureValueRef) : TextValue
    /** Literal with {{var}} placeholders; only non-secure vars may be interpolated. */
    @Serializable @SerialName("template") data class Template(val template: String) : TextValue
}
```

### 2.1 Actions (`ActionParameters`, discriminator `"type"`)

| `type` | Class | Parameters (validated ranges) | `ConcurrencyClass` | Needs a11y | Default timeout |
|---|---|---|---|---|---|
| `launchApp` | `LaunchApp` | `packageName: String` (valid package regex), `waitForWindow: Boolean = true`, `windowWait: Duration = 5 s` | UI (FOREGROUND) | no (needs foreground gate) | 15 s |
| `openUrl` | `OpenUrl` | `url: TextValue` (resolved must be `http`/`https`, ≤ 2 048 chars), `preferPackage: String? = null` | UI (FOREGROUND) | no | 15 s |
| `wait` | `Wait` | `duration: Duration` (10 ms … 10 min) | BACKGROUND_SAFE | no | duration + 1 s |
| `globalAction` | `GlobalAction` | `action: GlobalActionKind` ∈ {BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS} | UI | **yes** | 5 s |
| `clickNode` | `ClickNode` | `selector: NodeSelector`, `longClick: Boolean = false`, `requireVisible: Boolean = true` (must stay true in v1) | UI | **yes** | 10 s |
| `scrollNode` | `ScrollNode` | `selector: NodeSelector?` (null = first scrollable in window), `direction: FORWARD\|BACKWARD`, `times: Int` (1..20) | UI | **yes** | 10 s |
| `enterText` | `EnterText` | `selector: NodeSelector?` (null = focused editable), `text: TextValue`, `sensitive: Boolean = false` (must be true when `text` is `Secure`), `append: Boolean = false` | UI | **yes** | 10 s |
| `waitForNode` | `WaitForNode` | `selector: NodeSelector`, `state: PRESENT\|ABSENT` | UI | **yes** | 15 s |
| `sendNotification` | `SendNotification` | `title: TextValue` (≤ 80), `text: TextValue` (≤ 500), `tapOpensMacro: Boolean = true` | BACKGROUND_SAFE | no | 5 s |
| `setVariable` | `SetVariable` | `name: String` (`[a-zA-Z_][a-zA-Z0-9_]{0,31}`), `value: VariableValue \| Expression` where `Expression` ∈ {`Increment(by)`, `Concat(parts: List<TextValue>)`, `Now()`, `NodeText(selector)`} — `NodeText` makes the step UI-class and requires a11y; the result is stored **redacted** (`h:` hash) in logs | BACKGROUND_SAFE (or UI for NodeText) | only NodeText | 5 s |
| `if` | `If` | `condition: Condition`, `then: List<MacroStep>`, `else: List<MacroStep> = emptyList()` | max(children) | if any child | — |
| `repeat` | `Repeat` | `count: Int` (1..100) **or** `whileCondition: Condition` with `maxIterations: Int` (1..100), `body: List<MacroStep>`, `delayBetween: Duration = 0` | max(children) | if any child | — |
| `parallel` | `Parallel` | `children: List<MacroStep>` (2..8, all BACKGROUND_SAFE), `failFast: Boolean = true` | BACKGROUND_SAFE | no | — |
| `log` | `Log` | `level: INFO\|WARN`, `message: TextValue` (≤ 500) | BACKGROUND_SAFE | no | 1 s |
| `stop` | `Stop` | `success: Boolean = true`, `message: String? = null` | BACKGROUND_SAFE | no | 1 s |
| `screenshot` | — | **not in v1** (post-MVP; reserved type name rejected by v1 validator with `ACTION_NOT_SUPPORTED`) | | | |
| `gesture` | — | **not in v1** (reserved) | | | |

```kotlin
@Serializable
data class NodeSelector(
    val viewId: String? = null,          // "pkg:id/name" exact
    val text: String? = null,            // exact or contains per textMatch
    val contentDescription: String? = null,
    val className: String? = null,       // e.g. "android.widget.Button"
    val textMatch: TextMatch = TextMatch.EQUALS_IGNORE_CASE, // EQUALS | EQUALS_IGNORE_CASE | CONTAINS | REGEX(≤200 chars, compiled with timeout guard)
    val packageName: String? = null,     // restrict to window of this package
    val index: Int = 0,                  // nth match, 0..50
    val clickableAncestor: Boolean = true, // if match isn't clickable, walk up ≤ 5 ancestors
) // validator: at least one of viewId/text/contentDescription/className

@Serializable
sealed interface Condition {
    @Serializable @SerialName("varEquals")   data class VarEquals(val name: String, val value: VariableValue) : Condition
    @Serializable @SerialName("varCompare")  data class VarCompare(val name: String, val op: CompareOp, val value: Long) : Condition
    @Serializable @SerialName("varContains") data class VarContains(val name: String, val needle: TextValue) : Condition
    @Serializable @SerialName("nodeExists")  data class NodeExists(val selector: NodeSelector) : Condition   // UI, a11y
    @Serializable @SerialName("appInstalled") data class AppInstalled(val packageName: String) : Condition
    @Serializable @SerialName("not") data class Not(val inner: Condition) : Condition
    @Serializable @SerialName("all") data class All(val conditions: List<Condition>) : Condition // ≤ 8
    @Serializable @SerialName("any") data class Any(val conditions: List<Condition>) : Condition // ≤ 8
}
```

### 2.2 Derived properties (computed, not serialised)
- `Macro.requiresAccessibility`: any step (recursively) with `needsA11y`.
- `Macro.concurrencyClass`: max over steps.
- `Macro.targetPackages`: set of `LaunchApp.packageName`, `NodeSelector.packageName`, `OpenUrl.preferPackage`.
- `Macro.staticTimeBudget`: Σ over expanded leaves of `(timeout ?: default) × maxAttempts` + backoff, capped by `totalTimeout` — used for FGS type selection and preview.
- `Macro.expandedLeafCount`.

## 3. Limits (enforced by validator and by import)

| Limit | Value |
|---|---|
| Top-level steps | 100 |
| Expanded leaf steps (constant repeats multiplied) | 200 |
| Nesting depth (`If`/`Repeat`/`Parallel`) | 4 |
| Repeat count / maxIterations | 100 |
| Parallel children | 8 |
| Variables | 32; name ≤ 32 chars |
| Literal text | 4 000 chars; template 4 000; URL 2 048; regex 200 |
| Tags | 10 × 30 chars |
| Document size on import | 1 MiB; JSON depth 16; macros per document 50 |
| Total timeout | 2 h; step timeout 10 min; wait 10 min |

## 4. JSON wire format example

```json
{
  "schemaVersion": 1,
  "exportedAt": "2026-09-23T10:15:30Z",
  "appVersionCode": 1,
  "macros": [
    {
      "id": "6f1d3a2e-2c7b-4a4e-9a55-2f0c1f4f5a10",
      "revision": 3,
      "name": "Open Wi-Fi settings",
      "description": "Launches Settings and taps Wi-Fi.",
      "profile": "General",
      "tags": ["settings"],
      "enabled": true,
      "executionPolicy": { "totalTimeout": "PT5M" },
      "variables": {},
      "steps": [
        { "id": "0a…", "action": { "type": "launchApp", "packageName": "com.android.settings" } },
        { "id": "0b…", "action": { "type": "waitForNode",
            "selector": { "text": "Network & internet", "textMatch": "CONTAINS" }, "state": "PRESENT" },
          "timeout": "PT8S", "retry": { "maxAttempts": 2 } },
        { "id": "0c…", "action": { "type": "clickNode",
            "selector": { "text": "Network & internet", "textMatch": "CONTAINS" } },
          "onFailure": { "type": "jump", "label": "fallback" } },
        { "id": "0d…", "action": { "type": "stop", "success": true } },
        { "id": "0e…", "label": "fallback",
          "action": { "type": "log", "level": "WARN",
                      "message": { "type": "literal", "text": "Menu not found" } } }
      ],
      "createdAt": "2026-09-20T08:00:00Z",
      "updatedAt": "2026-09-23T10:00:00Z"
    }
  ],
  "schedules": []
}
```

Durations are ISO-8601 (`kotlin.time.Duration` default serializer). Secure values appear as `{"type":"secure","ref":{"id":"…","redacted":true}}` unless the user opted to include them, in which case the exporter emits `{"type":"literal","text":"…"}` **and** sets `sensitive: true` on the step so re-import re-encrypts it.

## 5. `Json` configuration (import and export)

```kotlin
val MacroJson = Json {
    ignoreUnknownKeys = false     // hostile/typo'd input is rejected, not silently accepted
    isLenient = false
    allowStructuredMapKeys = false
    encodeDefaults = false        // compact exports; defaults documented here
    explicitNulls = false
    classDiscriminator = "type"
    prettyPrint = true            // export only; import accepts either
    serializersModule = macroSerializersModule // sealed hierarchies are closed → no open polymorphism
}
```
Import pipeline: size check → UTF-8 decode → depth pre-scan (streaming, cheap) → parse envelope → migrate → decode → `MacroValidator` → user review (collisions, redacted values) → persist disabled.

## 6. Validation rules (`MacroValidator`, pure Kotlin)

Severity **E** blocks save/import/run; **W** is shown.

| Code | Sev | Rule |
|---|---|---|
| `NAME_INVALID` | E | name blank, > 80, or contains control chars |
| `NAME_DUPLICATE` | E | another macro in the same profile has the same normalised name |
| `NO_STEPS` | E | steps empty or all disabled |
| `STEP_LIMIT` | E | > 100 top-level or > 200 expanded |
| `NESTING_LIMIT` | E | depth > 4 |
| `REPEAT_BOUNDS` | E | count/maxIterations outside 1..100 |
| `PARALLEL_CONTAINS_UI_STEP` | E | any child not BACKGROUND_SAFE |
| `PARALLEL_SIZE` | E | children < 2 or > 8 |
| `TIMEOUT_RANGE` | E | any timeout outside allowed range |
| `RETRY_RANGE` | E | maxAttempts/backoff delays outside range |
| `PACKAGE_NAME_INVALID` | E | not matching `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$` |
| `URL_SCHEME_NOT_ALLOWED` | E | literal URL scheme not http/https; template/var URLs get **W** `URL_RUNTIME_CHECK` and are re-checked at run time |
| `SELECTOR_EMPTY` | E | NodeSelector without any of viewId/text/contentDescription/className |
| `REGEX_INVALID` | E | `TextMatch.REGEX` pattern fails to compile or > 200 chars |
| `LABEL_DUPLICATE` | E | two steps share a label |
| `JUMP_TARGET_MISSING` | E | `JumpToLabel` label not found |
| `JUMP_BACKWARD` | E | label index ≤ current index (only forward jumps) |
| `VARIABLE_NAME_INVALID` | E | bad identifier |
| `VARIABLE_UNDEFINED` | W | `Var`/`Template` references a name never set earlier or in initial variables (W because a jump/if may set it) |
| `SECURE_IN_TEMPLATE` | E | a `Template` references a secure variable |
| `SENSITIVE_FLAG_REQUIRED` | E | `EnterText.text` is `Secure` but `sensitive=false` |
| `SECURE_VALUE_REDACTED` | E | a `SecureValueRef.redacted == true` (imported without value) — user must fill in |
| `SECURE_VALUE_UNAVAILABLE` | E | referenced id not decryptable |
| `ACTION_NOT_SUPPORTED` | E | reserved/unknown action in this app version |
| `A11Y_CONSENT_MISSING` | W | macro requires a11y and consent not granted (save allowed, run blocked) |
| `A11Y_STEP_WITHOUT_LAUNCH` | W | first UI step is an a11y step with no preceding `LaunchApp`/`WaitForNode` — likely to act on the wrong window |
| `TARGET_APP_NOT_VISIBLE` | W | `LaunchApp.packageName` not resolvable on this device |
| `REQUIRES_DEVICE_IDLE_WITH_UI` | E | (schedule-level) `requiresDeviceIdle` on a macro with UI steps |
| `CONTINUE_ON_CANCEL_RESERVED` | E | `continueOnCancel=true` in v1 |

## 7. Migration contract

```kotlin
interface MacroMigration { val from: Int; val to: Int; fun migrate(document: JsonObject): JsonObject }
object MacroMigrations {
    private val all: List<MacroMigration> = listOf(/* V1ToV2 … added when needed */)
    fun migrate(fromVersion: Int, doc: JsonObject): JsonObject  // applies chain or throws SchemaTooNew/NoMigrationPath
}
```
Tests (Phase 5): a fixture file per historical schema version under `automation/engine/src/test/resources/schema/vN/*.json` must import and re-export to the current version byte-identically (after normalisation). Adding v2 later requires keeping the v1 fixtures.
