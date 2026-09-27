package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.automation.testing.TriggerFixtures.LANDSCAPE
import com.macroandroid.automation.testing.TriggerFixtures.PORTRAIT
import org.junit.Test
import kotlin.math.roundToInt

class TriggerEditSessionTest {
    // Area px (100,700) 200×150 → corner handle at (300,850); points at (400,300) (550,300) (700,450) (500,600).
    private fun session(config: TriggerConfiguration = TriggerFixtures.config()) =
        TriggerEditSession(config, PORTRAIT, handleRadiusPx = 40f, touchSlopPx = 8f)

    private fun TriggerEditSession.drag(fromX: Float, fromY: Float, toX: Float, toY: Float): EditGesture {
        onDown(fromX, fromY)
        onMove((fromX + toX) / 2, (fromY + toY) / 2)
        onMove(toX, toY)
        return onUp(toX, toY)
    }

    @Test
    fun `hit test prefers points, then the resize corner, then the area body`() {
        val s = session()
        assertThat(s.hitTest(405f, 305f)).isEqualTo(EditHandle.Point(TargetPointId("p400-300")))
        assertThat(s.hitTest(295f, 845f)).isEqualTo(EditHandle.AreaResize)
        assertThat(s.hitTest(150f, 750f)).isEqualTo(EditHandle.AreaMove)
        assertThat(s.hitTest(50f, 50f)).isEqualTo(EditHandle.None)
    }

    @Test
    fun `dragging the area moves it in normalised space and leaves display points alone`() {
        val s = session()
        val before = s.config.targetPoints
        val gesture = s.drag(150f, 750f, 250f, 850f)
        assertThat(gesture).isEqualTo(EditGesture.Dragged(EditHandle.AreaMove, changed = true))
        val bounds = s.areaBounds()
        assertThat(bounds.left).isEqualTo(200)
        assertThat(bounds.top).isEqualTo(800)
        assertThat(bounds.width).isEqualTo(200)
        assertThat(bounds.height).isEqualTo(150)
        assertThat(s.config.targetPoints).isEqualTo(before)
        assertThat(s.isDirty).isTrue()
    }

    @Test
    fun `trigger-relative points follow the area, display points do not`() {
        val relative = TriggerFixtures.point(50, 50, space = CoordinateSpace.TRIGGER_RELATIVE, id = "rel")
        val absolute = TriggerFixtures.point(400, 300, id = "abs")
        val s = session(TriggerFixtures.config(targets = listOf(relative, absolute)))
        val beforePx = s.pointPositions().associate { (p, pos) -> p.id.value to (pos.x.roundToInt() to pos.y.roundToInt()) }
        assertThat(beforePx["rel"]).isEqualTo(150 to 750)
        s.drag(150f, 750f, 250f, 850f)
        val afterPx = s.pointPositions().associate { (p, pos) -> p.id.value to (pos.x.roundToInt() to pos.y.roundToInt()) }
        assertThat(afterPx["rel"]).isEqualTo(250 to 850)
        assertThat(afterPx["abs"]).isEqualTo(400 to 300)
    }

    @Test
    fun `resize corner changes size, respects minimum and display bounds`() {
        val s = session()
        s.drag(300f, 850f, 400f, 1000f)
        assertThat(s.areaBounds().width).isEqualTo(300)
        assertThat(s.areaBounds().height).isEqualTo(300)
        // Shrink far past the minimum: clamps instead of inverting.
        s.drag(400f, 1000f, 0f, 0f)
        assertThat(s.config.triggerArea.width).isWithin(1e-9).of(TriggerLimits.MIN_AREA_FRACTION)
        assertThat(s.config.triggerArea.height).isWithin(1e-9).of(TriggerLimits.MIN_AREA_FRACTION)
        // Grow past the display edge: clamps to the display.
        val corner = s.areaBounds()
        s.drag(corner.right.toFloat(), corner.bottom.toFloat(), 5000f, 5000f)
        assertThat(s.config.triggerArea.right).isWithin(1e-9).of(1.0)
        assertThat(s.config.triggerArea.bottom).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `dragging a point moves only that point and clamps to the display`() {
        val s = session()
        s.drag(400f, 300f, 450f, 350f)
        val moved = s.pointPositions().first { it.first.id.value == "p400-300" }.second
        assertThat(moved.x.roundToInt()).isEqualTo(450)
        assertThat(moved.y.roundToInt()).isEqualTo(350)
        assertThat(s.pointPositions().first { it.first.id.value == "p550-300" }.second.x.roundToInt()).isEqualTo(550)
        s.drag(450f, 350f, -500f, -500f)
        val clamped = s.pointPositions().first { it.first.id.value == "p400-300" }.second
        assertThat(clamped.x).isEqualTo(0f)
        assertThat(clamped.y).isEqualTo(0f)
    }

    @Test
    fun `tap on a point selects it, tap on empty space reports a tap, movement under slop is a tap`() {
        val s = session()
        s.onDown(400f, 300f)
        assertThat(s.onUp(400f, 300f)).isEqualTo(EditGesture.PointSelected(TargetPointId("p400-300")))
        assertThat(s.selectedPointId).isEqualTo(TargetPointId("p400-300"))
        s.onDown(50f, 50f)
        s.onMove(53f, 54f) // under the 8 px slop
        assertThat(s.onUp(53f, 54f)).isEqualTo(EditGesture.TapEmpty(53f, 54f))
        assertThat(s.isDirty).isFalse()
    }

    @Test
    fun `add, toggle and delete respect the cap and the selection`() {
        val s = session(TriggerFixtures.config(targets = emptyList()))
        repeat(TriggerLimits.MAX_TARGETS) { i -> assertThat(s.addPointAt(100f + i, 100f)).isTrue() }
        assertThat(s.addPointAt(900f, 900f)).isFalse()
        assertThat(s.config.targetPoints).hasSize(TriggerLimits.MAX_TARGETS)
        assertThat(s.selectedPointId).isEqualTo(s.config.targetPoints.last().id)
        assertThat(s.toggleSelectedEnabled()).isTrue()
        assertThat(s.config.targetPoints.last().enabled).isFalse()
        assertThat(s.deleteSelected()).isTrue()
        assertThat(s.config.targetPoints).hasSize(TriggerLimits.MAX_TARGETS - 1)
        assertThat(s.selectedPointId).isNull()
        assertThat(s.deleteSelected()).isFalse()
    }

    @Test
    fun `cancel keeps applied edits but drops the grab, geometry change re-maps to the new display`() {
        val s = session()
        s.onDown(150f, 750f)
        s.onMove(250f, 850f)
        s.cancelGesture()
        assertThat(s.grabbed).isEqualTo(EditHandle.None)
        assertThat(s.areaBounds().left).isEqualTo(200)
        s.updateGeometry(LANDSCAPE)
        // Same normalised area, now expressed on the landscape display (2400×1080).
        assertThat(s.areaBounds().left).isEqualTo((200.0 / 1080 * 2400).toInt())
        assertThat(s.areaBounds().top).isEqualTo((800.0 / 2400 * 1080).toInt())
    }
}
