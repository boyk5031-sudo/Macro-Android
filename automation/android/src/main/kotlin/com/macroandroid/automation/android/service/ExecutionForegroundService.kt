package com.macroandroid.automation.android.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.macroandroid.automation.android.notification.ExecutionNotifications
import com.macroandroid.core.common.logging.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while long macros run (type `specialUse`, subtype
 * `user_initiated_macro_execution`). It owns no execution logic: it mirrors [MacroRunner.activeRecords]
 * into its notification and stops itself when nothing is active.
 */
@AndroidEntryPoint
class ExecutionForegroundService : LifecycleService() {

    @Inject lateinit var runner: MacroRunner

    @Inject lateinit var notifications: ExecutionNotifications

    @Inject lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        lifecycleScope.launch {
            runner.activeRecords.collectLatest { records ->
                if (records.isEmpty()) {
                    stopSelf()
                } else {
                    val primary = records.firstOrNull()
                    ServiceCompat.startForeground(
                        this@ExecutionForegroundService,
                        ExecutionNotifications.FGS_NOTIFICATION_ID,
                        notifications.foregroundNotification(records.size, primary),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        } else {
                            0
                        },
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Must call startForeground promptly after startForegroundService(); the collector above does so,
        // but post an initial notification here to be safe on slow devices.
        ServiceCompat.startForeground(
            this,
            ExecutionNotifications.FGS_NOTIFICATION_ID,
            notifications.foregroundNotification(runner.activeRecords.value.size, runner.activeRecords.value.firstOrNull()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: system-imposed FGS time limit reached. The execution continues in-process while it can.
        logger.w(TAG, "FGS timeout; stopping foreground state")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExecutionFGS"

        /** Returns false when the OS refuses (background start restrictions); the run continues without FGS. */
        fun start(context: Context, logger: Logger): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ExecutionForegroundService::class.java))
            true
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+), which extends IllegalStateException.
            logger.w(TAG, "FGS start not allowed", e)
            false
        } catch (e: SecurityException) {
            logger.w(TAG, "FGS start denied", e)
            false
        }
    }
}
