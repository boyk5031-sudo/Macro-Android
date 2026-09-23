package com.macroandroid.automation.engine

import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.StepId
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.ErrorCategory
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Execution states (docs/phase-1/07-execution-state-machine.md §1). */
enum class ExecutionState(val isTerminal: Boolean) {
    QUEUED(false),
    PREPARING(false),
    RUNNING(false),
    PAUSED(false),
    BLOCKED(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    REJECTED(true),
    SKIPPED(true),
    INTERRUPTED(true),
    ;

    val isActive: Boolean get() = !isTerminal

    /** Legal transitions; the executor asserts these so a bug never produces an impossible record. */
    fun canTransitionTo(next: ExecutionState): Boolean = when (this) {
        QUEUED -> next in setOf(PREPARING, REJECTED, CANCELLED, INTERRUPTED)
        PREPARING -> next in setOf(RUNNING, BLOCKED, FAILED, CANCELLED, INTERRUPTED, SKIPPED)
        RUNNING -> next in setOf(RUNNING, PAUSED, BLOCKED, COMPLETED, FAILED, CANCELLED, INTERRUPTED, SKIPPED)
        PAUSED -> next in setOf(RUNNING, BLOCKED, CANCELLED, INTERRUPTED)
        BLOCKED -> next in setOf(RUNNING, QUEUED, CANCELLED, INTERRUPTED)
        COMPLETED, FAILED, CANCELLED, REJECTED, SKIPPED, INTERRUPTED -> false
    }
}

enum class StepState { PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED, SKIPPED }

@Serializable
sealed interface ExecutionOrigin {
    @Serializable
    @SerialName("manual")
    data object Manual : ExecutionOrigin

    @Serializable
    @SerialName("shortcut")
    data object Shortcut : ExecutionOrigin

    @Serializable
    @SerialName("schedule")
    data class Schedule(val scheduleId: ScheduleId, val plannedAt: Instant? = null, val late: Boolean = false) :
        ExecutionOrigin

    @Serializable
    @SerialName("stepTest")
    data class StepTest(val stepId: StepId) : ExecutionOrigin
}

/** A request to run a macro. [runRequestId] de-duplicates retries of the same trigger. */
data class RunRequest(
    val macroId: MacroId,
    val origin: ExecutionOrigin,
    val runRequestId: String,
    /** When true (scheduled with SKIP policy and late), the run terminates as SKIPPED before step 0. */
    val skipBecauseMissed: Boolean = false,
    val executionId: ExecutionId = ExecutionId.random(),
)

/** Why an execution is BLOCKED and what the user can do about it. */
data class BlockedReason(val code: ErrorCode, val detail: String? = null)

/** Snapshot of an execution's durable fields; mirrors the Room row. */
data class ExecutionRecord(
    val id: ExecutionId,
    val macroId: MacroId,
    val macroRevision: Int,
    val macroName: String,
    val origin: ExecutionOrigin,
    val runRequestId: String,
    val state: ExecutionState,
    val ownerToken: String?,
    val queuedAt: Instant,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val pausedAt: Instant? = null,
    val blockedAt: Instant? = null,
    val blockedReason: BlockedReason? = null,
    val currentStepIndex: Int = 0,
    val currentStepId: StepId? = null,
    val currentAttempt: Int = 0,
    val completedSteps: Int = 0,
    val totalStaticSteps: Int = 0,
    val retryTotal: Int = 0,
    val errorCode: ErrorCode? = null,
    val errorCategory: ErrorCategory? = null,
    val errorDetail: String? = null,
) {
    fun transition(to: ExecutionState): ExecutionRecord {
        check(state.canTransitionTo(to)) { "Illegal transition $state -> $to for $id" }
        return copy(state = to)
    }
}

data class StepAttemptRecord(
    val executionId: ExecutionId,
    val stepId: StepId,
    val stepIndex: Int,
    val attempt: Int,
    val actionType: String,
    val state: StepState,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val errorCode: ErrorCode? = null,
    val errorCategory: ErrorCategory? = null,
    /** Redacted one-line summary (never secrets). */
    val outputSummary: String? = null,
)

data class ExecutionLogEntry(
    val executionId: ExecutionId,
    val at: Instant,
    val level: com.macroandroid.core.common.logging.LogLevel,
    val stepIndex: Int?,
    val attempt: Int?,
    val message: String,
    val errorCode: ErrorCode? = null,
)

/** Final outcome delivered to the caller of [MacroExecutor.run]. */
data class ExecutionOutcome(
    val id: ExecutionId,
    val state: ExecutionState,
    val error: AppError? = null,
    val completedSteps: Int,
    val retryTotal: Int,
)

/** Events for UI animations/snackbars; durable state comes from persistence (doc 07 §6). */
sealed interface ExecutionEvent {
    val executionId: ExecutionId

    data class StateChanged(
        override val executionId: ExecutionId,
        val from: ExecutionState,
        val to: ExecutionState,
        val reason: ErrorCode? = null,
    ) : ExecutionEvent

    data class StepStarted(override val executionId: ExecutionId, val stepIndex: Int, val stepId: StepId, val attempt: Int) :
        ExecutionEvent

    data class StepFinished(
        override val executionId: ExecutionId,
        val stepIndex: Int,
        val stepId: StepId,
        val attempt: Int,
        val state: StepState,
        val error: AppError? = null,
    ) : ExecutionEvent

    data class Log(override val executionId: ExecutionId, val entry: ExecutionLogEntry) : ExecutionEvent

    data class Progress(
        override val executionId: ExecutionId,
        val completed: Int,
        val total: Int,
        val indeterminate: Boolean,
    ) : ExecutionEvent
}
