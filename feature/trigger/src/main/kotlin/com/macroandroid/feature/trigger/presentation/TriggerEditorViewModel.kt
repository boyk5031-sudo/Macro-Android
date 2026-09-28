package com.macroandroid.feature.trigger.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.automation.port.AppLauncher
import com.macroandroid.automation.trigger.AuthoredDisplay
import com.macroandroid.automation.trigger.CoordinateConverter
import com.macroandroid.automation.trigger.CoordinateSpace
import com.macroandroid.automation.trigger.DisplayCompatibility
import com.macroandroid.automation.trigger.ExecutionMode
import com.macroandroid.automation.trigger.TargetActionType
import com.macroandroid.automation.trigger.TargetPoint
import com.macroandroid.automation.trigger.TargetPointId
import com.macroandroid.automation.trigger.TriggerArea
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.automation.trigger.TriggerJson
import com.macroandroid.automation.trigger.TriggerLimits
import com.macroandroid.automation.trigger.TriggerValidator
import com.macroandroid.core.common.contract.TriggerAccessStatus
import com.macroandroid.core.common.contract.TriggerRuntimeContract
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.repository.TriggerRepository
import com.macroandroid.feature.trigger.data.LauncherApps
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Progress of the in-editor test run (§17). */
sealed interface TestPhase {
    data object Idle : TestPhase

    /** "Test in game": seconds left for the user to switch to the game before the taps are injected. */
    data class Countdown(val secondsLeft: Int) : TestPhase

    /** "Test here": a scrim covers the editor and shows where the injected taps actually land. */
    data object RunningHere : TestPhase
    data object RunningInGame : TestPhase
}

/** What a test run executes: the whole configuration or one target point (§26). */
sealed interface TestScope {
    data object Whole : TestScope
    data class SinglePoint(val id: TargetPointId) : TestScope
}

data class TriggerEditorUiState(
    val config: TriggerConfiguration? = null,
    val isNew: Boolean = true,
    val selectedPointId: TargetPointId? = null,
    /** Display the editor currently runs on. */
    val geometry: DisplayGeometry? = null,
    val compatibility: DisplayCompatibility? = null,
    val errors: List<AppError> = emptyList(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val access: TriggerAccessStatus? = null,
    val testPhase: TestPhase = TestPhase.Idle,
    /** Window-relative positions of taps received by the test scrim, for the on-screen ripple. */
    val receivedTaps: List<Pair<Float, Float>> = emptyList(),
    val apps: List<LauncherApps.Entry> = emptyList(),
    val loaded: Boolean = false,
    /** Set by a refused Save: from then on every validation error is shown, including the initially blank name. */
    val showAllErrors: Boolean = false,
) {
    val selectedPoint: TargetPoint? get() = config?.targetPoints?.firstOrNull { it.id == selectedPointId }

    /**
     * Save is offered as soon as the configuration exists. A greyed-out button on a brand-new trigger (blank name,
     * no target yet) gave no hint why; instead [TriggerEditorViewModel.save] refuses with the first error and
     * turns [showAllErrors] on so the form highlights what is missing.
     */
    val canSave: Boolean get() = config != null && !saving
    val isValid: Boolean get() = config != null && errors.isEmpty()
    val nameInvalid: Boolean get() = errors.any { it.code == ErrorCode.NAME_INVALID } && (dirty || showAllErrors)
    val canTest: Boolean
        get() = config != null && errors.none { it.code != ErrorCode.NAME_INVALID } &&
            access?.ready == true && testPhase == TestPhase.Idle

    /** A single point can be tested even while the configuration as a whole has no enabled target yet. */
    val canTestPoint: Boolean
        get() = config != null && access?.ready == true && testPhase == TestPhase.Idle &&
            errors.none { it.code != ErrorCode.NAME_INVALID && it.code != ErrorCode.TRIGGER_NO_TARGETS }

    val canAddPoint: Boolean get() = (config?.targetPoints?.size ?: 0) < TriggerLimits.MAX_TARGETS

    /** On-screen edit works on the saved configuration; unsaved changes would be silently lost otherwise. */
    val canEditOnScreen: Boolean
        get() = config != null && !isNew && !dirty && access?.ready == true && testPhase == TestPhase.Idle
}

sealed interface TriggerEditorEvent {
    data class Saved(val id: TriggerId) : TriggerEditorEvent
    data class Error(val error: AppError) : TriggerEditorEvent
    data object TestSucceeded : TriggerEditorEvent

    /** Edit mode was handed to the runtime; the screen should leave (the overlay appears over the game). */
    data class EditOnScreenStarted(val packageName: String?) : TriggerEditorEvent
}

/**
 * Editor state machine. All coordinates in this class are normalised display fractions; the screen converts
 * canvas pixels to fractions before calling in, and the numeric fields work in authored-display pixels.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // one entry point per editor control; each is a one-line state transition
class TriggerEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: TriggerRepository,
    private val runtime: TriggerRuntimeContract,
    private val launcherApps: LauncherApps,
    private val appLauncher: AppLauncher,
) : ViewModel() {
    private val idArg: String? = savedState[ARG_TRIGGER_ID]

    private val _state = MutableStateFlow(TriggerEditorUiState(isNew = idArg == null))
    val uiState: StateFlow<TriggerEditorUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<TriggerEditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TriggerEditorEvent> = _events.asSharedFlow()

    private var testJob: Job? = null

    init {
        viewModelScope.launch { runtime.access.collect { a -> _state.update { it.copy(access = a) } } }
        viewModelScope.launch { _state.update { it.copy(apps = launcherApps.list()) } }
        val id = idArg
        if (id != null) {
            viewModelScope.launch {
                val stored = repository.get(TriggerId(id))
                if (stored == null) {
                    _events.tryEmit(TriggerEditorEvent.Error(AppError(ErrorCode.TRIGGER_NOT_FOUND)))
                } else {
                    _state.update { it.copy(config = stored.config, loaded = true).revalidated() }
                }
            }
        }
    }

    // ---- display -------------------------------------------------------------------------------------------

    /** The screen calls this with the activity's display on first composition and after every rotation. */
    fun onGeometry(geometry: DisplayGeometry) {
        _state.update { s ->
            val config = s.config ?: if (s.isNew) newConfiguration(geometry) else null
            s.copy(geometry = geometry, config = config, loaded = s.loaded || config != null).revalidated()
        }
    }

    // ---- configuration fields -----------------------------------------------------------------------------

    fun setName(name: String) = edit { it.copy(name = name.take(TriggerLimits.MAX_NAME_LENGTH)) }
    fun setPackage(packageName: String?) = edit { it.copy(packageName = packageName?.trim()?.ifEmpty { null }) }
    fun setEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }
    fun setMode(mode: ExecutionMode) = edit { it.copy(executionMode = mode) }
    fun setReactionDelay(ms: Long) = edit { it.copy(reactionDelayMs = ms.coerceIn(0, TriggerLimits.MAX_REACTION_DELAY_MS)) }
    fun setCooldown(ms: Long) =
        edit { it.copy(cooldownMs = ms.coerceIn(TriggerLimits.MIN_COOLDOWN_MS, TriggerLimits.MAX_COOLDOWN_MS)) }
    fun setRepeatCount(count: Int) = edit { it.copy(repeatCount = count.coerceIn(1, TriggerLimits.MAX_REPEAT)) }
    fun setRepeatDelay(ms: Long) = edit { it.copy(repeatDelayMs = ms.coerceIn(0, TriggerLimits.MAX_DELAY_MS)) }
    fun setIndicatorOverride(show: Boolean?) = edit { it.copy(showIndicatorInGameplay = show) }

    // ---- trigger area -------------------------------------------------------------------------------------

    /** Drag: deltas in display fractions. DISPLAY-space points stay where they are (§5). */
    fun moveArea(dx: Double, dy: Double) = edit { c ->
        val a = c.triggerArea
        val nx = (a.x + dx).coerceIn(0.0, (1.0 - a.width).coerceAtLeast(0.0))
        val ny = (a.y + dy).coerceIn(0.0, (1.0 - a.height).coerceAtLeast(0.0))
        c.copy(triggerArea = a.copy(x = nx, y = ny))
    }

    fun resizeArea(dw: Double, dh: Double) = edit { c ->
        val a = c.triggerArea
        val nw = (a.width + dw).coerceIn(TriggerLimits.MIN_AREA_FRACTION, 1.0 - a.x)
        val nh = (a.height + dh).coerceIn(TriggerLimits.MIN_AREA_FRACTION, 1.0 - a.y)
        c.copy(triggerArea = a.copy(width = nw, height = nh))
    }

    /** Numeric fields in authored-display pixels; out-of-range values are reported by validation, not clamped. */
    fun setAreaPx(xPx: Int?, yPx: Int?, wPx: Int?, hPx: Int?) = edit { c ->
        val d = c.authoredDisplay
        val a = c.triggerArea
        c.copy(
            triggerArea = TriggerArea(
                x = xPx?.let { it.toDouble() / d.widthPx } ?: a.x,
                y = yPx?.let { it.toDouble() / d.heightPx } ?: a.y,
                width = wPx?.let { it.toDouble() / d.widthPx } ?: a.width,
                height = hPx?.let { it.toDouble() / d.heightPx } ?: a.height,
            ),
        )
    }

    // ---- target points ------------------------------------------------------------------------------------

    fun selectPoint(id: TargetPointId?) = _state.update { it.copy(selectedPointId = id) }

    /** Adds a point at a display fraction (canvas tap) or, when null, at the display centre. */
    fun addPoint(fx: Double? = null, fy: Double? = null) {
        val id = TargetPointId.random()
        edit { c ->
            if (c.targetPoints.size >= TriggerLimits.MAX_TARGETS) return@edit c
            val point = TargetPoint(id = id, x = (fx ?: CENTER).coerceIn(0.0, 1.0), y = (fy ?: CENTER).coerceIn(0.0, 1.0))
            c.copy(targetPoints = c.targetPoints + point)
        }
        if (_state.value.config?.targetPoints?.any { it.id == id } == true) selectPoint(id)
    }

    fun movePoint(id: TargetPointId, dx: Double, dy: Double) = editPoint(id) { p, area ->
        val (fx, fy) = CoordinateConverter.toDisplayFraction(p, area)
        val nfx = (fx + dx).coerceIn(0.0, 1.0)
        val nfy = (fy + dy).coerceIn(0.0, 1.0)
        val (nx, ny) = CoordinateConverter.fractionToSpace(nfx, nfy, p.coordinateSpace, area)
        p.copy(x = nx, y = ny)
    }

    /** Numeric X/Y in authored-display pixels, always expressed in DISPLAY space for the user. */
    fun setPointPx(id: TargetPointId, xPx: Int?, yPx: Int?) = edit { c ->
        val d = c.authoredDisplay
        c.updatePoint(id) { p ->
            val (fx, fy) = CoordinateConverter.toDisplayFraction(p, c.triggerArea)
            val nfx = xPx?.let { it.toDouble() / d.widthPx } ?: fx
            val nfy = yPx?.let { it.toDouble() / d.heightPx } ?: fy
            val (nx, ny) = CoordinateConverter.fractionToSpace(nfx, nfy, p.coordinateSpace, c.triggerArea)
            p.copy(x = nx, y = ny)
        }
    }

    fun deletePoint(id: TargetPointId) {
        edit { c -> c.copy(targetPoints = c.targetPoints.filterNot { it.id == id }) }
        _state.update { if (it.selectedPointId == id) it.copy(selectedPointId = null) else it }
    }

    /** Moves the point one position up (`-1`) or down (`+1`); order is the sequential execution order. */
    fun reorderPoint(id: TargetPointId, direction: Int) = edit { c ->
        val list = c.targetPoints.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + direction
        if (from < 0 || to !in list.indices) return@edit c
        list.add(to, list.removeAt(from))
        c.copy(targetPoints = list)
    }

    fun setPointEnabled(id: TargetPointId, enabled: Boolean) = editPoint(id) { p, _ -> p.copy(enabled = enabled) }
    fun setPointAction(id: TargetPointId, action: TargetActionType) =
        editPoint(id) { p, _ -> p.copy(actionType = action, holdMs = null) }
    fun setPointDelay(id: TargetPointId, ms: Long) =
        editPoint(id) { p, _ -> p.copy(delayBeforeMs = ms.coerceIn(0, TriggerLimits.MAX_DELAY_MS)) }
    fun setPointHold(id: TargetPointId, ms: Long?) =
        editPoint(id) { p, _ -> p.copy(holdMs = ms?.coerceIn(TriggerLimits.MIN_HOLD_MS, TriggerLimits.MAX_HOLD_MS)) }

    /** Switches coordinate space while keeping the point where it is on screen. */
    fun setPointSpace(id: TargetPointId, space: CoordinateSpace) = editPoint(id) { p, area ->
        if (p.coordinateSpace == space) return@editPoint p
        val (fx, fy) = CoordinateConverter.toDisplayFraction(p, area)
        val (nx, ny) = CoordinateConverter.fractionToSpace(fx, fy, space, area)
        p.copy(x = nx, y = ny, coordinateSpace = space)
    }

    // ---- save / test --------------------------------------------------------------------------------------

    fun save() {
        val current = _state.value
        val config = current.config ?: return
        if (!current.canSave) return
        val firstError = current.errors.firstOrNull()
        if (firstError != null) {
            _state.update { it.copy(showAllErrors = true) }
            _events.tryEmit(TriggerEditorEvent.Error(firstError))
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val r = repository.save(config)) {
                is AppResult.Ok -> {
                    _state.update { it.copy(saving = false, dirty = false, isNew = false) }
                    _events.tryEmit(TriggerEditorEvent.Saved(r.value.id))
                }
                is AppResult.Err -> {
                    _state.update { it.copy(saving = false) }
                    _events.tryEmit(TriggerEditorEvent.Error(r.error))
                }
            }
        }
    }

    /** Injects the configured taps now, under a scrim that shows where they land (nothing else can be pressed). */
    fun testHere() = startTest(TestPhase.RunningHere, countdownSeconds = 0)

    /** Gives the user [IN_GAME_COUNTDOWN_S] seconds to switch to the game, then injects the configured taps. */
    fun testInGame() = startTest(TestPhase.RunningInGame, countdownSeconds = IN_GAME_COUNTDOWN_S)

    /** Taps ONE target under the scrim – once, immediately, ignoring its enabled flag, reaction delay and repeats. */
    fun testPoint(id: TargetPointId) = startTest(TestPhase.RunningHere, countdownSeconds = 0, scope = TestScope.SinglePoint(id))

    /**
     * Hands the saved configuration to the runtime's on-screen editor and, for a bound configuration, brings the
     * game to the front so the editor appears over it. Unbound configurations: the editor appears over whatever
     * app the user opens next.
     */
    fun editOnScreen() {
        val state = _state.value
        val config = state.config ?: return
        if (!state.canEditOnScreen) return
        viewModelScope.launch {
            when (val r = runtime.startOverlayEdit(config.id.value)) {
                is AppResult.Err -> _events.tryEmit(TriggerEditorEvent.Error(r.error))
                is AppResult.Ok -> {
                    val pkg = config.packageName
                    if (pkg != null) {
                        val launched = appLauncher.launch(pkg)
                        if (launched is AppResult.Err) {
                            runtime.stopOverlayEdit()
                            _events.tryEmit(TriggerEditorEvent.Error(launched.error))
                            return@launch
                        }
                    }
                    _events.tryEmit(TriggerEditorEvent.EditOnScreenStarted(pkg))
                }
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        testJob = null
        _state.update { it.copy(testPhase = TestPhase.Idle, receivedTaps = emptyList()) }
    }

    /** Called by the test scrim for every pointer-down it receives. */
    fun onScrimTap(x: Float, y: Float) = _state.update { it.copy(receivedTaps = it.receivedTaps + (x to y)) }

    private fun startTest(running: TestPhase, countdownSeconds: Int, scope: TestScope = TestScope.Whole) {
        val config = _state.value.config ?: return
        val allowed = if (scope is TestScope.SinglePoint) _state.value.canTestPoint else _state.value.canTest
        if (!allowed) return
        testJob?.cancel()
        testJob = viewModelScope.launch {
            try {
                for (s in countdownSeconds downTo 1) {
                    _state.update { it.copy(testPhase = TestPhase.Countdown(s)) }
                    delay(ONE_SECOND_MS)
                }
                _state.update { it.copy(testPhase = running, receivedTaps = emptyList()) }
                if (running == TestPhase.RunningHere) delay(SCRIM_SETTLE_MS)
                val json = TriggerJson.encodeConfiguration(config)
                val result = when (scope) {
                    TestScope.Whole -> runtime.test(json)
                    is TestScope.SinglePoint -> runtime.testTarget(json, scope.id.value)
                }
                when (val r = result) {
                    is AppResult.Ok -> _events.tryEmit(TriggerEditorEvent.TestSucceeded)
                    is AppResult.Err -> _events.tryEmit(TriggerEditorEvent.Error(r.error))
                }
                if (running == TestPhase.RunningHere) delay(SCRIM_LINGER_MS)
            } finally {
                _state.update { it.copy(testPhase = TestPhase.Idle, receivedTaps = emptyList()) }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun edit(transform: (TriggerConfiguration) -> TriggerConfiguration) = _state.update { s ->
        val current = s.config ?: return@update s
        val next = transform(current)
        if (next == current) s else s.copy(config = next, dirty = true).revalidated()
    }

    private fun editPoint(id: TargetPointId, transform: (TargetPoint, TriggerArea) -> TargetPoint) =
        edit { c -> c.updatePoint(id) { p -> transform(p, c.triggerArea) } }

    private fun TriggerConfiguration.updatePoint(id: TargetPointId, transform: (TargetPoint) -> TargetPoint) =
        copy(targetPoints = targetPoints.map { if (it.id == id) transform(it) else it })

    private fun TriggerEditorUiState.revalidated(): TriggerEditorUiState {
        val c = config ?: return copy(errors = emptyList(), compatibility = null)
        val compat = geometry?.let { CoordinateConverter.compatibility(c.authoredDisplay, it) }
        return copy(errors = TriggerValidator.validate(c), compatibility = compat)
    }

    private fun newConfiguration(geometry: DisplayGeometry) = TriggerConfiguration(
        id = TriggerId.random(),
        name = "",
        triggerArea = TriggerArea(x = DEFAULT_AREA_X, y = DEFAULT_AREA_Y, width = DEFAULT_AREA_W, height = DEFAULT_AREA_H),
        authoredDisplay = AuthoredDisplay(geometry.widthPx, geometry.heightPx, geometry.orientation),
    )

    companion object {
        const val ARG_TRIGGER_ID = "triggerId"
        const val IN_GAME_COUNTDOWN_S = 5
        private const val ONE_SECOND_MS = 1_000L
        private const val SCRIM_SETTLE_MS = 400L
        private const val SCRIM_LINGER_MS = 1_500L
        private const val CENTER = 0.5
        private const val DEFAULT_AREA_X = 0.1
        private const val DEFAULT_AREA_Y = 0.72
        private const val DEFAULT_AREA_W = 0.3
        private const val DEFAULT_AREA_H = 0.12

        /** Pixel value of a fraction on the authored display, for the numeric fields. */
        fun px(fraction: Double, sizePx: Int): Int = (fraction * sizePx).roundToInt()
    }
}
