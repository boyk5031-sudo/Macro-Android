package com.macroandroid.feature.execution.presentation

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionOrigin
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.engine.StepState
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.StepId
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.LogLevel
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.feature.execution.data.ExecutionLogExporter
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.time.Instant

class ExecutionLogExporterTest {
    private val exporter = ExecutionLogExporter(
        context = mockk<Context>(relaxed = true),
        executions = mockk<ExecutionRepository>(),
        dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
    )

    @Test
    fun `render contains header, steps and log lines`() {
        val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val id = ExecutionId("e1")
        val record = ExecutionRecord(
            id = id, macroId = MacroId("m1"), macroRevision = 3, macroName = "Morning", origin = ExecutionOrigin.Manual,
            runRequestId = "r", state = ExecutionState.FAILED, ownerToken = null, queuedAt = t0, startedAt = t0, endedAt = t0,
            completedSteps = 1, totalStaticSteps = 2, errorCode = ErrorCode.STEP_TIMEOUT, errorDetail = "clickNode",
        )
        val steps = listOf(
            StepAttemptRecord(id, StepId("s1"), 0, 1, "launchApp", StepState.COMPLETED, t0, t0),
            StepAttemptRecord(id, StepId("s2"), 1, 2, "clickNode", StepState.TIMED_OUT, t0, t0, errorCode = ErrorCode.STEP_TIMEOUT),
        )
        val logs = listOf(ExecutionLogEntry(id, t0, LogLevel.WARN, 1, 2, "node not found"))
        val text = exporter.render(record, steps, logs)
        assertThat(text).contains("macro: Morning (m1 rev 3)")
        assertThat(text).contains("state: FAILED")
        assertThat(text).contains("#2 clickNode attempt 2 TIMED_OUT")
        assertThat(text).contains("WARN [#2] node not found")
    }
}
