package com.macroandroid.automation.android.service

import android.content.Context
import com.macroandroid.automation.android.accessibility.AccessibilityServiceRegistry
import com.macroandroid.automation.android.gate.ForegroundGate
import com.macroandroid.automation.android.notification.ExecutionNotifications
import com.macroandroid.automation.engine.EnginePorts
import com.macroandroid.automation.engine.ExecutionEvent
import com.macroandroid.automation.engine.ExecutionHandle
import com.macroandroid.automation.engine.ExecutionOrigin
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.engine.MacroBudget
import com.macroandroid.automation.engine.MacroExecutor
import com.macroandroid.automation.engine.RunRequest
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.port.EngineConfig
import com.macroandroid.automation.port.ExecutionLifecycleHooks
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.coroutines.ApplicationScope
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.database.repository.MacroRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The single process-wide engine host. Wires the pure engine to Android: FGS start/stop, BLOCKED
 * notifications, accessibility "active" flag, last-run bookkeeping, and start-up reconciliation.
 */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class MacroRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val macros: MacroRepository,
    private val executions: ExecutionRepository,
    private val notifications: ExecutionNotifications,
    private val registry: AccessibilityServiceRegistry,
    private val foregroundGate: ForegroundGate,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val logger: Logger,
    private val config: EngineConfig,
    ports: EnginePorts,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val hooks = Hooks()
    private val executor = MacroExecutor(
        ports = ports.copy(hooks = hooks),
        config = config,
        clock = clock,
        dispatcher = dispatchers.default,
    )
    private val reconciled = AtomicBoolean(false)

    private val _activeRecords = MutableStateFlow<List<ExecutionRecord>>(emptyList())

    /** Live snapshot of running/blocked/paused records (for the FGS notification and the Monitor screen). */
    val activeRecords: StateFlow<List<ExecutionRecord>> = _activeRecords.asStateFlow()

    val events: SharedFlow<ExecutionEvent> = executor.events
    val ownerToken: String get() = executor.ownerToken

    init {
        scope.launch { executions.observeActive().collect { list -> _activeRecords.value = list.filter { it.ownerToken == ownerToken } } }
    }

    /**
     * Marks records left by a previous process instance as INTERRUPTED. Idempotent; called from
     * `Application.onCreate` and the boot receiver (never starts anything).
     */
    suspend fun reconcile() {
        if (!reconciled.compareAndSet(false, true)) return
        val n = executions.interruptOrphans(ownerToken, ErrorCode.CANCELLED_PROCESS_DEATH)
        if (n > 0) logger.i(TAG, "reconciled $n orphaned execution(s)")
    }

    suspend fun run(macroId: MacroId, origin: ExecutionOrigin = ExecutionOrigin.Manual, runRequestId: String = Uuid.random().toString()):
        AppResult<ExecutionHandle> = executor.enqueue(RunRequest(macroId, origin, runRequestId))

    suspend fun runScheduled(macroId: MacroId, origin: ExecutionOrigin.Schedule, runRequestId: String, skipBecauseMissed: Boolean):
        AppResult<ExecutionHandle> = executor.enqueue(RunRequest(macroId, origin, runRequestId, skipBecauseMissed))

    /** Runs a single step of a macro (editor "test step"); persisted like any run with origin StepTest. */
    suspend fun testStep(macroId: MacroId, stepId: StepId): AppResult<ExecutionHandle> =
        executor.enqueue(RunRequest(macroId, ExecutionOrigin.StepTest(stepId), "steptest:${Uuid.random()}"))

    fun cancel(id: ExecutionId) = executor.handleFor(id)?.cancel()
    fun pause(id: ExecutionId) = executor.handleFor(id)?.pause()
    fun resume(id: ExecutionId) = executor.handleFor(id)?.resume()
    fun cancelAll() = executor.cancelAll()
    fun isActive(id: ExecutionId): Boolean = executor.handleFor(id) != null

    private inner class Hooks : ExecutionLifecycleHooks {
        override suspend fun onRunningStarted(record: ExecutionRecord, macro: Macro) {
            if (macro.requiresAccessibility) registry.automationActive.value = true
            val budget = MacroBudget.staticTimeBudget(macro)
            if (budget > config.foregroundServiceThreshold && foregroundGate.mayStartActivity()) {
                ExecutionForegroundService.start(context, logger)
            }
        }

        override suspend fun onBlocked(record: ExecutionRecord, macro: Macro) {
            notifications.showBlocked(record)
        }

        override suspend fun onTerminal(record: ExecutionRecord, macro: Macro?) {
            notifications.cancelBlocked(record.id)
            macros.recordLastRun(record.macroId, record.endedAt ?: clock.now(), record.state)
            _activeRecords.update { list -> list.filterNot { it.id == record.id } }
            if (_activeRecords.value.none { it.state == ExecutionState.RUNNING || it.state == ExecutionState.PAUSED }) {
                registry.automationActive.value = false
            }
        }
    }

    private companion object {
        const val TAG = "MacroRunner"
    }
}
