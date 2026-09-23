package com.macroandroid.automation.model

import com.macroandroid.core.common.error.ErrorCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
enum class Backoff { FIXED, EXPONENTIAL }

@Serializable
data class RetryPolicy(
    /** 1..5; 1 means no retry. */
    val maxAttempts: Int = 1,
    val backoff: Backoff = Backoff.FIXED,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val retryOn: Set<ErrorCategory> = setOf(ErrorCategory.TRANSIENT, ErrorCategory.TARGET_UI),
) {
    /** Delay before attempt number [attempt] (2-based: first retry is attempt 2). */
    fun delayBefore(attempt: Int): Duration {
        require(attempt >= 2) { "attempt must be >= 2" }
        return when (backoff) {
            Backoff.FIXED -> initialDelay
            Backoff.EXPONENTIAL -> {
                val factor = 1L shl (attempt - 2).coerceAtMost(MAX_SHIFT)
                (initialDelay * factor.toInt()).coerceAtMost(maxDelay)
            }
        }.coerceAtMost(maxDelay)
    }

    private companion object {
        const val MAX_SHIFT = 10
    }
}

@Serializable
data class ExecutionPolicy(
    val totalTimeout: Duration = 30.minutes,
    val defaultStepTimeout: Duration = 30.seconds,
    val defaultRetry: RetryPolicy = RetryPolicy(),
    val lockAcquireTimeout: Duration = 60.seconds,
    val blockedTimeout: Duration = 10.minutes,
    val allowConcurrentSelf: Boolean = false,
    val requiresConfirmationBeforeRun: Boolean = false,
)

@Serializable
sealed interface FailureBehavior {
    @Serializable
    @SerialName("abort")
    data object AbortMacro : FailureBehavior

    @Serializable
    @SerialName("continue")
    data object Continue : FailureBehavior

    @Serializable
    @SerialName("jump")
    data class JumpToLabel(val label: String) : FailureBehavior
}

@Serializable
data class MacroStep(
    val id: StepId,
    val label: String? = null,
    val enabled: Boolean = true,
    val action: ActionParameters,
    val timeout: Duration? = null,
    val retry: RetryPolicy? = null,
    val onFailure: FailureBehavior = FailureBehavior.AbortMacro,
    /** Reserved; must be `false` in v1. */
    val continueOnCancel: Boolean = false,
)

@Serializable
data class Macro(
    val id: MacroId,
    val revision: Int = 1,
    val name: String,
    val description: String = "",
    val profile: String = DEFAULT_PROFILE,
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true,
    val executionPolicy: ExecutionPolicy = ExecutionPolicy(),
    val variables: Map<String, VariableValue> = emptyMap(),
    val steps: List<MacroStep>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Any step (recursively) needs the accessibility service. */
    val requiresAccessibility: Boolean get() = allSteps().any { it.action.needsAccessibility }

    val concurrencyClass: ConcurrencyClass
        get() = steps.fold(ConcurrencyClass.BACKGROUND_SAFE) { acc, s -> acc max s.action.concurrencyClass }

    /** Packages this macro will bring to the foreground or inspect. */
    val targetPackages: Set<String>
        get() = buildSet {
            allSteps().forEach { step ->
                when (val a = step.action) {
                    is ActionParameters.LaunchApp -> add(a.packageName)
                    is ActionParameters.OpenUrl -> a.preferPackage?.let(::add)
                    is ActionParameters.ClickNode -> a.selector.packageName?.let(::add)
                    is ActionParameters.WaitForNode -> a.selector.packageName?.let(::add)
                    is ActionParameters.ScrollNode -> a.selector?.packageName?.let(::add)
                    is ActionParameters.EnterText -> a.selector?.packageName?.let(::add)
                    else -> Unit
                }
            }
        }

    /** Number of leaf steps when constant repeats are multiplied out (validator `STEP_LIMIT`). */
    val expandedLeafCount: Int get() = expandedLeaves(steps)

    /** Depth of the deepest container nesting (top level = 0). */
    val nestingDepth: Int get() = depthOf(steps, 0)

    /** Depth-first list of every step including nested ones. */
    fun allSteps(): List<MacroStep> = buildList { collect(steps, this) }

    companion object {
        const val DEFAULT_PROFILE = "General"

        private fun collect(steps: List<MacroStep>, into: MutableList<MacroStep>) {
            steps.forEach { s ->
                into += s
                s.action.childGroups().forEach { collect(it, into) }
            }
        }

        private fun expandedLeaves(steps: List<MacroStep>): Int = steps.sumOf { s ->
            when (val a = s.action) {
                is ActionParameters.If -> maxOf(expandedLeaves(a.then), expandedLeaves(a.`else`), 1)
                is ActionParameters.Repeat -> expandedLeaves(a.body) * a.iterationBound.coerceAtLeast(1)
                is ActionParameters.Parallel -> expandedLeaves(a.children)
                else -> 1
            }
        }

        private fun depthOf(steps: List<MacroStep>, current: Int): Int = steps.maxOfOrNull { s ->
            val groups = s.action.childGroups()
            if (groups.isEmpty()) current else groups.maxOf { depthOf(it, current + 1) }
        } ?: current
    }
}
