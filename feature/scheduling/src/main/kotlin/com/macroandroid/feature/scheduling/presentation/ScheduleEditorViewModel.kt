package com.macroandroid.feature.scheduling.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MissedRunPolicy
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.automation.schedule.NextRunCalculator
import com.macroandroid.automation.validation.MacroValidator
import com.macroandroid.automation.validation.ValidationEnvironment
import com.macroandroid.automation.validation.ValidationResult
import com.macroandroid.core.common.contract.SchedulerContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.database.repository.MacroSummary
import com.macroandroid.core.database.repository.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

data class ScheduleEditorUiState(
    val spec: ScheduleSpec? = null,
    val isNew: Boolean = true,
    val macros: ImmutableList<MacroSummary> = persistentListOf(),
    val macro: Macro? = null,
    val validation: ValidationResult = ValidationResult.OK,
    val nextRunPreview: Instant? = null,
    val saving: Boolean = false,
    val loaded: Boolean = false,
) {
    val canSave: Boolean get() = spec != null && validation.isValid && !saving && macro != null
}

sealed interface ScheduleEditorEvent {
    data class Saved(val id: ScheduleId) : ScheduleEditorEvent
    data class Error(val error: AppError) : ScheduleEditorEvent
}

/** FR-SCH-2/3: create or edit one schedule; validation and next-run preview are recomputed on every edit. */
@HiltViewModel
class ScheduleEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val schedules: ScheduleRepository,
    private val macros: MacroRepository,
    private val scheduler: SchedulerContract,
    private val clock: Clock,
) : ViewModel() {
    private val scheduleIdArg: String? = savedState[ARG_SCHEDULE_ID]
    private val macroIdArg: String? = savedState[ARG_MACRO_ID]

    private val _state = MutableStateFlow(ScheduleEditorUiState())
    val uiState: StateFlow<ScheduleEditorUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ScheduleEditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ScheduleEditorEvent> = _events.asSharedFlow()

    private val validator = MacroValidator(
        object : ValidationEnvironment {
            override fun now(): Instant = clock.now()
        },
    )

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val summaries = macros.observeSummaries().first()
        val existing = scheduleIdArg?.let { schedules.get(ScheduleId(it)) }?.spec
        val spec = existing ?: ScheduleSpec(
            id = ScheduleId.random(),
            macroId = macroIdArg?.let(::MacroId) ?: summaries.firstOrNull()?.id ?: MacroId(""),
            zoneId = TimeZone.currentSystemDefault().id,
            kind = ScheduleKind.Daily(LocalTime(DEFAULT_HOUR, 0), DayOfWeek.entries.toSet()),
        )
        val macro = macros.get(spec.macroId)
        _state.update { it.copy(spec = spec, isNew = existing == null, macros = summaries.toImmutableList(), macro = macro, loaded = true) }
        revalidate()
    }

    fun setMacro(id: MacroId) = viewModelScope.launch {
        val macro = macros.get(id)
        _state.update { s -> s.copy(spec = s.spec?.copy(macroId = id), macro = macro) }
        revalidate()
    }

    fun setKind(kind: ScheduleKind) = update { it.copy(kind = kind) }
    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setRequiresCharging(v: Boolean) = update { it.copy(requiresCharging = v) }
    fun setRequiresBatteryNotLow(v: Boolean) = update { it.copy(requiresBatteryNotLow = v) }
    fun setRequiresDeviceIdle(v: Boolean) = update { it.copy(requiresDeviceIdle = v) }
    fun setMissedPolicy(v: MissedRunPolicy) = update { it.copy(missedRunPolicy = v) }
    fun setLateThreshold(v: Duration) = update { it.copy(lateThreshold = v.coerceIn(MIN_LATE, MAX_LATE)) }

    private fun update(f: (ScheduleSpec) -> ScheduleSpec) {
        _state.update { s -> s.copy(spec = s.spec?.let(f)) }
        revalidate()
    }

    private fun revalidate() {
        val s = _state.value
        val spec = s.spec ?: return
        val result = validator.validate(spec, s.macro)
        val preview = if (result.isValid) NextRunCalculator.next(spec, clock.now()) else null
        _state.update { it.copy(validation = result, nextRunPreview = preview) }
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val spec = s.spec ?: return@launch
        val result = validator.validate(spec, s.macro)
        if (!result.isValid) {
            _state.update { it.copy(validation = result) }
            return@launch
        }
        _state.update { it.copy(saving = true) }
        when (val r = schedules.save(spec)) {
            is AppResult.Ok -> {
                scheduler.plan(spec.id.value)
                _state.update { it.copy(saving = false, isNew = false) }
                _events.emit(ScheduleEditorEvent.Saved(spec.id))
            }
            is AppResult.Err -> {
                _state.update { it.copy(saving = false) }
                _events.emit(ScheduleEditorEvent.Error(r.error))
            }
        }
    }

    companion object {
        const val ARG_SCHEDULE_ID = "scheduleId"
        const val ARG_MACRO_ID = "macroId"
        private const val DEFAULT_HOUR = 8
        private val MIN_LATE = Duration.ZERO
        private val MAX_LATE = 24.hours
    }
}
