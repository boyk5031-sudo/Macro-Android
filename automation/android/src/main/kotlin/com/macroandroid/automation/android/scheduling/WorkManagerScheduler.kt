package com.macroandroid.automation.android.scheduling

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.automation.schedule.MissedDecision
import com.macroandroid.automation.schedule.NextRunCalculator
import com.macroandroid.core.common.contract.SchedulerContract
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.repository.ScheduleRepository
import com.macroandroid.core.database.repository.StoredSchedule
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Plans schedules as unique one-time WorkManager requests (`schedule:<id>`, doc 08 §3). One-time requests with an
 * initial delay are used for every kind (including intervals) so "daily at 07:30" and DST are computed by
 * [NextRunCalculator] rather than approximated by PeriodicWorkRequest's flex windows.
 */
@Singleton
class WorkManagerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val schedules: ScheduleRepository,
    private val clock: Clock,
    private val logger: Logger,
) : SchedulerContract {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun plan(scheduleId: String) {
        val stored = schedules.get(ScheduleId(scheduleId))
        if (stored == null || !stored.spec.enabled) {
            cancel(scheduleId)
            return
        }
        val now = clock.now()
        val next = NextRunCalculator.next(stored.spec, now)
        enqueue(stored, next, now)
    }

    /** Called by the worker after an occurrence fired at [planned]. */
    suspend fun planAfter(stored: StoredSchedule, planned: Instant) {
        val now = clock.now()
        val next = NextRunCalculator.nextAfterPlanned(stored.spec, planned, now)
        enqueue(stored, next, now)
    }

    override suspend fun cancel(scheduleId: String) {
        workManager.cancelUniqueWork(workName(scheduleId))
        schedules.get(ScheduleId(scheduleId))?.let { schedules.setNextRun(it.spec.id, null, null) }
    }

    override suspend fun reconcileAll() {
        val now = clock.now()
        for (stored in schedules.all()) {
            if (stored.spec.enabled) reconcileOne(stored, now) else workManager.cancelUniqueWork(stored.workName)
        }
    }

    private suspend fun reconcileOne(stored: StoredSchedule, now: Instant) {
        val planned = stored.nextRunAt
        if (planned == null || planned > now) {
            enqueue(stored, planned ?: NextRunCalculator.next(stored.spec, now), now)
            return
        }
        // The occurrence passed while the process/device was down; apply the missed-run policy here so the decision
        // is auditable in the schedule row, then continue on the normal grid.
        val ranSince = stored.lastFiredAt?.let { it >= planned } == true
        val late = NextRunCalculator.isLate(stored.spec, planned, now)
        val decision = if (late) NextRunCalculator.decideMissed(stored.spec, ranSince) else MissedDecision.RUN_NOW
        if (decision == MissedDecision.RUN_NOW) {
            enqueue(stored, now, now, plannedOverride = planned, late = late)
        } else {
            schedules.recordFired(stored.spec.id, now, "SKIPPED_MISSED")
            enqueue(stored, NextRunCalculator.nextAfterPlanned(stored.spec, planned, now), now)
        }
    }

    private suspend fun enqueue(
        stored: StoredSchedule,
        runAt: Instant?,
        now: Instant,
        plannedOverride: Instant? = null,
        late: Boolean = false,
    ) {
        val id = stored.spec.id
        if (runAt == null) {
            workManager.cancelUniqueWork(stored.workName)
            schedules.setNextRun(id, null, null)
            logger.i(TAG, "schedule ${id.value}: no further occurrence")
            return
        }
        val planned = plannedOverride ?: runAt
        val delay: Duration = (runAt - now).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(constraintsFor(stored.spec))
            .setInputData(
                workDataOf(
                    ScheduleWorker.KEY_SCHEDULE_ID to id.value,
                    ScheduleWorker.KEY_PLANNED_AT to planned.toEpochMilliseconds(),
                    ScheduleWorker.KEY_LATE to late,
                ),
            )
            .addTag(TAG_SCHEDULE)
            .build()
        workManager.enqueueUniqueWork(stored.workName, ExistingWorkPolicy.REPLACE, request)
        schedules.setNextRun(id, runAt, planned)
        logger.i(TAG, "schedule ${id.value}: next run at $runAt (planned $planned, delay $delay)")
    }

    private fun constraintsFor(spec: ScheduleSpec): Constraints = Constraints.Builder()
        .setRequiresCharging(spec.requiresCharging)
        .setRequiresBatteryNotLow(spec.requiresBatteryNotLow)
        .setRequiresDeviceIdle(spec.requiresDeviceIdle)
        .build()

    companion object {
        const val TAG = "Scheduler"
        const val TAG_SCHEDULE = "macro-schedule"
        fun workName(scheduleId: String) = "schedule:$scheduleId"
    }
}
