package com.macroandroid.feature.macros.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.ExecutionPolicy
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroDocument
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.SecureValueRef
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.automation.serialization.MacroJson
import com.macroandroid.automation.validation.ValidationIssue
import com.macroandroid.automation.validation.ValidationResult
import com.macroandroid.core.common.contract.MacroRunnerContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.util.Identifiers
import com.macroandroid.core.database.SecureValueStore
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.feature.macros.data.ValidatorFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock

/** Editor state: the working copy plus validation and bookkeeping (FR-MAC-2/3/4). */
data class MacroEditorUiState(
    val macro: Macro? = null,
    val isNew: Boolean = false,
    val dirty: Boolean = false,
    val validation: ValidationResult = ValidationResult.OK,
    val issuesByStep: Map<StepId, List<ValidationIssue>> = emptyMap(),
    val profiles: List<String> = emptyList(),
    val knownTags: List<String> = emptyList(),
    val draftAvailable: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
) {
    val canSave: Boolean get() = macro != null && validation.isValid && !saving
}

sealed interface MacroEditorEvent {
    data class Saved(val id: MacroId) : MacroEditorEvent
    data class Error(val error: AppError) : MacroEditorEvent
    data class StepRemoved(val step: MacroStep, val path: StepPath, val index: Int) : MacroEditorEvent
    data class StepTestStarted(val executionId: String) : MacroEditorEvent
    data object Closed : MacroEditorEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class MacroEditorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val macros: MacroRepository,
    private val validators: ValidatorFactory,
    private val secureValues: SecureValueStore,
    private val executions: ExecutionRepository,
    private val runner: MacroRunnerContract,
    private val clock: Clock,
) : ViewModel() {

    private val requestedId: String? = savedState[ARG_ID]
    val macroId: MacroId = MacroId(
        requestedId ?: savedState.get<String>(KEY_NEW_ID) ?: MacroId.random().value.also { savedState[KEY_NEW_ID] = it },
    )

    private val _state = MutableStateFlow(MacroEditorUiState())
    val uiState: StateFlow<MacroEditorUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<MacroEditorEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MacroEditorEvent> = _events.asSharedFlow()

    private var validateJob: Job? = null
    private var lastSaved: Macro? = null

    init {
        viewModelScope.launch { load() }
        // Persist a draft after the user pauses typing (FR-MAC-2 unsaved-change protection).
        _state.drop(1).debounce(DRAFT_DEBOUNCE_MS).onEach { s ->
            if (s.dirty && s.macro != null) persistDraft(s.macro)
        }.launchIn(viewModelScope)
    }

    private suspend fun load() {
        val restored: Macro? = savedState.get<String>(KEY_WORKING)?.let { decodeWorking(it) }
        val stored = if (requestedId != null) macros.get(macroId) else null
        val draft = macros.draft(macroId)?.macros?.firstOrNull()
        val base = restored ?: stored ?: newMacro()
        lastSaved = stored
        _state.update {
            it.copy(
                macro = base,
                isNew = stored == null,
                dirty = restored != null && restored != stored,
                draftAvailable = restored == null && draft != null && draft != stored,
                profiles = macros.observeProfiles().first(),
                loaded = true,
            )
        }
        revalidate()
    }

    private fun newMacro(): Macro {
        val now = clock.now()
        return Macro(id = macroId, name = "", steps = emptyList(), createdAt = now, updatedAt = now)
    }

    // ---- draft handling ----------------------------------------------------------------------

    fun restoreDraft() = viewModelScope.launch {
        val draft = macros.draft(macroId)?.macros?.firstOrNull() ?: return@launch
        update(markDirty = true) { draft }
        _state.update { it.copy(draftAvailable = false) }
    }

    fun discardDraft() = viewModelScope.launch {
        macros.deleteDraft(macroId)
        _state.update { it.copy(draftAvailable = false) }
    }

    private suspend fun persistDraft(macro: Macro) {
        savedState[KEY_WORKING] = MacroJson.encode(document(macro))
        macros.saveDraft(macroId, document(macro))
    }

    private fun document(m: Macro) = MacroDocument(exportedAt = clock.now(), appVersionCode = 0, macros = listOf(m))

    private fun decodeWorking(json: String): Macro? =
        runCatching { MacroJson.instance.decodeFromString(MacroDocument.serializer(), json).macros.firstOrNull() }.getOrNull()

    // ---- field edits --------------------------------------------------------------------------

    fun setName(v: String) = update { it.copy(name = v.take(MacroLimits.NAME_MAX)) }
    fun setDescription(v: String) = update { it.copy(description = v.take(MacroLimits.DESCRIPTION_MAX)) }
    fun setProfile(v: String) = update { it.copy(profile = v.trim().ifBlank { Macro.DEFAULT_PROFILE }.take(MacroLimits.PROFILE_MAX)) }
    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setPolicy(v: ExecutionPolicy) = update { it.copy(executionPolicy = v) }

    fun addTag(raw: String) = update { m ->
        val tag = Identifiers.normaliseTag(raw)
        if (tag.isEmpty() || tag in m.tags || m.tags.size >= MacroLimits.TAGS_MAX) m else m.copy(tags = m.tags + tag)
    }

    fun removeTag(tag: String) = update { it.copy(tags = it.tags - tag) }

    fun setVariable(name: String, value: VariableValue?) = update { m ->
        val vars = m.variables.toMutableMap()
        if (value == null) vars.remove(name) else vars[name] = value
        m.copy(variables = vars)
    }

    // ---- step edits ---------------------------------------------------------------------------

    fun addStep(action: ActionParameters, path: StepPath, index: Int) = update { m ->
        m.copy(steps = MacroEditing.insert(m.steps, path, index, MacroStep(id = StepId.random(), action = action)))
    }

    fun replaceStep(path: StepPath, step: MacroStep) = update { it.copy(steps = MacroEditing.replace(it.steps, path, step)) }

    fun removeStep(path: StepPath, id: StepId) {
        val m = _state.value.macro ?: return
        val list = listAt(m.steps, path)
        val index = list.indexOfFirst { it.id == id }
        val step = list.getOrNull(index) ?: return
        update { it.copy(steps = MacroEditing.remove(it.steps, path, id)) }
        viewModelScope.launch {
            secureIdsOf(listOf(step)).forEach { runCatching { secureValues.delete(it) } }
            _events.emit(MacroEditorEvent.StepRemoved(step, path, index))
        }
    }

    /** Undo for [removeStep]; secure values are re-created by the user if the step held any. */
    fun reinsertStep(step: MacroStep, path: StepPath, index: Int) =
        update { it.copy(steps = MacroEditing.insert(it.steps, path, index, step)) }

    fun moveStep(path: StepPath, id: StepId, delta: Int) = update { it.copy(steps = MacroEditing.move(it.steps, path, id, delta)) }
    fun duplicateStep(path: StepPath, id: StepId) = update { it.copy(steps = MacroEditing.duplicate(it.steps, path, id)) }
    fun toggleStep(path: StepPath, id: StepId) = update { it.copy(steps = MacroEditing.toggleEnabled(it.steps, path, id)) }

    /** Encrypts a sensitive literal and returns the reference to embed in the step (FR-SEC-1). */
    suspend fun storeSecret(stepId: StepId, param: String, plaintext: String, existing: SecureValueRef?): AppResult<SecureValueRef> {
        val r = secureValues.put(macroId.value, stepId.value, param, plaintext, clock.now().toEpochMilliseconds(), existing?.id)
        return when (r) {
            is AppResult.Ok -> {
                executions.audit(AuditKind.SECURE_VALUE_SET, macroId.value, "$stepId/$param")
                AppResult.ok(SecureValueRef(r.value))
            }
            is AppResult.Err -> AppResult.err(r.error)
        }
    }

    // ---- validation / save --------------------------------------------------------------------

    private fun update(markDirty: Boolean = true, f: (Macro) -> Macro) {
        if (_state.value.macro == null) return
        _state.update { s -> s.macro?.let { m -> s.copy(macro = f(m), dirty = s.dirty || markDirty) } ?: s }
        revalidate()
    }

    private fun revalidate() {
        validateJob?.cancel()
        validateJob = viewModelScope.launch {
            val m = _state.value.macro ?: return@launch
            val validator = validators.snapshot(m.profile, m.id, secureIdsOf(m.allSteps()))
            val result = validator.validate(m)
            _state.update {
                it.copy(validation = result, issuesByStep = result.issues.filter { i -> i.stepId != null }.groupBy { i -> i.stepId!! })
            }
        }
    }

    fun save(onSaved: (MacroId) -> Unit = {}) = viewModelScope.launch {
        val s = _state.value
        val m = s.macro ?: return@launch
        val validator = validators.snapshot(m.profile, m.id, secureIdsOf(m.allSteps()))
        val result = validator.validate(m)
        if (!result.isValid) {
            _state.update { it.copy(validation = result) }
            _events.emit(MacroEditorEvent.Error(AppError(result.errors.first().code, result.errors.first().detail)))
            return@launch
        }
        _state.update { it.copy(saving = true) }
        when (val r = macros.save(m)) {
            is AppResult.Ok -> {
                secureValues.prune(macroId.value, secureIdsOf(r.value.allSteps()))
                macros.deleteDraft(macroId)
                savedState.remove<String>(KEY_WORKING)
                lastSaved = r.value
                _state.update { it.copy(macro = r.value, isNew = false, dirty = false, saving = false, draftAvailable = false) }
                _events.emit(MacroEditorEvent.Saved(r.value.id))
                onSaved(r.value.id)
            }
            is AppResult.Err -> {
                _state.update { it.copy(saving = false) }
                _events.emit(MacroEditorEvent.Error(r.error))
            }
        }
    }

    /** FR-MAC-5: the macro is saved first so the engine runs the exact step the user sees. */
    fun testStep(stepId: StepId) = viewModelScope.launch {
        if (_state.value.dirty || _state.value.isNew) {
            var saved = false
            save { saved = true }.join()
            if (!saved) return@launch
        }
        when (val r = runner.testStep(macroId.value, stepId.value)) {
            is AppResult.Ok -> _events.emit(MacroEditorEvent.StepTestStarted(r.value))
            is AppResult.Err -> _events.emit(MacroEditorEvent.Error(r.error))
        }
    }

    /** Back navigation: keep the draft (already persisted) and close. */
    fun close(discard: Boolean) = viewModelScope.launch {
        if (discard) {
            macros.deleteDraft(macroId)
            savedState.remove<String>(KEY_WORKING)
            if (_state.value.isNew) secureValues.prune(macroId.value, emptyList())
        }
        _events.emit(MacroEditorEvent.Closed)
    }

    private fun listAt(steps: List<MacroStep>, path: StepPath): List<MacroStep> {
        var list = steps
        for (seg in path) {
            val container = list.firstOrNull { it.id == seg.containerId } ?: return emptyList()
            list = MacroEditing.groupsOf(container.action).getOrElse(seg.group) { emptyList() }
        }
        return list
    }

    private fun secureIdsOf(steps: List<MacroStep>): List<String> = buildList {
        steps.forEach { s ->
            val a = s.action
            if (a is ActionParameters.EnterText && a.text is TextValue.Secure) add(a.text.ref.id)
            MacroEditing.groupsOf(a).forEach { addAll(secureIdsOf(it)) }
        }
    }

    companion object {
        const val ARG_ID = "macroId"
        private const val KEY_NEW_ID = "editor.newId"
        private const val KEY_WORKING = "editor.working"
        private const val DRAFT_DEBOUNCE_MS = 1_500L
    }
}
