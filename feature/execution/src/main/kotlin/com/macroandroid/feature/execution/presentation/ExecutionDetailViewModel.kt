package com.macroandroid.feature.execution.presentation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.android.service.MacroRunner
import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.core.common.contract.MacroRunnerContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.feature.execution.data.ExecutionLogExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExecutionDetailUiState(
    val record: ExecutionRecord? = null,
    val steps: ImmutableList<StepAttemptRecord> = persistentListOf(),
    val logs: ImmutableList<ExecutionLogEntry> = persistentListOf(),
    val isLive: Boolean = false,
    val loaded: Boolean = false,
)

sealed interface ExecutionDetailEvent {
    data class Error(val error: AppError) : ExecutionDetailEvent
    data object Exported : ExecutionDetailEvent
    data class Rerun(val executionId: String) : ExecutionDetailEvent
}

/** FR-EXE-3/4/6: one execution's live status, step timeline, log, controls, re-run and export. */
@HiltViewModel
class ExecutionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val executions: ExecutionRepository,
    private val runner: MacroRunner,
    private val runnerContract: MacroRunnerContract,
    private val exporter: ExecutionLogExporter,
) : ViewModel() {
    val executionId = ExecutionId(checkNotNull(savedState[ARG_ID]) { "executionId missing" })

    private val _events = MutableSharedFlow<ExecutionDetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ExecutionDetailEvent> = _events.asSharedFlow()

    val uiState: StateFlow<ExecutionDetailUiState> = combine(
        executions.observe(executionId),
        executions.observeSteps(executionId),
        executions.observeLogs(executionId),
    ) { record, steps, logs ->
        ExecutionDetailUiState(
            record = record,
            steps = steps.toImmutableList(),
            logs = logs.toImmutableList(),
            isLive = record != null && runner.isActive(record.id),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExecutionDetailUiState())

    fun cancel() = runner.cancel(executionId)
    fun pause() = runner.pause(executionId)
    fun resume() = runner.resume(executionId)

    fun rerun() = viewModelScope.launch {
        val macroId = uiState.value.record?.macroId ?: return@launch
        when (val r = runnerContract.runMacro(macroId.value)) {
            is AppResult.Ok -> _events.emit(ExecutionDetailEvent.Rerun(r.value))
            is AppResult.Err -> _events.emit(ExecutionDetailEvent.Error(r.error))
        }
    }

    fun suggestedExportName(): String = "execution-${executionId.value.take(EXPORT_ID_CHARS)}.txt"

    fun export(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val text = exporter.render(executionId)
            if (text == null) {
                _events.emit(ExecutionDetailEvent.Error(AppError(ErrorCode.EXECUTION_NOT_FOUND)))
                return@launch
            }
            when (val r = exporter.writeTo(uri, text)) {
                is AppResult.Ok -> _events.emit(ExecutionDetailEvent.Exported)
                is AppResult.Err -> _events.emit(ExecutionDetailEvent.Error(r.error))
            }
        }
    }

    companion object {
        const val ARG_ID = "executionId"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val EXPORT_ID_CHARS = 8
    }
}
