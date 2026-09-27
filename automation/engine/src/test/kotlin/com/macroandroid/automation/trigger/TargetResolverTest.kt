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
    fun `single point plan taps only that point once, without reaction delay, repeats or its pre-delay`() {
        val targets = listOf(
            TriggerFixtures.point(400, 300),
            TriggerFixtures.point(550, 300, delayBeforeMs = 80, enabled = false),
        )
        val config = TriggerFixtures.config(
            targets = targets,
            mode = ExecutionMode.MULTI_TOUCH,
            repeatCount = 3,
            reactionDelayMs = 250,
        )
        val plan = TargetResolver.resolve(config, PORTRAIT, capable, onlyPoint = targets[1].id)
        val value = checkNotNull(plan.getOrNull())
        assertThat(value.contacts.map { it.index to (it.x.toInt() to it.y.toInt()) }).containsExactly(2 to (550 to 300))
        assertThat(value.contacts.single().delayBeforeMs).isEqualTo(0)
        assertThat(value.mode).isEqualTo(ExecutionMode.SEQUENTIAL)
        assertThat(value.repeatCount).isEqualTo(1)
        assertThat(value.reactionDelayMs).isEqualTo(0)

        val whole = checkNotNull(TargetResolver.resolve(config, PORTRAIT, capable).getOrNull())
        assertThat(whole.reactionDelayMs).isEqualTo(250)
        assertThat(whole.repeatCount).isEqualTo(3)

        val unknown = TargetResolver.resolve(config, PORTRAIT, capable, onlyPoint = TargetPointId("missing"))
        assertThat(unknown.errorOrNull()?.code).isEqualTo(ErrorCode.TRIGGER_NO_TARGETS)
        // A single point can be tested while the configuration as a whole has no enabled target.
        val allOff = TriggerFixtures.config(targets = listOf(TriggerFixtures.point(400, 300, enabled = false)))
        assertThat(TargetResolver.resolve(allOff, PORTRAIT, capable, onlyPoint = allOff.targetPoints[0].id).isOk).isTrue()
    }

    @Test
    fun `reaction delay outside its range is a timing error`() {
        val config = TriggerFixtures.config(reactionDelayMs = TriggerLimits.MAX_REACTION_DELAY_MS + 1)
        assertThat(TriggerValidator.validate(config).map { it.code }).contains(ErrorCode.TRIGGER_TIMING_RANGE)
        assertThat(TriggerValidator.validate(config.copy(reactionDelayMs = 500))).isEmpty()
        assertThat(config.copy(repeatCount = 1).repeatMode).isEqualTo(RepeatMode.ONCE)
        assertThat(config.copy(repeatCount = 2).repeatMode).isEqualTo(RepeatMode.FIXED_COUNT)
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
