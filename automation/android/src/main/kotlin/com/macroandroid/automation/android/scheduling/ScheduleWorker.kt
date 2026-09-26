package com.macroandroid.automation.android.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.macroandroid.automation.android.service.MacroRunner
import com.macroandroid.automation.engine.ExecutionOrigin
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.schedule.MissedDecision
import com.macroandroid.automation.schedule.NextRunCalculator
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.repository.ScheduleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Fires one planned occurrence (doc 08 §3–4). The macro itself runs in the application-scoped [MacroRunner]; the
 * worker keeps the process alive while it waits (bounded by [AWAIT_MAX], below WorkManager's 10-minute limit) and
 * then plans the next occurrence. If WorkManager stops the worker, the execution is cancelled explicitly so the
 * history never shows a phantom "running" row.
 */
@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val schedules: ScheduleRepository,
    private val runner: MacroRunner,
    private val scheduler: WorkManagerScheduler,
    private val clock: Clock,
    private val logger: Logger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val planned = Instant.fromEpochMilliseconds(inputData.getLong(KEY_PLANNED_AT, clock.now().toEpochMilliseconds()))
        val forcedLate = inputData.getBoolean(KEY_LATE, false)
        val stored = schedules.get(ScheduleId(id))
        if (stored == null || !stored.spec.enabled) {
            logger.i(TAG, "schedule $id gone or disabled; nothing to run")
            return Result.success()
        }
        val now = clock.now()
        val late = forcedLate || NextRunCalculator.isLate(stored.spec, planned, now)
        val ranSince = stored.lastFiredAt?.let { it >= planned } == true
        val skip = late && NextRunCalculator.decideMissed(stored.spec, ranSince) == MissedDecision.SKIP

        // runRequestId = schedule + planned slot: a retried/duplicated worker never starts the same occurrence twice.
        val runRequestId = "schedule:$id:${planned.toEpochMilliseconds()}"
        val origin = ExecutionOrigin.Schedule(stored.spec.id, plannedAt = planned, late = late)
        val outcome = when (val r = runner.runScheduled(stored.spec.macroId, origin, runRequestId, skipBecauseMissed = skip)) {
            is AppResult.Err -> {
                logger.w(TAG, "schedule $id rejected: ${r.error.code}")
                schedules.recordFired(stored.spec.id, now, "REJECTED:${r.error.code}")
                scheduler.planAfter(stored, planned)
                return Result.success()
            }
            is AppResult.Ok -> r.value
        }
        val result = try {
            withTimeoutOrNull(AWAIT_MAX) { outcome.await() }
        } catch (e: CancellationException) {
            // WorkManager stopped us (constraints lost, quota, process shutdown): stop the macro too.
            outcome.cancel()
            throw e
        }
        val label = result?.state?.name ?: ExecutionState.RUNNING.name
        schedules.recordFired(stored.spec.id, clock.now(), label)
        scheduler.planAfter(stored, planned)
        return Result.success()
    }

    companion object {
        const val TAG = "ScheduleWorker"
        const val KEY_SCHEDULE_ID = "scheduleId"
        const val KEY_PLANNED_AT = "plannedAt"
        const val KEY_LATE = "late"
        val AWAIT_MAX = 9.minutes
    }
}
