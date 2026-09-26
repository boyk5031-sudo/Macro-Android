package com.macroandroid.automation.engine

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.ConcurrencyClass
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.FailureBehavior
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.automation.model.typeName
import com.macroandroid.automation.port.EngineConfig
import com.macroandroid.automation.port.GateResult
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCategory
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Handle returned by [MacroExecutor.enqueue]; the run itself proceeds in the executor scope. */
class ExecutionHandle internal constructor(
    val id: ExecutionId,
    internal val job: Job,
    private val outcome: Deferred<ExecutionOutcome>,
    private val controls: ExecutionControls,
) {
    suspend fun await(): ExecutionOutcome = outcome.await()
    fun cancel() = controls.cancel()
    fun pause() = controls.pause()
    fun resume() = controls.resume()
}

internal class ExecutionControls {
    val pauseRequested = MutableStateFlow(false)
    val resumeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val cancelRequested = MutableStateFlow(false)
    fun pause() = pauseRequested.update { true }
    fun resume() {
        pauseRequested.update { false }
        resumeSignal.tryEmit(Unit)
    }
    fun cancel() = cancelRequested.update { true }
}

/**
 * The macro execution engine (docs/phase-1/07-execution-state-machine.md).
 *
 * Pure Kotlin: everything platform-specific arrives through [EnginePorts]. One instance per process;
 * concurrency is bounded by [EngineConfig.maxConcurrentExecutions] and a single UI mutex.
 */
@OptIn(ExperimentalUuidApi::class)
class MacroExecutor(
    private val ports: EnginePorts,
    private val config: EngineConfig = EngineConfig(),
    private val clock: Clock = Clock.System,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    parentJob: Job? = null,
    /** Identifies this process instance; written into every record it owns (doc 07 §1.2). */
    val ownerToken: String = Uuid.random().toString(),
) {
    private val scope = CoroutineScope(SupervisorJob(parentJob) + dispatcher + CoroutineName("macro-executor"))
    private val slots = Semaphore(config.maxConcurrentExecutions)
    private val uiLock = Mutex()
    private val stateMutex = Mutex()
    private val active = java.util.concurrent.ConcurrentHashMap<ExecutionId, ActiveRun>()
    private val recentStarts = ArrayDeque<Pair<Instant, MacroId>>()

    private val _events = MutableSharedFlow<ExecutionEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ExecutionEvent> = _events

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private class ActiveRun(val handle: ExecutionHandle, val macroId: MacroId, val controls: ExecutionControls)

    /**
     * Accepts a run request. Returns the handle of the (possibly pre-existing) execution, or an error
     * when policy rejects it. Rejections are persisted as terminal REJECTED records.
     */
    suspend fun enqueue(request: RunRequest): AppResult<ExecutionHandle> = stateMutex.withLock {
        ports.store.findByRunRequestId(request.runRequestId, clock.now() - config.runRequestDedupWindow)?.let { existing ->
            active[existing.id]?.let { return AppResult.ok(it.handle) }
            return AppResult.err(ErrorCode.CANCELLED_SUPERSEDED, "runRequestId already processed: ${existing.state}")
        }
        val macro = ports.macroSource.getMacro(request.macroId)
            ?: return AppResult.err(ErrorCode.MACRO_NOT_FOUND, request.macroId.value)
        val now = clock.now()
        val record = ExecutionRecord(
            id = request.executionId,
            macroId = macro.id,
            macroRevision = macro.revision,
            macroName = macro.name,
            origin = request.origin,
            runRequestId = request.runRequestId,
            state = ExecutionState.QUEUED,
            ownerToken = ownerToken,
            queuedAt = now,
            totalStaticSteps = macro.expandedLeafCount,
        )
        val rejection = policyRejection(macro, request, now)
        if (rejection != null) {
            ports.store.insert(
                record.transition(ExecutionState.REJECTED)
                    .copy(endedAt = now, errorCode = rejection, errorCategory = rejection.category),
            )
            emit(ExecutionEvent.StateChanged(record.id, ExecutionState.QUEUED, ExecutionState.REJECTED, rejection))
            return AppResult.err(rejection)
        }
        ports.store.insert(record)
        val controls = ExecutionControls()
        val deferred = scope.async(CoroutineName("exec-${record.id}")) {
            Runner(record, macro, request, controls).run()
        }
        val handle = ExecutionHandle(record.id, deferred, deferred, controls)
        active[record.id] = ActiveRun(handle, macro.id, controls)
        _activeCount.value = active.size
        deferred.invokeOnCompletion {
            scope.launch {
                stateMutex.withLock {
                    active.remove(record.id)
                    _activeCount.value = active.size
                }
            }
        }
        AppResult.ok(handle)
    }

    fun handleFor(id: ExecutionId): ExecutionHandle? = active[id]?.handle

    /** Cancels every active execution (used on shutdown/reboot). */
    fun cancelAll() = active.values.forEach { it.controls.cancel() }

    private fun policyRejection(macro: Macro, request: RunRequest, now: Instant): ErrorCode? {
        if (!macro.enabled && request.origin !is ExecutionOrigin.StepTest) return ErrorCode.MACRO_DISABLED
        if (active.size >= config.queueCapacity) return ErrorCode.QUEUE_FULL
        if (!macro.executionPolicy.allowConcurrentSelf && active.values.any { it.macroId == macro.id }) {
            return ErrorCode.CONCURRENT_SELF_NOT_ALLOWED
        }
        val windowStart = now - 1.minutes
        while (recentStarts.isNotEmpty() && recentStarts.first().first < windowStart) recentStarts.removeFirst()
        if (recentStarts.size >= config.globalStartsPerMinute) return ErrorCode.RATE_LIMITED
        if (recentStarts.count { it.second == macro.id } >= config.perMacroStartsPerMinute) return ErrorCode.RATE_LIMITED
        recentStarts.addLast(now to macro.id)
        return null
    }

    private fun emit(event: ExecutionEvent) {
        _events.tryEmit(event)
    }

    // =============================================================================================

    /** State for one execution. All mutation happens on the execution's own coroutine. */
    private inner class Runner(
        initial: ExecutionRecord,
        private val macro: Macro,
        private val request: RunRequest,
        private val controls: ExecutionControls,
    ) {
        private var record = initial
        private var slotHeld = false
        private var lockHeld = false
        private lateinit var variables: VariableScope
        private var secureValues: Map<String, String> = emptyMap()
        private val pendingLogs = ArrayList<ExecutionLogEntry>()
        private var leafCounter = 0
        private val labelIndex: Map<String, Int> = macro.steps.withIndex()
            .mapNotNull { (i, s) -> s.label?.let { it to i } }.toMap()
        private var runJob: Job? = null

        suspend fun run(): ExecutionOutcome {
            val watcher = scope.launch { controls.cancelRequested.collect { if (it) runJob?.cancel(UserCancel()) } }
            return try {
                coroutineScope {
                    runJob = coroutineContext[Job]
                    if (controls.cancelRequested.value) throw UserCancel()
                    runInternal()
                }
            } catch (e: CancellationException) {
                val userCancelled = e is UserCancel || controls.cancelRequested.value
                val code = if (userCancelled) ErrorCode.CANCELLED_BY_USER else ErrorCode.CANCELLED_PROCESS_DEATH
                withContext(NonCancellable) { finish(ExecutionState.CANCELLED, AppError(code)) }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                withContext(NonCancellable) { finish(ExecutionState.FAILED, AppError(ErrorCode.UNEXPECTED, e.javaClass.simpleName, e)) }
            } finally {
                watcher.cancel()
                withContext(NonCancellable) { releaseAll() }
            }
        }

        private suspend fun runInternal(): ExecutionOutcome {
            // QUEUED → PREPARING: wait for a slot (bounded by queueTimeout).
            val acquired = withTimeoutOrNull(config.queueTimeout) { slots.acquire(); true } ?: false
            if (!acquired) return finish(ExecutionState.REJECTED, AppError(ErrorCode.QUEUE_TIMEOUT))
            slotHeld = true
            transition(ExecutionState.PREPARING)

            val fresh = ports.macroSource.getMacro(macro.id)
            if (fresh == null || (!fresh.enabled && request.origin !is ExecutionOrigin.StepTest)) {
                return finish(ExecutionState.REJECTED, AppError(ErrorCode.MACRO_DISABLED))
            }
            if (request.skipBecauseMissed) return finish(ExecutionState.SKIPPED, AppError(ErrorCode.MISSED))

            when (val r = resolveSecureValues()) {
                is AppResult.Err -> return finish(ExecutionState.FAILED, r.error)
                is AppResult.Ok -> secureValues = r.value
            }
            when (val r = VariableScope.fromInitial(macro.variables, secureValues)) {
                is AppResult.Err -> return finish(ExecutionState.FAILED, r.error)
                is AppResult.Ok -> variables = r.value
            }

            val start = clock.now()
            record = record.copy(startedAt = start)
            transition(ExecutionState.RUNNING)
            ports.hooks.onRunningStarted(record, macro)

            val result = withTimeoutOrNull(macro.executionPolicy.totalTimeout) {
                executeList(macro.steps, topLevel = true)
            } ?: return finish(ExecutionState.FAILED, AppError(ErrorCode.MACRO_TIMEOUT))

            return when (result) {
                is ListOutcome.Completed -> finish(ExecutionState.COMPLETED, null)
                is ListOutcome.Stopped -> if (result.success) {
                    finish(ExecutionState.COMPLETED, null)
                } else {
                    finish(ExecutionState.FAILED, AppError(ErrorCode.STOPPED_BY_MACRO, result.message))
                }
                is ListOutcome.Aborted -> finish(ExecutionState.FAILED, result.error)
                is ListOutcome.Blocked -> finish(ExecutionState.CANCELLED, AppError(result.reason.code, result.reason.detail))
            }
        }

        private suspend fun resolveSecureValues(): AppResult<Map<String, String>> {
            val ids = LinkedHashSet<String>()
            macro.variables.values.forEach { if (it is VariableValue.Secure) ids += it.ref.id }
            macro.allSteps().forEach { step ->
                when (val a = step.action) {
                    is ActionParameters.EnterText -> (a.text as? TextValue.Secure)?.let { ids += it.ref.id }
                    is ActionParameters.OpenUrl -> (a.url as? TextValue.Secure)?.let { ids += it.ref.id }
                    is ActionParameters.SetVariable -> (a.value as? VariableValue.Secure)?.let { ids += it.ref.id }
                    else -> Unit
                }
            }
            val out = HashMap<String, String>()
            for (id in ids) {
                when (val r = ports.secureValues.resolve(id)) {
                    is AppResult.Err -> return r
                    is AppResult.Ok -> out[id] = r.value
                }
            }
            return AppResult.ok(out)
        }

        // ---- step lists -----------------------------------------------------------------------

        private suspend fun executeList(steps: List<MacroStep>, topLevel: Boolean): ListOutcome {
            var index = 0
            while (index < steps.size) {
                coroutineContext.ensureActive()
                val step = steps[index]
                if (!step.enabled) {
                    index++
                    continue
                }
                val outcome = executeStep(step, if (topLevel) index else -1)
                when (outcome) {
                    is StepOutcome.Ok -> index++
                    is StepOutcome.Stopped -> return ListOutcome.Stopped(outcome.success, outcome.message)
                    is StepOutcome.Blocked -> return ListOutcome.Blocked(outcome.reason)
                    is StepOutcome.Failed -> when (val f = step.onFailure) {
                        FailureBehavior.AbortMacro -> return ListOutcome.Aborted(outcome.error)
                        FailureBehavior.Continue -> index++
                        is FailureBehavior.JumpToLabel -> {
                            if (!topLevel) return ListOutcome.Aborted(outcome.error) // nested jumps propagate as abort
                            val target = labelIndex[f.label] ?: return ListOutcome.Aborted(AppError(ErrorCode.JUMP_TARGET_MISSING, f.label))
                            log(LogLevel.WARN, "jump to '${f.label}' after failure ${outcome.error.code}", index)
                            index = target
                        }
                    }
                }
            }
            return ListOutcome.Completed
        }

        private suspend fun executeStep(step: MacroStep, topIndex: Int): StepOutcome {
            val index = if (topIndex >= 0) topIndex else record.currentStepIndex
            return when (val a = step.action) {
                is ActionParameters.If -> executeIf(step, a, index)
                is ActionParameters.Repeat -> executeRepeat(step, a, index)
                is ActionParameters.Parallel -> executeParallel(step, a, index)
                else -> executeLeafWithRetries(step, index)
            }
        }

        private suspend fun executeIf(step: MacroStep, a: ActionParameters.If, index: Int): StepOutcome {
            if (a.condition.needsAccessibility) {
                gateOrBlock(needsAccessibility = true)?.let { return it }
            }
            val ctx = context(step, index, attempt = 1, timeout = 5.seconds)
            val taken = when (val r = LeafActions.evaluate(ctx, a.condition)) {
                is AppResult.Err -> return StepOutcome.Failed(r.error)
                is AppResult.Ok -> r.value
            }
            log(LogLevel.INFO, "if → ${if (taken) "then" else "else"}", index)
            return executeList(if (taken) a.then else a.`else`, topLevel = false).toStepOutcome()
        }

        private suspend fun executeRepeat(step: MacroStep, a: ActionParameters.Repeat, index: Int): StepOutcome {
            val max = a.count ?: a.maxIterations ?: 0
            var iteration = 0
            while (iteration < max) {
                coroutineContext.ensureActive()
                if (a.whileCondition != null) {
                    if (a.whileCondition.needsAccessibility) gateOrBlock(true)?.let { return it }
                    val ctx = context(step, index, attempt = 1, timeout = 5.seconds)
                    when (val r = LeafActions.evaluate(ctx, a.whileCondition)) {
                        is AppResult.Err -> return StepOutcome.Failed(r.error)
                        is AppResult.Ok -> if (!r.value) break
                    }
                }
                val outcome = executeList(a.body, topLevel = false)
                if (outcome !is ListOutcome.Completed) return outcome.toStepOutcome()
                iteration++
                if (iteration < max && a.delayBetween > Duration.ZERO) delay(a.delayBetween)
            }
            log(LogLevel.INFO, "repeat finished after $iteration iteration(s)", index)
            return StepOutcome.Ok
        }

        @Suppress("UnusedParameter") // `step` kept for symmetry with the other executeX helpers and future per-step policy
        private suspend fun executeParallel(step: MacroStep, a: ActionParameters.Parallel, index: Int): StepOutcome {
            log(LogLevel.INFO, "parallel ×${a.children.size}", index)
            val results: List<StepOutcome> = try {
                coroutineScope {
                    a.children.map { child ->
                        async {
                            val r = executeLeafWithRetries(child, index)
                            if (a.failFast && r is StepOutcome.Failed) throw ParallelAbort(r)
                            r
                        }
                    }.awaitAll()
                }
            } catch (e: ParallelAbort) {
                return e.outcome
            }
            results.firstOrNull { it is StepOutcome.Failed }?.let { return it }
            results.firstOrNull { it is StepOutcome.Stopped }?.let { return it }
            return StepOutcome.Ok
        }

        // ---- leaf with retries / timeout / lock -----------------------------------------------

        // The attempt loop mirrors doc 07 §3 (gate -> lock -> attempt -> classify -> retry/block) as one unit so the
        // transition table can be audited top-to-bottom; it is exercised by MacroExecutorTest. TODO(phase-10): split.
        @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "ReturnCount")
        private suspend fun executeLeafWithRetries(step: MacroStep, index: Int): StepOutcome {
            val retry = step.retry ?: macro.executionPolicy.defaultRetry
            var attempt = 1
            while (true) {
                coroutineContext.ensureActive()
                awaitIfPaused(index)
                val needsUi = step.action.concurrencyClass == ConcurrencyClass.UI
                if (needsUi) {
                    gateOrBlock(step.action.needsAccessibility)?.let { return it }
                    if (!lockHeld) {
                        val got = withTimeoutOrNull(macro.executionPolicy.lockAcquireTimeout) { uiLock.lock(); true } ?: false
                        if (!got) {
                            blockedOutcome(BlockedReason(ErrorCode.UI_LOCK_TIMEOUT))?.let { return it }
                            continue // gate passed again: retry lock acquisition for the same attempt
                        }
                        lockHeld = true
                    }
                } else if (lockHeld) {
                    uiLock.unlock()
                    lockHeld = false
                }

                val timeout = MacroBudget.stepTimeout(macro, step)
                val startedAt = clock.now()
                record = record.copy(currentStepIndex = index, currentStepId = step.id, currentAttempt = attempt)
                var attemptRow = StepAttemptRecord(
                    executionId = record.id, stepId = step.id, stepIndex = index, attempt = attempt,
                    actionType = step.action.typeName, state = StepState.RUNNING, startedAt = startedAt,
                )
                ports.store.insertStepAttempt(attemptRow)
                ports.store.update(record)
                emit(ExecutionEvent.StepStarted(record.id, index, step.id, attempt))

                val ctx = context(step, index, attempt, timeout)
                val result: ActionResult = try {
                    withTimeout(timeout) { LeafActions.execute(ctx) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    ActionResult.Failure(AppError(ErrorCode.STEP_TIMEOUT, timeout.toString(), e))
                } catch (e: CancellationException) {
                    attemptRow = attemptRow.copy(state = StepState.CANCELLED, endedAt = clock.now())
                    withContext(NonCancellable) { ports.store.updateStepAttempt(attemptRow); flushLogs() }
                    emit(ExecutionEvent.StepFinished(record.id, index, step.id, attempt, StepState.CANCELLED))
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    ActionResult.Failure(AppError(ErrorCode.UNEXPECTED, e.javaClass.simpleName, e))
                }

                when (result) {
                    is ActionResult.Success -> {
                        leafCounter++
                        attemptRow = attemptRow.copy(state = StepState.COMPLETED, endedAt = clock.now(), outputSummary = result.summary)
                        record = record.copy(completedSteps = leafCounter)
                        ports.store.updateStepAttempt(attemptRow)
                        ports.store.update(record)
                        flushLogs()
                        emit(ExecutionEvent.StepFinished(record.id, index, step.id, attempt, StepState.COMPLETED))
                        emit(ExecutionEvent.Progress(record.id, leafCounter, record.totalStaticSteps, indeterminate = false))
                        return StepOutcome.Ok
                    }
                    is ActionResult.Stop -> {
                        attemptRow = attemptRow.copy(
                            state = StepState.COMPLETED,
                            endedAt = clock.now(),
                            outputSummary = "stop(${result.success})",
                        )
                        ports.store.updateStepAttempt(attemptRow)
                        flushLogs()
                        emit(ExecutionEvent.StepFinished(record.id, index, step.id, attempt, StepState.COMPLETED))
                        return StepOutcome.Stopped(result.success, result.message)
                    }
                    is ActionResult.Failure -> {
                        val error = result.error
                        val state = if (error.code == ErrorCode.STEP_TIMEOUT) StepState.TIMED_OUT else StepState.FAILED
                        attemptRow = attemptRow.copy(
                            state = state,
                            endedAt = clock.now(),
                            errorCode = error.code,
                            errorCategory = error.category,
                        )
                        log(LogLevel.WARN, "step failed: ${error.code}${error.detail?.let { " ($it)" } ?: ""}", index, attempt, error.code)
                        ports.store.updateStepAttempt(attemptRow)
                        flushLogs()
                        emit(ExecutionEvent.StepFinished(record.id, index, step.id, attempt, state, error))

                        if (error.category == ErrorCategory.PRECONDITION && needsUi) {
                            // Re-attempt from the start after the user fixes the precondition; attempt counter unchanged.
                            val blocked = blockedOutcome(BlockedReason(error.code, error.detail))
                            if (blocked != null) return blocked
                            continue
                        }
                        val canRetry = attempt < retry.maxAttempts && error.retryable && error.category in retry.retryOn
                        if (!canRetry) return StepOutcome.Failed(error)
                        attempt++
                        record = record.copy(retryTotal = record.retryTotal + 1)
                        val backoff = retry.delayBefore(attempt)
                        log(LogLevel.INFO, "retry $attempt/${retry.maxAttempts} in $backoff", index, attempt)
                        delay(backoff)
                    }
                }
            }
        }

        /** Checks the gate for a UI segment; on failure enters BLOCKED and waits. Returns a terminal outcome or null. */
        private suspend fun gateOrBlock(needsAccessibility: Boolean): StepOutcome? {
            return when (val g = ports.gate.checkUiSegment(needsAccessibility)) {
                GateResult.Pass -> null
                is GateResult.Blocked -> blockedOutcome(BlockedReason(g.code, g.detail))
            }
        }

        /**
         * BLOCKED: release slot and lock, notify, wait for gate (bounded by `blockedTimeout`), then re-acquire.
         * Returns `null` when execution may continue, or the terminal outcome.
         */
        private suspend fun blockedOutcome(reason: BlockedReason): StepOutcome? {
            if (lockHeld) { uiLock.unlock(); lockHeld = false }
            if (slotHeld) { slots.release(); slotHeld = false }
            record = record.copy(blockedAt = clock.now(), blockedReason = reason)
            transition(ExecutionState.BLOCKED, reason.code)
            ports.hooks.onBlocked(record, macro)
            val needsA11y = macro.requiresAccessibility
            val result = ports.gate.awaitPass(needsA11y, macro.executionPolicy.blockedTimeout)
            if (result !is GateResult.Pass) return StepOutcome.Blocked(BlockedReason(ErrorCode.BLOCKED_TIMEOUT, reason.code.name))
            slots.acquire()
            slotHeld = true
            record = record.copy(blockedAt = null, blockedReason = null)
            transition(ExecutionState.RUNNING)
            return null
        }

        private suspend fun awaitIfPaused(index: Int) {
            if (!controls.pauseRequested.value) return
            if (lockHeld) { uiLock.unlock(); lockHeld = false }
            record = record.copy(pausedAt = clock.now())
            transition(ExecutionState.PAUSED)
            log(LogLevel.INFO, "paused", index)
            while (controls.pauseRequested.value) {
                withTimeoutOrNull(PAUSE_POLL) { controls.resumeSignal.collect { } }
            }
            record = record.copy(pausedAt = null)
            transition(ExecutionState.RUNNING)
        }

        // ---- bookkeeping ----------------------------------------------------------------------

        private fun context(step: MacroStep, index: Int, attempt: Int, timeout: Duration) = ActionContext(
            executionId = record.id, macro = macro, step = step, stepIndex = index, attempt = attempt,
            variables = variables, secureValues = secureValues, ports = ports, clock = clock, timeout = timeout,
            logger = { level, message -> log(level, message, index, attempt) },
        )

        private fun log(level: LogLevel, message: String, index: Int?, attempt: Int? = null, code: ErrorCode? = null) {
            val entry = ExecutionLogEntry(record.id, clock.now(), level, index, attempt, message, code)
            pendingLogs += entry
            emit(ExecutionEvent.Log(record.id, entry))
        }

        private suspend fun flushLogs() {
            if (pendingLogs.isEmpty()) return
            val batch = pendingLogs.toList()
            pendingLogs.clear()
            ports.store.appendLogs(batch)
        }

        private suspend fun transition(to: ExecutionState, reason: ErrorCode? = null) {
            val from = record.state
            record = record.transition(to)
            ports.store.update(record)
            emit(ExecutionEvent.StateChanged(record.id, from, to, reason))
        }

        private suspend fun finish(state: ExecutionState, error: AppError?): ExecutionOutcome {
            val now = clock.now()
            if (record.state.isTerminal) return outcome(error)
            val from = record.state
            record = if (from.canTransitionTo(state)) record.transition(state) else record.transition(ExecutionState.INTERRUPTED)
            record = record.copy(
                endedAt = now,
                errorCode = error?.code,
                errorCategory = error?.category,
                errorDetail = error?.detail,
            )
            error?.let {
                val level = if (state == ExecutionState.COMPLETED) LogLevel.INFO else LogLevel.WARN
                log(level, "finished ${record.state}: ${it.code}", null, null, it.code)
            }
                ?: log(LogLevel.INFO, "finished ${record.state}", null)
            flushLogs()
            ports.store.update(record)
            emit(ExecutionEvent.StateChanged(record.id, from, record.state, error?.code))
            ports.hooks.onTerminal(record, macro)
            return outcome(error)
        }

        private fun outcome(error: AppError?) =
            ExecutionOutcome(record.id, record.state, error, record.completedSteps, record.retryTotal)

        private fun releaseAll() {
            if (lockHeld) { uiLock.unlock(); lockHeld = false }
            if (slotHeld) { slots.release(); slotHeld = false }
        }
    }

    private sealed interface StepOutcome {
        data object Ok : StepOutcome
        data class Failed(val error: AppError) : StepOutcome
        data class Stopped(val success: Boolean, val message: String?) : StepOutcome
        data class Blocked(val reason: BlockedReason) : StepOutcome
    }

    private sealed interface ListOutcome {
        data object Completed : ListOutcome
        data class Aborted(val error: AppError) : ListOutcome
        data class Stopped(val success: Boolean, val message: String?) : ListOutcome
        data class Blocked(val reason: BlockedReason) : ListOutcome
    }

    private fun ListOutcome.toStepOutcome(): StepOutcome = when (this) {
        ListOutcome.Completed -> StepOutcome.Ok
        is ListOutcome.Aborted -> StepOutcome.Failed(error)
        is ListOutcome.Stopped -> StepOutcome.Stopped(success, message)
        is ListOutcome.Blocked -> StepOutcome.Blocked(reason)
    }

    private class UserCancel : CancellationException("cancelled by user")
    private class ParallelAbort(val outcome: StepOutcome) : RuntimeException("parallel fail-fast")

    private companion object {
        const val EVENT_BUFFER = 256
        val PAUSE_POLL = 500.milliseconds
    }
}
