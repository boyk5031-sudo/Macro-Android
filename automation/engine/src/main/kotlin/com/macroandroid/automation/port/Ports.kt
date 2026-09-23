package com.macroandroid.automation.port

import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.GlobalActionKind
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.ScrollDirection
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlin.time.Duration
import kotlin.time.Instant

/*
 * Ports = the only things the pure-Kotlin engine needs from the platform.
 * automation:android implements them; tests use fakes from core:testing.
 */

/** Read-only access to macros for the executor's snapshot at PREPARING. */
interface MacroSource {
    suspend fun getMacro(id: MacroId): Macro?
}

/** Durable persistence of execution records (doc 07 §4 persistence points). */
interface ExecutionStore {
    suspend fun insert(record: ExecutionRecord)
    suspend fun update(record: ExecutionRecord)
    suspend fun findByRunRequestId(runRequestId: String, since: Instant): ExecutionRecord?
    suspend fun insertStepAttempt(attempt: StepAttemptRecord)
    suspend fun updateStepAttempt(attempt: StepAttemptRecord)
    suspend fun appendLogs(entries: List<ExecutionLogEntry>)
    suspend fun activeExecutions(): List<ExecutionRecord>
}

/** Resolves encrypted values (`SecureValueRef.id`) to plaintext at PREPARING time only. */
interface SecureValueResolver {
    suspend fun resolve(id: String): AppResult<String>
}

/** Result of a precondition check for a UI segment. */
sealed interface GateResult {
    data object Pass : GateResult
    data class Blocked(val code: ErrorCode, val detail: String? = null) : GateResult
}

/**
 * Preconditions for UI steps: screen interactive, not keyguard-locked, accessibility service connected
 * when needed, consent granted. Implemented on Android; fakes toggle these in tests.
 */
interface PreconditionGate {
    suspend fun checkUiSegment(needsAccessibility: Boolean): GateResult

    /** Suspends until the gate passes again or [timeout] elapses (BLOCKED wait). Returns final state. */
    suspend fun awaitPass(needsAccessibility: Boolean, timeout: Duration): GateResult
}

/** A node handle abstracted from AccessibilityNodeInfo. */
data class UiNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val packageName: String?,
    val isVisible: Boolean,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isPassword: Boolean,
    val isFocused: Boolean,
)

/** Accessibility gateway. Only the closed set of operations below exists; nothing generic. */
interface AccessibilityGateway {
    val isConnected: Boolean
    suspend fun activePackage(): String?
    suspend fun findNodes(selector: NodeSelector): List<UiNode>
    suspend fun focusedEditable(): UiNode?
    suspend fun firstScrollable(packageName: String?): UiNode?
    suspend fun click(node: UiNode, longClick: Boolean): AppResult<Unit>
    suspend fun setText(node: UiNode, text: String, append: Boolean): AppResult<Unit>
    suspend fun scroll(node: UiNode, direction: ScrollDirection): AppResult<Unit>
    suspend fun performGlobalAction(action: GlobalActionKind): AppResult<Unit>
    suspend fun nodeText(node: UiNode): String?
}

/** App launching and URL opening (intent-based; needs the foreground gate, not accessibility). */
interface AppLauncher {
    suspend fun isInstalled(packageName: String): Boolean
    suspend fun launch(packageName: String): AppResult<Unit>
    suspend fun openUrl(url: String, preferPackage: String?): AppResult<Unit>

    /** Waits until [packageName] owns the active window or [timeout] passes. */
    suspend fun awaitWindow(packageName: String, timeout: Duration): Boolean
}

interface NotificationPort {
    suspend fun postMacroNotification(executionId: ExecutionId, title: String, text: String, tapOpensExecution: Boolean):
        AppResult<Unit>
}

/** Execution-wide hooks the platform layer can observe (FGS start/stop, shortcuts refresh). */
interface ExecutionLifecycleHooks {
    suspend fun onRunningStarted(record: ExecutionRecord, macro: Macro) = Unit
    suspend fun onBlocked(record: ExecutionRecord, macro: Macro) = Unit
    suspend fun onTerminal(record: ExecutionRecord, macro: Macro?) = Unit

    object Noop : ExecutionLifecycleHooks
}

/** Rate limits and queue capacity policy (POLICY errors). */
data class EngineConfig(
    val maxConcurrentExecutions: Int = 3,
    val queueCapacity: Int = 20,
    val queueTimeout: Duration = kotlin.time.Duration.parse("PT30M"),
    val runRequestDedupWindow: Duration = kotlin.time.Duration.parse("PT24H"),
    /** Max executions started per macro per rolling minute. */
    val perMacroStartsPerMinute: Int = 6,
    /** Max executions started globally per rolling minute. */
    val globalStartsPerMinute: Int = 20,
    /** A run whose static time budget exceeds this asks the platform for a foreground service. */
    val foregroundServiceThreshold: Duration = kotlin.time.Duration.parse("PT30S"),
)
