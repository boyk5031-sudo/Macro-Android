package com.macroandroid.automation.android.trigger

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.macroandroid.automation.android.accessibility.AccessibilityServiceRegistry
import com.macroandroid.automation.trigger.CoordinateConverter
import com.macroandroid.automation.trigger.ExecutionReason
import com.macroandroid.automation.trigger.RuntimeDecision
import com.macroandroid.automation.trigger.RuntimeInput
import com.macroandroid.automation.trigger.TargetResolver
import com.macroandroid.automation.trigger.TouchOutcome
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.automation.trigger.TriggerController
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.automation.trigger.TriggerJson
import com.macroandroid.automation.trigger.TriggerPlanExecutor
import com.macroandroid.automation.trigger.TriggerRuntimePolicy
import com.macroandroid.automation.trigger.TriggerTouch
import com.macroandroid.core.common.contract.TriggerAccessStatus
import com.macroandroid.core.common.contract.TriggerRuntimeContract
import com.macroandroid.core.common.contract.TriggerRuntimeStatus
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.coroutines.ApplicationScope
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.flatMap
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.repository.TriggerRepository
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.core.platform.DisplayGeometryReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Owns the lifecycle of Trigger Areas (§14): starts when the accessibility service binds, stops when it unbinds.
 * Every input that can change what should be on screen is a flow; [TriggerRuntimePolicy] turns the combined
 * snapshot into a decision and [reconcile] applies it – adding, moving or removing overlays and controllers.
 *
 * What it never does: show anything while no configuration is armed, inject anything without a press on the
 * overlay, or keep running after the service is unbound.
 */
@Singleton
class TriggerRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AccessibilityServiceRegistry,
    private val repository: TriggerRepository,
    private val prefs: UserPreferencesRepository,
    private val accessChecker: TriggerAccessChecker,
    private val adapter: AccessibilityInputInjectionAdapter,
    private val overlays: TriggerOverlayController,
    private val dispatchers: AppDispatchers,
    @ApplicationScope private val appScope: CoroutineScope,
    private val logger: Logger,
    private val clock: Clock,
) : TriggerRuntimeContract {

    private class Active(val controller: TriggerController, var config: TriggerConfiguration)

    private val _status = MutableStateFlow(TriggerRuntimeStatus())
    override val status: StateFlow<TriggerRuntimeStatus> = _status.asStateFlow()
    override val access: Flow<TriggerAccessStatus> get() = accessChecker.status

    private val manuallyArmed = MutableStateFlow<TriggerId?>(null)
    private val screenInteractive = MutableStateFlow(readScreenInteractive())
    private val active = LinkedHashMap<TriggerId, Active>()
    private var runtimeScope: CoroutineScope? = null
    private var loop: Job? = null
    private var receiver: BroadcastReceiver? = null

    /** Called from `onServiceConnected`; idempotent. */
    fun start() {
        if (loop?.isActive == true) return
        val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        runtimeScope = scope
        registerScreenReceiver()
        screenInteractive.value = readScreenInteractive()
        loop = scope.launch {
            inputs().collectLatest { input ->
                delay(SETTLE_MS) // absorb bursts of window events (app switch, IME, rotation)
                reconcile(input)
            }
        }
        logger.i(TAG, "started")
    }

    /** Called from `onUnbind`/`onDestroy`; tears everything down even if `start` was never called. */
    fun stop() {
        loop?.cancel()
        loop = null
        unregisterScreenReceiver()
        val scope = runtimeScope
        runtimeScope = null
        appScope.launch(dispatchers.main) {
            active.values.forEach { it.controller.disarm() }
            active.clear()
            overlays.hideAll()
            scope?.cancel()
            _status.update { it.copy(visibleTriggerIds = emptySet(), blockedReason = null, executing = false) }
        }
        logger.i(TAG, "stopped")
    }

    override fun refreshAccess() = accessChecker.refresh()

    override suspend fun arm(triggerId: String): AppResult<Unit> {
        val stored = repository.get(TriggerId(triggerId)) ?: return AppResult.err(ErrorCode.TRIGGER_NOT_FOUND)
        if (stored.config.packageName != null) {
            return AppResult.err(ErrorCode.INVARIANT_VIOLATION, detail = "package-bound triggers arm automatically")
        }
        if (!stored.config.enabled) return AppResult.err(ErrorCode.TRIGGER_DISABLED)
        manuallyArmed.value = stored.id
        _status.update { it.copy(manuallyArmedId = triggerId) }
        return AppResult.ok(Unit)
    }

    override suspend fun disarm() {
        manuallyArmed.value = null
        _status.update { it.copy(manuallyArmedId = null) }
    }

    override suspend fun test(configurationJson: String): AppResult<Unit> {
        val current = accessChecker.current()
        if (!current.ready) return AppResult.err(ErrorCode.GESTURE_DISPATCH_UNAVAILABLE, detail = current.missing.joinToString())
        val geometry = DisplayGeometryReader.read(context) ?: return AppResult.err(ErrorCode.DISPLAY_UNAVAILABLE)
        return TriggerJson.decodeConfiguration(configurationJson)
            .flatMap { config -> TargetResolver.resolve(config, geometry, adapter.capability(), ignoreEnabledFlag = true) }
            .flatMap { plan ->
                logger.d(TAG, "TEST ${plan.triggerId}: ${plan.contacts.size} targets, mode ${plan.mode}")
                _status.update { it.copy(executing = true) }
                try {
                    withContext(dispatchers.default) { TriggerPlanExecutor.execute(plan, adapter, logger, ExecutionReason.TEST) }
                } finally {
                    _status.update { it.copy(executing = false) }
                }
            }
    }

    // ---- inputs -------------------------------------------------------------------------------------------

    private data class Snapshot(
        val configs: List<TriggerConfiguration>,
        val foreground: String?,
        val armed: TriggerId?,
        val accessReady: Boolean,
        val interactive: Boolean,
    )

    private fun inputs(): Flow<Pair<RuntimeInput, Boolean>> {
        val base = combine(
            repository.observeAll().map { rows -> rows.map { it.config } },
            registry.foregroundPackage,
            manuallyArmed,
            accessChecker.status.map { it.ready },
            screenInteractive,
        ) { configs, foreground, armed, ready, interactive -> Snapshot(configs, foreground, armed, ready, interactive) }
        return combine(base, registry.displayChanges, prefs.preferences.map { it.showTriggerIndicator }) { s, _, indicator ->
            RuntimeInput(
                configurations = s.configs,
                foregroundPackage = s.foreground,
                ownPackage = context.packageName,
                manuallyArmedId = s.armed,
                accessReady = s.accessReady,
                screenInteractive = s.interactive,
                geometry = currentGeometry(),
            ) to indicator
        }
    }

    private fun currentGeometry(): DisplayGeometry? {
        val service = registry.service.value
        return DisplayGeometryReader.read(service ?: context)
    }

    // ---- reconcile ----------------------------------------------------------------------------------------

    private suspend fun reconcile(pair: Pair<RuntimeInput, Boolean>) {
        val (input, globalIndicator) = pair
        val decision = TriggerRuntimePolicy.decide(input)
        val service = registry.service.value
        val geometry = input.geometry
        if (service == null || geometry == null || decision.visible.isEmpty()) {
            removeAll()
            publish(decision)
            return
        }
        val wanted = decision.visible.associateBy { it.id }
        active.keys.filterNot { it in wanted }.forEach { remove(it) }
        var overlayFailed = false
        decision.visible.forEach { config ->
            val entry = active.getOrPut(config.id) {
                Active(
                    controller = TriggerController(config, geometry, adapter, checkNotNull(runtimeScope), logger, ::onExecuted),
                    config = config,
                )
            }
            entry.config = config
            entry.controller.config = config
            entry.controller.geometry = geometry
            val bounds = CoordinateConverter.areaToDisplay(config.triggerArea, geometry)
            val indicator = config.showIndicatorInGameplay ?: globalIndicator
            val shown = overlays.show(service, config.id, bounds, config.name, indicator) { touch -> onTouch(config.id, touch) }
            if (!shown) overlayFailed = true
        }
        publish(if (overlayFailed) decision.copy(blockedReason = ErrorCode.TRIGGER_OVERLAY_FAILED) else decision)
    }

    private fun onTouch(id: TriggerId, touch: TriggerTouch) {
        val entry = active[id] ?: return
        when (val outcome = entry.controller.onTouch(touch)) {
            is TouchOutcome.Activated -> {
                overlays.flash(id)
                _status.update {
                    it.copy(
                        lastActivationAtMillis = clock.now().toEpochMilliseconds(),
                        activationCount = it.activationCount + 1,
                        executing = true,
                    )
                }
            }
            is TouchOutcome.Rejected -> logger.d(TAG, "press on $id rejected: ${outcome.reason}")
            TouchOutcome.Ignored -> Unit
        }
    }

    private fun onExecuted(id: TriggerId, result: AppResult<Unit>) {
        _status.update { it.copy(executing = false) }
        if (result is AppResult.Err) logger.w(TAG, "trigger $id failed: ${result.error.code}")
    }

    private suspend fun remove(id: TriggerId) {
        overlays.hide(id)
        active.remove(id)?.controller?.disarm()
    }

    private suspend fun removeAll() {
        active.keys.toList().forEach { remove(it) }
    }

    private fun publish(decision: RuntimeDecision) {
        _status.update {
            it.copy(
                visibleTriggerIds = overlays.visibleIds.map(TriggerId::value).toSet(),
                foregroundPackage = registry.foregroundPackage.value,
                blockedReason = decision.blockedReason,
            )
        }
    }

    // ---- screen state -------------------------------------------------------------------------------------

    private fun readScreenInteractive(): Boolean {
        val interactive = context.getSystemService<PowerManager>()?.isInteractive != false
        val locked = context.getSystemService<KeyguardManager>()?.isKeyguardLocked == true
        return interactive && !locked
    }

    private fun registerScreenReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                screenInteractive.value = readScreenInteractive()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun unregisterScreenReceiver() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private companion object {
        const val TAG = "TriggerRuntime"
        const val SETTLE_MS = 120L
    }
}
