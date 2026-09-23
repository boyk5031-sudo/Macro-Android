package com.macroandroid.automation.engine

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.port.AccessibilityGateway
import com.macroandroid.automation.port.AppLauncher
import com.macroandroid.automation.port.ExecutionLifecycleHooks
import com.macroandroid.automation.port.ExecutionStore
import com.macroandroid.automation.port.MacroSource
import com.macroandroid.automation.port.NotificationPort
import com.macroandroid.automation.port.PreconditionGate
import com.macroandroid.automation.port.SecureValueResolver
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.logging.LogLevel
import kotlin.time.Clock
import kotlin.time.Duration

/** Outcome of one leaf action attempt. Actions never throw except for cancellation. */
sealed interface ActionResult {
    data class Success(val summary: String? = null) : ActionResult
    data class Failure(val error: AppError) : ActionResult
    data class Stop(val success: Boolean, val message: String?) : ActionResult
}

/** All platform ports bundled for injection into the executor. */
data class EnginePorts(
    val macroSource: MacroSource,
    val store: ExecutionStore,
    val secureValues: SecureValueResolver,
    val gate: PreconditionGate,
    val accessibility: AccessibilityGateway,
    val launcher: AppLauncher,
    val notifications: NotificationPort,
    val hooks: ExecutionLifecycleHooks = ExecutionLifecycleHooks.Noop,
)

/** Everything a leaf action needs; created per attempt by the runner. */
class ActionContext(
    val executionId: ExecutionId,
    val macro: Macro,
    val step: MacroStep,
    val stepIndex: Int,
    val attempt: Int,
    val variables: VariableScope,
    val secureValues: Map<String, String>,
    val ports: EnginePorts,
    val clock: Clock,
    /** Effective timeout of this attempt; long-polling actions stop slightly before it. */
    val timeout: Duration,
    private val logger: (LogLevel, String) -> Unit,
) {
    val action: ActionParameters get() = step.action
    fun log(level: LogLevel, message: String) = logger(level, message)
    fun info(message: String) = logger(LogLevel.INFO, message)
    fun warn(message: String) = logger(LogLevel.WARN, message)
}
