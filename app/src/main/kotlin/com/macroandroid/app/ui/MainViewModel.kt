package com.macroandroid.app.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.android.service.MacroRunner
import com.macroandroid.automation.engine.ExecutionOrigin
import com.macroandroid.automation.model.MacroId
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.datastore.ThemeMode
import com.macroandroid.core.datastore.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val onboardingCompleted: Boolean,
)

/** A shortcut asked to run a macro that needs confirmation (Settings → "Confirm before running"). */
data class PendingRun(val macroId: MacroId, val macroName: String)

sealed interface MainNavEvent {
    data class OpenExecution(val executionId: String) : MainNavEvent
    data object NewMacro : MainNavEvent
    data object OpenRuns : MainNavEvent
    data class Error(val error: AppError) : MainNavEvent
}

/** Routes launcher/shortcut/notification intents (doc 03 journeys J4, J7) and exposes app-wide preferences. */
@HiltViewModel
class MainViewModel @Inject constructor(
    prefs: UserPreferencesRepository,
    private val macros: MacroRepository,
    private val runner: MacroRunner,
    private val audit: AuditContract,
) : ViewModel() {
    val uiState: StateFlow<MainUiState?> = prefs.preferences
        .map { MainUiState(it.themeMode, it.dynamicColor, it.onboardingCompleted) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val confirmBeforeRun = prefs.preferences.map { it.confirmBeforeRun }

    private val _navEvents = MutableSharedFlow<MainNavEvent>(extraBufferCapacity = 4)
    val navEvents: SharedFlow<MainNavEvent> = _navEvents.asSharedFlow()

    private val _pendingRun = MutableStateFlow<PendingRun?>(null)
    val pendingRun: StateFlow<PendingRun?> = _pendingRun.asStateFlow()

    private var handledIntentId: Int? = null

    fun onIntent(intent: Intent?, fromSavedState: Boolean) {
        intent ?: return
        // Activity recreation redelivers the same Intent object; do not run a shortcut macro twice.
        val id = System.identityHashCode(intent)
        if (fromSavedState || handledIntentId == id) return
        handledIntentId = id
        val data = intent.data ?: return
        when (val route = IntentRoutes.parse(data)) {
            is IntentRoutes.Route.Execution -> _navEvents.tryEmit(MainNavEvent.OpenExecution(route.id))
            is IntentRoutes.Route.RunMacro -> viewModelScope.launch { onRunMacroRequested(MacroId(route.id)) }
            IntentRoutes.Route.NewMacro -> _navEvents.tryEmit(MainNavEvent.NewMacro)
            IntentRoutes.Route.Runs -> _navEvents.tryEmit(MainNavEvent.OpenRuns)
            null -> Unit
        }
    }

    private suspend fun onRunMacroRequested(id: MacroId) {
        val macro = macros.get(id)
        if (macro == null) {
            audit.record(AuditKind.OTHER.name, "shortcut.unknownMacro", id.value)
            return
        }
        audit.record(AuditKind.SHORTCUT_LAUNCHED.name, id.value)
        val needsConfirm = macro.requiresAccessibility && confirmBeforeRun.first()
        if (needsConfirm) {
            _pendingRun.value = PendingRun(id, macro.name)
        } else {
            runNow(id)
        }
    }

    fun confirmPendingRun() {
        val p = _pendingRun.value ?: return
        _pendingRun.value = null
        viewModelScope.launch { runNow(p.macroId) }
    }

    fun dismissPendingRun() {
        _pendingRun.value = null
    }

    private suspend fun runNow(id: MacroId) {
        when (val r = runner.run(id, ExecutionOrigin.Shortcut)) {
            is AppResult.Ok -> _navEvents.emit(MainNavEvent.OpenExecution(r.value.id.value))
            is AppResult.Err -> _navEvents.emit(MainNavEvent.Error(r.error))
        }
    }
}

/** Internal URI scheme. Kept in one place so shortcuts, notifications and the activity agree. */
object IntentRoutes {
    const val SCHEME = "macroandroid"

    sealed interface Route {
        data class Execution(val id: String) : Route
        data class RunMacro(val id: String) : Route
        data object NewMacro : Route
        data object Runs : Route
    }

    fun runMacroUri(macroId: String): Uri = Uri.parse("$SCHEME://macro/$macroId/run")
    fun executionUri(executionId: String): Uri = Uri.parse("$SCHEME://execution/$executionId")

    fun parse(uri: Uri): Route? {
        if (uri.scheme != SCHEME) return null
        val segments = uri.pathSegments
        return when (uri.host) {
            "execution" -> segments.firstOrNull()?.takeIf { it.isNotBlank() }?.let(Route::Execution)
            "macro" -> when {
                segments.size == 2 && segments[1] == "run" -> Route.RunMacro(segments[0])
                segments.size == 1 && segments[0] == "new" -> Route.NewMacro
                else -> null
            }
            "runs" -> Route.Runs
            else -> null
        }
    }
}
