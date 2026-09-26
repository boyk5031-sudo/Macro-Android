package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.automation.testing.TriggerFixtures.LANDSCAPE
import com.macroandroid.automation.testing.TriggerFixtures.PORTRAIT
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.display.DisplayRect
import org.junit.Test

class CoordinateConverterTest {

    @Test
    fun `area maps to the authored pixels on the same display`() {
        val rect = CoordinateConverter.areaToDisplay(TriggerFixtures.areaPx(100, 700, 200, 150), PORTRAIT)
        assertThat(rect).isEqualTo(DisplayRect(100, 700, 300, 850))
    }

    @Test
    fun `points scale with resolution when the aspect ratio is unchanged`() {
        val point = TriggerFixtures.point(400, 300)
        val doubled = DisplayGeometry(2160, 4800, 560)
        val px = CoordinateConverter.pointToDisplay(point, TriggerFixtures.areaPx(0, 0, 100, 100), doubled)
        assertThat(px.x).isWithin(0.01f).of(800f)
        assertThat(px.y).isWithin(0.01f).of(600f)
        assertThat(CoordinateConverter.compatibility(TriggerFixtures.authored(), doubled)).isEqualTo(DisplayCompatibility.EXACT)
    }

    @Test
    fun `trigger-relative points move with the area, display points do not`() {
        val relative = TriggerFixtures.point(50, 50, space = CoordinateSpace.TRIGGER_RELATIVE)
        val absolute = TriggerFixtures.point(50, 50)
        val areaA = TriggerFixtures.areaPx(100, 700, 200, 150)
        val areaB = TriggerFixtures.areaPx(500, 1000, 200, 150)

        val relA = CoordinateConverter.pointToDisplay(relative, areaA, PORTRAIT)
        val relB = CoordinateConverter.pointToDisplay(relative, areaB, PORTRAIT)
        val absA = CoordinateConverter.pointToDisplay(absolute, areaA, PORTRAIT)
        val absB = CoordinateConverter.pointToDisplay(absolute, areaB, PORTRAIT)

        assertThat(relA.x.toInt() to relA.y.toInt()).isEqualTo(150 to 750)
        assertThat(relB.x.toInt() to relB.y.toInt()).isEqualTo(550 to 1050)
        assertThat(absA).isEqualTo(absB)
    }

    @Test
    fun `orientation change is reported as a mismatch, aspect drift as a warning`() {
        assertThat(CoordinateConverter.compatibility(TriggerFixtures.authored(), LANDSCAPE))
            .isEqualTo(DisplayCompatibility.ORIENTATION_MISMATCH)
        val taller = DisplayGeometry(1080, 2640, 420)
        assertThat(CoordinateConverter.compatibility(TriggerFixtures.authored(), taller))
            .isEqualTo(DisplayCompatibility.ASPECT_DIFFERS)
    }

    @Test
    fun `display to fraction and back is stable`() {
        val (fx, fy) = CoordinateConverter.displayToFraction(700.0, 450.0, PORTRAIT)
        val point = TargetPoint(TargetPointId("p"), fx, fy)
        val px = CoordinateConverter.pointToDisplay(point, TriggerFixtures.areaPx(0, 0, 100, 100), PORTRAIT)
        assertThat(px.x).isWithin(0.001f).of(700f)
        assertThat(px.y).isWithin(0.001f).of(450f)
    }

    @Test
    fun `hit test uses physical pixels of the current display`() {
        val area = TriggerFixtures.areaPx(100, 700, 200, 150)
        assertThat(TriggerHitTester.hit(area, PORTRAIT, 182f, 742f)).isTrue()
        assertThat(TriggerHitTester.hit(area, PORTRAIT, 100f, 700f)).isTrue()
        assertThat(TriggerHitTester.hit(area, PORTRAIT, 300f, 850f)).isFalse() // exclusive edge
        assertThat(TriggerHitTester.hit(area, PORTRAIT, 99f, 742f)).isFalse()
        assertThat(TriggerHitTester.hit(area, PORTRAIT, 182f, 900f)).isFalse()
    }

    @Test
    fun `off-screen area is clamped instead of producing negative windows`() {
        val rect = CoordinateConverter.areaToDisplay(TriggerArea(0.95, 0.95, 0.2, 0.2), PORTRAIT)
        assertThat(rect.right).isEqualTo(PORTRAIT.widthPx)
        assertThat(rect.bottom).isEqualTo(PORTRAIT.heightPx)
        assertThat(rect.width).isAtLeast(0)
    }
}
