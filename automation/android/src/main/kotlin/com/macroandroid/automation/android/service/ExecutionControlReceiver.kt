package com.macroandroid.automation.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.macroandroid.automation.android.notification.ExecutionNotifications
import com.macroandroid.automation.model.ExecutionId
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Handles Cancel/Pause/Resume notification actions. Not exported; intents are package-scoped PendingIntents. */
@AndroidEntryPoint
class ExecutionControlReceiver : BroadcastReceiver() {

    @Inject lateinit var runner: MacroRunner

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ExecutionNotifications.EXTRA_EXECUTION_ID)?.let(::ExecutionId) ?: return
        when (intent.action) {
            ExecutionNotifications.ACTION_CANCEL -> runner.cancel(id)
            ExecutionNotifications.ACTION_PAUSE -> runner.pause(id)
            ExecutionNotifications.ACTION_RESUME -> runner.resume(id)
        }
    }
}
