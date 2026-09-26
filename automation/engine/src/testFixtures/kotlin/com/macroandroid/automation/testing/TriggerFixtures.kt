package com.macroandroid.automation.testing

import com.macroandroid.automation.trigger.AuthoredDisplay
import com.macroandroid.automation.trigger.Contact
import com.macroandroid.automation.trigger.CoordinateSpace
import com.macroandroid.automation.trigger.ExecutionMode
import com.macroandroid.automation.trigger.InjectionCapability
import com.macroandroid.automation.trigger.InputInjectionAdapter
import com.macroandroid.automation.trigger.TargetActionType
import com.macroandroid.automation.trigger.TargetPoint
import com.macroandroid.automation.trigger.TargetPointId
import com.macroandroid.automation.trigger.TriggerArea
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.coroutines.delay

/** Records every injected gesture with the virtual time it started; simulates real contact duration via `delay`. */
class FakeInputInjectionAdapter(
    var capability: InjectionCapability = InjectionCapability(available = true, maxSimultaneousContacts = 10),
    private val now: () -> Long = { 0L },
) : InputInjectionAdapter {
    data class Gesture(val startedAt: Long, val contacts: List<Contact>)

    val gestures = mutableListOf<Gesture>()
    var failWith: ErrorCode? = null
    var cancelCalls = 0

    /** Number of contacts currently "down"; must be 0 whenever no gesture is in flight. */
    var pointersDown = 0
        private set

    override fun capability(): InjectionCapability = capability

    override suspend fun inject(contacts: List<Contact>): AppResult<Unit> {
        gestures += Gesture(now(), contacts)
        failWith?.let { return AppResult.err(it) }
        pointersDown += contacts.size
        try {
            delay(contacts.maxOf { it.holdMs })
        } finally {
            pointersDown -= contacts.size
        }
        return AppResult.ok(Unit)
    }

    override suspend fun cancelAll() {
        cancelCalls++
    }

    /** All injected points in dispatch order (flattened), as integer pairs for readable assertions. */
    fun points(): List<Pair<Int, Int>> = gestures.flatMap { g -> g.contacts.map { it.x.toInt() to it.y.toInt() } }
}

object TriggerFixtures {
    /** 1080×2400 portrait phone. */
    val PORTRAIT = DisplayGeometry(widthPx = 1080, heightPx = 2400, densityDpi = 420)
    val LANDSCAPE = DisplayGeometry(widthPx = 2400, heightPx = 1080, densityDpi = 420)

    fun authored(geometry: DisplayGeometry = PORTRAIT) =
        AuthoredDisplay(geometry.widthPx, geometry.heightPx, geometry.orientation)

    fun point(
        xPx: Int,
        yPx: Int,
        geometry: DisplayGeometry = PORTRAIT,
        enabled: Boolean = true,
        delayBeforeMs: Long = 0,
        space: CoordinateSpace = CoordinateSpace.DISPLAY,
        action: TargetActionType = TargetActionType.TAP,
        id: String = "p$xPx-$yPx",
    ) = TargetPoint(
        id = TargetPointId(id),
        x = xPx.toDouble() / geometry.widthPx,
        y = yPx.toDouble() / geometry.heightPx,
        coordinateSpace = space,
        actionType = action,
        delayBeforeMs = delayBeforeMs,
        enabled = enabled,
    )

    /** Trigger at px (100,700) size 200×150 on the portrait display, like the specification example. */
    fun areaPx(x: Int, y: Int, w: Int, h: Int, geometry: DisplayGeometry = PORTRAIT) = TriggerArea(
        x = x.toDouble() / geometry.widthPx,
        y = y.toDouble() / geometry.heightPx,
        width = w.toDouble() / geometry.widthPx,
        height = h.toDouble() / geometry.heightPx,
    )

    fun config(
        targets: List<TargetPoint> = listOf(point(400, 300), point(550, 300), point(700, 450), point(500, 600)),
        area: TriggerArea = areaPx(100, 700, 200, 150),
        mode: ExecutionMode = ExecutionMode.SEQUENTIAL,
        cooldownMs: Long = 100,
        enabled: Boolean = true,
        packageName: String? = null,
        repeatCount: Int = 1,
        id: String = "abc123",
        geometry: DisplayGeometry = PORTRAIT,
    ) = TriggerConfiguration(
        id = TriggerId(id),
        name = "Combo",
        enabled = enabled,
        packageName = packageName,
        triggerArea = area,
        targetPoints = targets,
        executionMode = mode,
        cooldownMs = cooldownMs,
        repeatCount = repeatCount,
        authoredDisplay = authored(geometry),
    )
}
