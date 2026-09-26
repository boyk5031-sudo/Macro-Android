package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.automation.testing.TriggerFixtures.PORTRAIT
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.error.ErrorCode
import org.junit.Test

class TargetResolverTest {
    private val capable = InjectionCapability(available = true, maxSimultaneousContacts = 10)

    @Test
    fun `resolves enabled targets to pixels with timing`() {
        val plan = TargetResolver.resolve(TriggerFixtures.config(), PORTRAIT, capable).getOrNull()!!
        assertThat(plan.contacts.map { it.index }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(plan.contacts.first().holdMs).isEqualTo(TriggerLimits.DEFAULT_TAP_HOLD_MS)
        assertThat(plan.compatibility).isEqualTo(DisplayCompatibility.EXACT)
        assertThat(plan.estimatedDurationMs).isEqualTo(200)
    }

    @Test
    fun `validation failures win over display problems`() {
        val bad = TriggerFixtures.config(area = TriggerArea(0.5, 0.5, 0.001, 0.001))
        assertThat(TargetResolver.resolve(bad, PORTRAIT, capable).errorOrNull()?.code)
            .isEqualTo(ErrorCode.TRIGGER_AREA_INVALID)
    }

    @Test
    fun `invalid display and missing capability are reported`() {
        val config = TriggerFixtures.config()
        assertThat(TargetResolver.resolve(config, DisplayGeometry(0, 0, 0), capable).errorOrNull()?.code)
            .isEqualTo(ErrorCode.DISPLAY_UNAVAILABLE)
        val noGestures = InjectionCapability(false, 0, ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        assertThat(TargetResolver.resolve(config, PORTRAIT, noGestures).errorOrNull()?.code)
            .isEqualTo(ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
    }

    @Test
    fun `multi-touch beyond the adapter limit is refused`() {
        val config = TriggerFixtures.config(mode = ExecutionMode.MULTI_TOUCH)
        val twoFingers = InjectionCapability(true, 2)
        assertThat(TargetResolver.resolve(config, PORTRAIT, twoFingers).errorOrNull()?.code).isEqualTo(ErrorCode.LIMIT_EXCEEDED)
    }

    @Test
    fun `test runs may ignore the enabled flag but never skip other checks`() {
        val disabled = TriggerFixtures.config(enabled = false)
        assertThat(TargetResolver.resolve(disabled, PORTRAIT, capable, ignoreEnabledFlag = true).isOk).isTrue()
        val disabledEmpty = TriggerFixtures.config(enabled = false, targets = emptyList())
        assertThat(TargetResolver.resolve(disabledEmpty, PORTRAIT, capable, ignoreEnabledFlag = true).errorOrNull()?.code)
            .isEqualTo(ErrorCode.TRIGGER_NO_TARGETS)
    }

    @Test
    fun `validator catches timing ranges, names and package names`() {
        val config = TriggerFixtures.config(cooldownMs = 99_999)
        assertThat(TriggerValidator.validate(config).map { it.code }).contains(ErrorCode.TRIGGER_TIMING_RANGE)
        assertThat(TriggerValidator.validate(config.copy(name = " ")).map { it.code }).contains(ErrorCode.NAME_INVALID)
        assertThat(TriggerValidator.validate(config.copy(packageName = "no dots")).map { it.code })
            .contains(ErrorCode.PACKAGE_NAME_INVALID)
        assertThat(TriggerValidator.validate(TriggerFixtures.config(packageName = "com.example.game"))).isEmpty()
        val tooMany = TriggerFixtures.config(targets = List(11) { TriggerFixtures.point(100 + it, 100, id = "t$it") })
        assertThat(TriggerValidator.validate(tooMany).map { it.code }).contains(ErrorCode.LIMIT_EXCEEDED)
    }
}
