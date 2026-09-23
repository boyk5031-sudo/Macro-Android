package com.macroandroid.automation.model

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.engine.MacroBudget
import com.macroandroid.automation.testing.MacroFixtures.log
import com.macroandroid.automation.testing.MacroFixtures.macro
import com.macroandroid.automation.testing.MacroFixtures.step
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class MacroModelTest {

    @Test
    fun `derived properties`() {
        val m = macro(
            step(1, ActionParameters.LaunchApp("a.b")),
            step(2, ActionParameters.Repeat(count = 3, body = listOf(log(3, "x"), log(4, "y")))),
            step(5, ActionParameters.If(Condition.AppInstalled("c.d"), then = listOf(log(6, "t")), `else` = listOf(log(7, "e"), log(8, "f")))),
            step(9, ActionParameters.Parallel(children = listOf(log(10, "p"), log(11, "q")))),
        )
        assertThat(m.expandedLeafCount).isEqualTo(1 + 6 + 2 + 2)
        assertThat(m.nestingDepth).isEqualTo(1)
        assertThat(m.requiresAccessibility).isFalse()
        assertThat(m.concurrencyClass).isEqualTo(ConcurrencyClass.UI)
        assertThat(m.targetPackages).containsExactly("a.b")
        assertThat(m.allSteps()).hasSize(11)
    }

    @Test
    fun `retry backoff`() {
        val fixed = RetryPolicy(maxAttempts = 3, backoff = Backoff.FIXED, initialDelay = 2.seconds)
        assertThat(fixed.delayBefore(2)).isEqualTo(2.seconds)
        assertThat(fixed.delayBefore(3)).isEqualTo(2.seconds)
        val exp = RetryPolicy(maxAttempts = 5, backoff = Backoff.EXPONENTIAL, initialDelay = 1.seconds, maxDelay = 5.seconds)
        assertThat(exp.delayBefore(2)).isEqualTo(1.seconds)
        assertThat(exp.delayBefore(3)).isEqualTo(2.seconds)
        assertThat(exp.delayBefore(4)).isEqualTo(4.seconds)
        assertThat(exp.delayBefore(5)).isEqualTo(5.seconds)
    }

    @Test
    fun `static budget`() {
        val m = macro(
            step(1, ActionParameters.Wait(10.seconds)),
            step(2, ActionParameters.ClickNode(NodeSelector(text = "x")), retry = RetryPolicy(maxAttempts = 2, initialDelay = 1.seconds)),
        )
        // wait 10s ×1 + click 10s ×2 + 1s backoff
        assertThat(MacroBudget.staticTimeBudget(m)).isEqualTo(31.seconds)
        assertThat(MacroBudget.stepTimeout(m, m.steps[1])).isEqualTo(10.seconds)
    }

    @Test
    fun `runtime secrets never print`() {
        val s = RuntimeValue.Secret("hunter2")
        assertThat(s.toString()).doesNotContain("hunter2")
        assertThat(s.asDisplayString()).doesNotContain("hunter2")
        assertThat(s.reveal()).isEqualTo("hunter2")
    }
}
