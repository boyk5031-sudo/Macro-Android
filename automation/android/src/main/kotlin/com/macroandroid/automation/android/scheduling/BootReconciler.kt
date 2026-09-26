package com.macroandroid.automation.android.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.macroandroid.automation.android.service.MacroRunner
import com.macroandroid.core.common.logging.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * RECEIVE_BOOT_COMPLETED is used only to reconcile state (Phase 0 decision): interrupted executions are marked and
 * schedules are re-planned. No macro is started from here; late occurrences go through the normal missed-run policy
 * inside [ScheduleWorker], which WorkManager itself defers until constraints are met.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            ReconcileWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReconcileWorker>().build(),
        )
    }
}

@HiltWorker
class ReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: MacroRunner,
    private val scheduler: WorkManagerScheduler,
    private val logger: Logger,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runner.reconcile()
        scheduler.reconcileAll()
        logger.i(TAG, "reconciled after boot/update")
        return Result.success()
    }

    companion object {
        const val TAG = "Reconcile"
        const val WORK_NAME = "reconcile"
    }
}
