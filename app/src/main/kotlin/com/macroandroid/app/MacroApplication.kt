package com.macroandroid.app

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.macroandroid.app.shortcuts.AppShortcuts
import com.macroandroid.automation.android.service.MacroRunner
import com.macroandroid.core.common.contract.SchedulerContract
import com.macroandroid.core.common.coroutines.ApplicationScope
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.datastore.UserPreferencesRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock

@HiltAndroidApp
class MacroApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var runner: MacroRunner
    @Inject lateinit var scheduler: SchedulerContract
    @Inject lateinit var executions: ExecutionRepository
    @Inject lateinit var prefs: UserPreferencesRepository
    @Inject lateinit var shortcuts: AppShortcuts
    @Inject lateinit var logger: Logger
    @Inject lateinit var clock: Clock
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        if (BuildConfig.DEBUG) enableStrictMode()
        super.onCreate()
        appScope.launch { startupHousekeeping() }
    }

    /**
     * Doc 07 §7 / doc 08 §3: mark orphaned executions, re-plan schedules, apply retention, refresh dynamic shortcuts.
     * Nothing here starts a macro.
     */
    private suspend fun startupHousekeeping() {
        runCatching { runner.reconcile() }.onFailure { logger.e(TAG, "reconcile failed", it) }
        runCatching { scheduler.reconcileAll() }.onFailure { logger.e(TAG, "schedule reconcile failed", it) }
        runCatching {
            val p = prefs.current()
            executions.applyRetention(p.historyRetentionDays, p.historyMaxRuns, p.logMaxEntries.toLong())
            prefs.setLastReconcileAt(clock.now().toEpochMilliseconds())
        }.onFailure { logger.e(TAG, "retention failed", it) }
        runCatching { shortcuts.publishRecentMacros() }.onFailure { logger.w(TAG, "shortcut refresh failed", it) }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build(),
        )
    }

    private companion object {
        const val TAG = "App"
    }
}
