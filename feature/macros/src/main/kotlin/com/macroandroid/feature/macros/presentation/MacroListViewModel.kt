package com.macroandroid.feature.macros.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.core.common.contract.MacroRunnerContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.database.repository.MacroSummary
import com.macroandroid.core.database.repository.ScheduleRepository
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.feature.macros.data.ImportConflictChoice
import com.macroandroid.feature.macros.data.ImportPreview
import com.macroandroid.feature.macros.data.MacroTransfer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MacroListFilter(
    val query: String = "",
    val enabledOnly: Boolean = false,
    val requiresA11yOnly: Boolean = false,
    val scheduledOnly: Boolean = false,
    val profile: String? = null,
)

data class MacroRow(val summary: MacroSummary, val scheduleCount: Int, val hasDraft: Boolean)

data class MacroListUiState(
    val groups: ImmutableList<Pair<String, ImmutableList<MacroRow>>>,
    val profiles: ImmutableList<String>,
    val filter: MacroListFilter,
    val total: Int,
    val loaded: Boolean,
    /** Pending export payload waiting for a `CREATE_DOCUMENT` URI. */
    val pendingExport: PendingExport? = null,
    val importPreview: ImportPreview? = null,
)

data class PendingExport(val ids: List<MacroId>, val suggestedName: String, val includeSecrets: Boolean)

sealed interface MacroListEvent {
    data class Started(val name: String, val executionId: String) : MacroListEvent
    data class Error(val error: AppError) : MacroListEvent
    data class Deleted(val name: String) : MacroListEvent
    data class Duplicated(val name: String) : MacroListEvent
    data class Exported(val count: Int) : MacroListEvent
    data class Imported(val count: Int) : MacroListEvent
    data class ConfirmRun(val macroId: MacroId, val name: String) : MacroListEvent
}

@HiltViewModel
class MacroListViewModel @Inject constructor(
    private val macros: MacroRepository,
    private val schedules: ScheduleRepository,
    private val runner: MacroRunnerContract,
    private val transfer: MacroTransfer,
    private val executions: ExecutionRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(MacroListFilter())
    private val pendingExport = MutableStateFlow<PendingExport?>(null)
    private val importPreview = MutableStateFlow<ImportPreview?>(null)
    private val _events = MutableSharedFlow<MacroListEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MacroListEvent> = _events.asSharedFlow()

    private val rows = combine(macros.observeSummaries(), schedules.observeAll(), macros.observeDraftIds()) { list, scheds, drafts ->
        val byMacro = scheds.groupingBy { it.spec.macroId }.eachCount()
        list.map { MacroRow(it, byMacro[it.id] ?: 0, it.id in drafts) }
    }

    val uiState: StateFlow<MacroListUiState> = combine(rows, filter, pendingExport, importPreview) { all, f, exp, imp ->
        val q = f.query.trim().lowercase()
        val visible = all.filter { r ->
            val s = r.summary
            (q.isBlank() || s.name.lowercase().contains(q) || s.description.lowercase().contains(q) || s.tags.any { it.contains(q) }) &&
                (!f.enabledOnly || s.enabled) &&
                (!f.requiresA11yOnly || s.requiresAccessibility) &&
                (!f.scheduledOnly || r.scheduleCount > 0) &&
                (f.profile == null || s.profile == f.profile)
        }
        val groups = visible.groupBy { it.summary.profile }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (p, rs) -> p to rs.sortedBy { it.summary.name.lowercase() }.toImmutableList() }
        MacroListUiState(
            groups = groups.toImmutableList(),
            profiles = all.map { it.summary.profile }.distinct().sorted().toImmutableList(),
            filter = f,
            total = all.size,
            loaded = true,
            pendingExport = exp,
            importPreview = imp,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        MacroListUiState(persistentListOf(), persistentListOf(), MacroListFilter(), 0, loaded = false),
    )

    val isBusy: StateFlow<Boolean> get() = busy.asStateFlow()
    private val busy = MutableStateFlow(false)

    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun setEnabledOnly(v: Boolean) = filter.update { it.copy(enabledOnly = v) }
    fun setRequiresA11yOnly(v: Boolean) = filter.update { it.copy(requiresA11yOnly = v) }
    fun setScheduledOnly(v: Boolean) = filter.update { it.copy(scheduledOnly = v) }
    fun setProfile(p: String?) = filter.update { it.copy(profile = p) }

    fun run(id: MacroId, name: String, confirmed: Boolean = false) = viewModelScope.launch {
        val macro = macros.get(id)
        val needsConfirm = prefs.current().confirmBeforeRun || macro?.executionPolicy?.requiresConfirmationBeforeRun == true
        if (needsConfirm && !confirmed) {
            _events.emit(MacroListEvent.ConfirmRun(id, name))
            return@launch
        }
        when (val r = runner.runMacro(id.value)) {
            is AppResult.Ok -> _events.emit(MacroListEvent.Started(name, r.value))
            is AppResult.Err -> _events.emit(MacroListEvent.Error(r.error))
        }
    }

    fun setEnabled(id: MacroId, enabled: Boolean) = viewModelScope.launch {
        macros.setEnabled(id, enabled).onErr()
    }

    fun delete(id: MacroId, name: String) = viewModelScope.launch {
        when (val r = macros.delete(id)) {
            is AppResult.Ok -> {
                executions.audit(AuditKind.MACRO_DELETED, id.value, name)
                _events.emit(MacroListEvent.Deleted(name))
            }
            is AppResult.Err -> _events.emit(MacroListEvent.Error(r.error))
        }
    }

    /** FR-MAC-9: "<name> (copy)", disabled, no schedules, fresh ids. */
    fun duplicate(id: MacroId) = viewModelScope.launch {
        val source = macros.get(id) ?: return@launch _events.emit(MacroListEvent.Error(AppError(ErrorCode.MACRO_NOT_FOUND)))
        val copy: Macro = source.copy(
            id = MacroId.random(),
            name = "${source.name} (copy)".take(com.macroandroid.automation.model.MacroLimits.NAME_MAX),
            enabled = false,
            steps = MacroEditing.freshIds(source.steps),
        )
        when (val r = macros.save(copy)) {
            is AppResult.Ok -> _events.emit(MacroListEvent.Duplicated(copy.name))
            is AppResult.Err -> _events.emit(MacroListEvent.Error(r.error))
        }
    }

    // ---- export / import -------------------------------------------------------------------

    fun requestExport(ids: List<MacroId>, includeSecrets: Boolean) {
        val name = if (ids.size == 1) "macro-${ids.first().value.take(8)}.json" else "macros-${ids.size}.json"
        pendingExport.value = PendingExport(ids, name, includeSecrets)
    }

    fun cancelExport() {
        pendingExport.value = null
    }

    /** Called with the URI from `ACTION_CREATE_DOCUMENT` (null when the user cancelled). */
    fun completeExport(uri: Uri?, appVersionCode: Int) {
        val pending = pendingExport.value ?: return
        pendingExport.value = null
        if (uri == null) return
        viewModelScope.launch {
            busy.value = true
            try {
                val json = when (val r = transfer.exportDocument(pending.ids, pending.includeSecrets, appVersionCode)) {
                    is AppResult.Ok -> r.value
                    is AppResult.Err -> return@launch _events.emit(MacroListEvent.Error(r.error))
                }
                when (val w = transfer.writeTo(uri, json, pending.ids)) {
                    is AppResult.Ok -> _events.emit(MacroListEvent.Exported(pending.ids.size))
                    is AppResult.Err -> _events.emit(MacroListEvent.Error(w.error))
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun previewImport(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            busy.value = true
            try {
                when (val r = transfer.preview(uri)) {
                    is AppResult.Ok -> importPreview.value = r.value
                    is AppResult.Err -> _events.emit(MacroListEvent.Error(r.error))
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun cancelImport() {
        importPreview.value = null
    }

    fun commitImport(choices: Map<MacroId, ImportConflictChoice>) {
        val preview = importPreview.value ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                when (val r = transfer.commit(preview, choices)) {
                    is AppResult.Ok -> {
                        importPreview.value = null
                        _events.emit(MacroListEvent.Imported(r.value))
                    }
                    is AppResult.Err -> _events.emit(MacroListEvent.Error(r.error))
                }
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun <T> AppResult<T>.onErr() {
        if (this is AppResult.Err) _events.emit(MacroListEvent.Error(error))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
