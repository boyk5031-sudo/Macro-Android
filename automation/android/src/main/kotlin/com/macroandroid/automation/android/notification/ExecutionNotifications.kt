package com.macroandroid.automation.android.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.macroandroid.automation.android.R
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.port.NotificationPort
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds every execution-related notification. Deep links are internal (`macroandroid://execution/{id}`). */
@Singleton
class ExecutionNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationPort {

    private val manager get() = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.automation_android_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            )
                .apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ATTENTION,
                context.getString(R.string.automation_android_channel_attention),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.automation_android_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    val notificationsEnabled: Boolean get() = manager.areNotificationsEnabled()

    /** Ongoing FGS notification summarising active runs. */
    fun foregroundNotification(activeCount: Int, primary: ExecutionRecord?): Notification {
        val title = primary?.let { context.getString(R.string.automation_android_notification_running, it.macroName) }
            ?: context.getString(R.string.automation_android_fgs_title)
        val text = primary?.let {
            context.getString(R.string.automation_android_notification_step, it.completedSteps + 1, it.totalStaticSteps.coerceAtLeast(1))
        } ?: context.getString(R.string.automation_android_fgs_text, activeCount)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(primary?.let { openExecution(it.id) })
        if (primary != null) {
            builder.setProgress(primary.totalStaticSteps.coerceAtLeast(1), primary.completedSteps, primary.totalStaticSteps == 0)
            builder.addAction(
                0,
                context.getString(R.string.automation_android_notification_cancel),
                controlIntent(ACTION_CANCEL, primary.id),
            )
        }
        return builder.build()
    }

    fun showBlocked(record: ExecutionRecord) {
        if (!notificationsEnabled) return
        val n = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.automation_android_notification_blocked_title, record.macroName))
            .setContentText(context.getString(R.string.automation_android_notification_blocked_text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openExecution(record.id))
            .addAction(0, context.getString(R.string.automation_android_notification_cancel), controlIntent(ACTION_CANCEL, record.id))
            .build()
        notify(record.id.hashCode(), n)
    }

    fun cancelBlocked(id: ExecutionId) = manager.cancel(id.hashCode())

    /** `SendNotification` macro action. Text is already redacted by the engine when sensitive. */
    override suspend fun postMacroNotification(
        executionId: ExecutionId,
        title: String,
        text: String,
        tapOpensExecution: Boolean,
    ): AppResult<Unit> {
        if (!notificationsEnabled) return AppResult.err(ErrorCode.NOTIFICATIONS_DENIED)
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(if (tapOpensExecution) openExecution(executionId) else null)
            .build()
        notify((executionId.value + title).hashCode(), n)
        return AppResult.ok(Unit)
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call; nothing else to do.
        }
    }

    private fun openExecution(id: ExecutionId): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("macroandroid://execution/${id.value}"))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun controlIntent(action: String, id: ExecutionId): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName).putExtra(EXTRA_EXECUTION_ID, id.value)
        return PendingIntent.getBroadcast(
            context,
            (action + id.value).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "execution_progress"
        const val CHANNEL_ATTENTION = "execution_attention"
        const val CHANNEL_RESULTS = "execution_results"
        const val ACTION_CANCEL = "com.macroandroid.action.CANCEL_EXECUTION"
        const val ACTION_PAUSE = "com.macroandroid.action.PAUSE_EXECUTION"
        const val ACTION_RESUME = "com.macroandroid.action.RESUME_EXECUTION"
        const val EXTRA_EXECUTION_ID = "execution_id"
        const val FGS_NOTIFICATION_ID = 1001
    }
}
