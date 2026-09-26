package com.macroandroid.feature.settings.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.appRunCatching
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.database.repository.ScheduleRepository
import com.macroandroid.core.datastore.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Clock

/**
 * Plain-text diagnostics bundle (FR-SET-1 "Diagnostics"). Contains device/app facts, preferences, macro and schedule
 * summaries, recent execution records and the audit log. It never contains macro step parameters, secure values or
 * log messages (which may echo on-screen text); users export individual execution logs from the execution screen.
 */
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val macros: MacroRepository,
    private val schedules: ScheduleRepository,
    private val executions: ExecutionRepository,
    private val capabilities: CapabilityStatus,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) {
    suspend fun render(): String = withContext(dispatchers.default) {
        val p = prefs.current()
        buildString {
            appendLine("MacroAndroid diagnostics ${clock.now()}")
            appendLine("app ${capabilities.versionName} (${capabilities.versionCode}) package ${context.packageName}")
            appendLine("android ${Build.VERSION.RELEASE} api ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
            append("notifications=${capabilities.notificationsEnabled} ")
            appendLine("batteryUnrestricted=${capabilities.ignoringBatteryOptimizations}")
            appendLine("a11yConsent=${p.accessibilityConsentGranted} v${p.accessibilityDisclosureVersion} verbose=${p.verboseLogging}")
            appendLine("retention days=${p.historyRetentionDays} runs=${p.historyMaxRuns} logs=${p.logMaxEntries}")
            appendLine()
            appendLine("# macros")
            macros.observeSummaries().first().forEach { m ->
                append("${m.id.value} \"${m.name}\" enabled=${m.enabled} steps=${m.stepCount} ")
                appendLine("a11y=${m.requiresAccessibility} lastRun=${m.lastRunState}")
            }
            appendLine()
            appendLine("# schedules")
            schedules.all().forEach { s ->
                append("${s.spec.id.value} macro=${s.spec.macroId.value} enabled=${s.spec.enabled} kind=${s.spec.kind} ")
                appendLine("next=${s.nextRunAt} last=${s.lastResult}")
            }
            appendLine()
            appendLine("# recent executions")
            executions.observeRecent(RECENT_EXECUTIONS).first().forEach { e ->
                appendLine("${e.queuedAt} ${e.id.value} macro=${e.macroId.value} ${e.state} origin=${e.origin} error=${e.errorCode}")
            }
            appendLine()
            appendLine("# audit")
            executions.observeAudit(AUDIT_ROWS).first().forEach { a ->
                appendLine("${a.at} ${a.kind} ${a.subject.orEmpty()} ${a.detail.orEmpty()}")
            }
        }
    }

    suspend fun writeTo(uri: Uri, text: String): AppResult<Unit> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.IO_ERROR) {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("openOutputStream returned null")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    private companion object {
        const val RECENT_EXECUTIONS = 200
        const val AUDIT_ROWS = 500
    }
}
