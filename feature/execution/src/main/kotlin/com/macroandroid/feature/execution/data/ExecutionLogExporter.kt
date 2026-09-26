package com.macroandroid.feature.execution.data

import android.content.Context
import android.net.Uri
import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.repository.ExecutionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

/** FR-EXE-6: plain-text export of one execution's timeline through a SAF-created document. */
class ExecutionLogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executions: ExecutionRepository,
    private val dispatchers: AppDispatchers,
) {
    suspend fun render(id: ExecutionId): String? {
        val record = executions.get(id) ?: return null
        return render(record, executions.steps(id), executions.logs(id))
    }

    fun render(record: ExecutionRecord, steps: List<StepAttemptRecord>, logs: List<ExecutionLogEntry>): String = buildString {
        appendLine("Macro Android execution report")
        appendLine("execution: ${record.id.value}")
        appendLine("macro: ${record.macroName} (${record.macroId.value} rev ${record.macroRevision})")
        appendLine("origin: ${record.origin}")
        appendLine("state: ${record.state}")
        appendLine("queued: ${record.queuedAt}  started: ${record.startedAt}  ended: ${record.endedAt}")
        appendLine("steps completed: ${record.completedSteps}/${record.totalStaticSteps}  retries: ${record.retryTotal}")
        record.errorCode?.let { appendLine("error: $it ${record.errorDetail.orEmpty()}") }
        appendLine()
        appendLine("Step attempts")
        steps.forEach { s ->
            appendLine(
                "  #${s.stepIndex + 1} ${s.actionType} attempt ${s.attempt} ${s.state} " +
                    "${s.startedAt} → ${s.endedAt ?: "-"}${s.errorCode?.let { " $it" } ?: ""}${s.outputSummary?.let { " ($it)" } ?: ""}",
            )
        }
        appendLine()
        appendLine("Log")
        logs.forEach { l ->
            val step = l.stepIndex?.let { "[#${it + 1}] " } ?: ""
            val code = l.errorCode?.let { " ($it)" } ?: ""
            appendLine("  ${l.at} ${l.level} $step${l.message}$code")
        }
    }

    suspend fun writeTo(uri: Uri, text: String): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext AppResult.err(AppError(ErrorCode.FILE_UNREADABLE))
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            AppResult.ok(Unit)
        } catch (e: FileNotFoundException) {
            AppResult.err(AppError(ErrorCode.URI_PERMISSION_REVOKED, e.message))
        } catch (e: SecurityException) {
            AppResult.err(AppError(ErrorCode.URI_PERMISSION_REVOKED, e.message))
        } catch (e: IOException) {
            AppResult.err(AppError(ErrorCode.FILE_UNREADABLE, e.message))
        }
    }
}
