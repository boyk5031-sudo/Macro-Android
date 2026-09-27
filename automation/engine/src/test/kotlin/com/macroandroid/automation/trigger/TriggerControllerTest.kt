package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.FakeInputInjectionAdapter
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.automation.testing.TriggerFixtures.LANDSCAPE
import com.macroandroid.automation.testing.TriggerFixtures.PORTRAIT
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TriggerControllerTest {

    private class Harness(scope: TestScope, config: TriggerConfiguration) {
        val adapter = FakeInputInjectionAdapter(now = { scope.currentTime })
        val results = mutableListOf<AppResult<Unit>>()
        val controller = TriggerController(config, PORTRAIT, adapter, scope, Logger.Noop) { _, r -> results += r }
        fun down(x: Int, y: Int, at: Long) = controller.onTouch(TriggerTouch(TouchKind.DOWN, x.toFloat(), y.toFloat(), at))
        fun up(at: Long) = controller.onTouch(TriggerTouch(TouchKind.UP, 0f, 0f, at))
    }

    @Test
    fun `touch inside the area fires all enabled targets in order, touch position is never a target`() = runTest {
        val h = Harness(this, TriggerFixtures.config())
        val outcome = h.down(182, 742, at = 0)
        assertThat(outcome).isInstanceOf(TouchOutcome.Activated::class.java)
        advanceUntilIdle()
        assertThat(h.adapter.points()).containsExactly(400 to 300, 550 to 300, 700 to 450, 500 to 600).inOrder()
        assertThat(h.adapter.points()).doesNotContain(182 to 742)
        assertThat(h.results).containsExactly(AppResult.ok(Unit))
        assertThat(h.adapter.pointersDown).isEqualTo(0)
    }

    @Test
    fun `any position inside the area yields identical targets`() = runTest {
        val a = Harness(this, TriggerFixtures.config())
        val b = Harness(this, TriggerFixtures.config())
        a.down(100, 700, at = 0)
        b.down(299, 849, at = 0)
        advanceUntilIdle()
        assertThat(a.adapter.points()).isEqualTo(b.adapter.points())
    }

    @Test
    fun `touch outside the area does nothing`() = runTest {
        val h = Harness(this, TriggerFixtures.config())
        assertThat(h.down(50, 50, at = 0)).isEqualTo(TouchOutcome.Ignored)
        assertThat(h.down(300, 850, at = 0)).isEqualTo(TouchOutcome.Ignored)
        advanceUntilIdle()
        assertThat(h.adapter.gestures).isEmpty()
    }

    @Test
    fun `sequential delays are respected`() = runTest {
        val targets = listOf(
            TriggerFixtures.point(400, 300),
            TriggerFixtures.point(550, 300, delayBeforeMs = 50),
            TriggerFixtures.point(700, 450, delayBeforeMs = 50),
            TriggerFixtures.point(500, 600, delayBeforeMs = 100),
        )
        val h = Harness(this, TriggerFixtures.config(targets = targets))
        h.down(150, 750, at = 0)
        advanceUntilIdle()
        // Each tap holds 50 ms (default), so starts are 0, 50+50, 100+50+50, 200+100+50.
        assertThat(h.adapter.gestures.map { it.startedAt }).containsExactly(0L, 100L, 200L, 350L).inOrder()
    }

    @Test
    fun `multi-touch sends all targets as one gesture`() = runTest {
        val h = Harness(this, TriggerFixtures.config(mode = ExecutionMode.MULTI_TOUCH))
        h.down(150, 750, at = 0)
        runCurrent()
        assertThat(h.adapter.gestures).hasSize(1)
        assertThat(h.adapter.gestures.single().contacts).hasSize(4)
        assertThat(h.adapter.pointersDown).isEqualTo(4)
        advanceUntilIdle()
        assertThat(h.adapter.pointersDown).isEqualTo(0)
    }

    @Test
    fun `cooldown and held pointer prevent duplicate activation`() = runTest {
        val h = Harness(this, TriggerFixtures.config(cooldownMs = 100))
        assertThat(h.down(150, 750, at = 0)).isInstanceOf(TouchOutcome.Activated::class.java)
        assertThat(h.down(150, 750, at = 10)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_BUSY))
        advanceUntilIdle()
        h.up(at = 30)
        assertThat(h.down(150, 750, at = 40)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_COOLDOWN))
        h.up(at = 50)
        assertThat(h.down(150, 750, at = 100)).isInstanceOf(TouchOutcome.Activated::class.java)
        advanceUntilIdle()
        assertThat(h.adapter.gestures).hasSize(8)
    }

    @Test
    fun `a press during execution is rejected as busy, not queued`() = runTest {
        val h = Harness(this, TriggerFixtures.config(cooldownMs = 0))
        h.down(150, 750, at = 0)
        runCurrent()
        h.up(at = 1)
        assertThat(h.down(150, 750, at = 2)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_BUSY))
        advanceUntilIdle()
        assertThat(h.adapter.gestures).hasSize(4)
    }

    @Test
    fun `disabled trigger and disabled targets`() = runTest {
        val disabled = Harness(this, TriggerFixtures.config(enabled = false))
        assertThat(disabled.down(150, 750, at = 0)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_DISABLED))

        val targets = listOf(
            TriggerFixtures.point(400, 300),
            TriggerFixtures.point(550, 300, enabled = false),
            TriggerFixtures.point(700, 450),
        )
        val partial = Harness(this, TriggerFixtures.config(targets = targets))
        partial.down(150, 750, at = 0)
        advanceUntilIdle()
        assertThat(partial.adapter.points()).containsExactly(400 to 300, 700 to 450).inOrder()
    }

    @Test
    fun `empty target list and off-screen point are rejected without crashing`() = runTest {
        val empty = Harness(this, TriggerFixtures.config(targets = emptyList()))
        assertThat(empty.down(150, 750, at = 0)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_NO_TARGETS))

        val outside = Harness(this, TriggerFixtures.config(targets = listOf(TriggerFixtures.point(1200, 300))))
        assertThat(outside.down(150, 750, at = 0)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_COORDINATE_INVALID))
        advanceUntilIdle()
        assertThat(outside.adapter.gestures).isEmpty()
    }

    @Test
    fun `orientation change suspends the trigger and rotation back restores it`() = runTest {
        val h = Harness(this, TriggerFixtures.config())
        h.controller.geometry = LANDSCAPE
        assertThat(h.down(150, 750, at = 0)).isEqualTo(TouchOutcome.Ignored) // area no longer covers that pixel
        // Even a touch inside the re-projected area must not fire: the resolver refuses the wrong orientation.
        assertThat(h.down(300, 350, at = 0)).isEqualTo(TouchOutcome.Rejected(ErrorCode.DISPLAY_ORIENTATION_MISMATCH))
        h.up(at = 1)
        h.controller.geometry = PORTRAIT
        assertThat(h.down(150, 750, at = 200)).isInstanceOf(TouchOutcome.Activated::class.java)
    }

    @Test
    fun `screen scaling keeps targets at the same relative place`() = runTest {
        val h = Harness(this, TriggerFixtures.config())
        h.controller.geometry = PORTRAIT.copy(widthPx = 1440, heightPx = 3200, densityDpi = 560)
        h.down(200, 1000, at = 0) // area now spans 133..400 × 933..1133
        advanceUntilIdle()
        assertThat(h.adapter.points()).containsExactly(533 to 400, 733 to 400, 933 to 600, 666 to 800).inOrder()
    }

    @Test
    fun `injection failure is reported and leaves no pointer down`() = runTest {
        val h = Harness(this, TriggerFixtures.config())
        h.adapter.failWith = ErrorCode.GESTURE_DISPATCH_FAILED
        h.down(150, 750, at = 0)
        advanceUntilIdle()
        assertThat(h.results.single().errorOrNull()?.code).isEqualTo(ErrorCode.GESTURE_DISPATCH_FAILED)
        assertThat(h.adapter.gestures).hasSize(1) // stops at the first failure
        assertThat(h.adapter.pointersDown).isEqualTo(0)
    }

    @Test
    fun `disarm cancels execution and asks the adapter to lift contacts`() = runTest {
        val h = Harness(this, TriggerFixtures.config(repeatCount = 5))
        h.down(150, 750, at = 0)
        advanceTimeBy(60)
        h.controller.disarm()
        advanceUntilIdle()
        assertThat(h.controller.isExecuting).isFalse()
        assertThat(h.adapter.cancelCalls).isEqualTo(1)
        assertThat(h.adapter.pointersDown).isEqualTo(0)
        assertThat(h.results).isEmpty()
    }

    @Test
    fun `repeat count runs the sequence again with the repeat delay`() = runTest {
        val h = Harness(this, TriggerFixtures.config(targets = listOf(TriggerFixtures.point(400, 300)), repeatCount = 3))
        h.down(150, 750, at = 0)
        advanceUntilIdle()
        assertThat(h.adapter.gestures.map { it.startedAt }).containsExactly(0L, 150L, 300L).inOrder()
    }

    @Test
    fun `reaction delay postpones the first contact and repeats keep their own interval`() = runTest {
        val targets = listOf(TriggerFixtures.point(400, 300), TriggerFixtures.point(550, 300, delayBeforeMs = 20))
        val h = Harness(this, TriggerFixtures.config(targets = targets, reactionDelayMs = 200, repeatCount = 2))
        h.down(150, 750, at = 0)
        runCurrent()
        assertThat(h.adapter.gestures).isEmpty() // nothing injected before the reaction delay elapsed
        advanceTimeBy(199)
        assertThat(h.adapter.gestures).isEmpty()
        advanceUntilIdle()
        // run 1: 200, 200+50+20 = 270; repeat delay 100 after the hold ends at 320 → run 2: 420, 490
        assertThat(h.adapter.gestures.map { it.startedAt }).containsExactly(200L, 270L, 420L, 490L).inOrder()
        assertThat(h.adapter.points()).containsExactly(400 to 300, 550 to 300, 400 to 300, 550 to 300).inOrder()
    }

    @Test
    fun `state machine walks idle, executing, waiting for release, cooldown, idle`() = runTest {
        val h = Harness(this, TriggerFixtures.config(targets = listOf(TriggerFixtures.point(400, 300)), cooldownMs = 500))
        assertThat(h.controller.state(0)).isEqualTo(TriggerState.IDLE)
        h.down(150, 750, at = 0)
        runCurrent()
        assertThat(h.controller.state(0)).isEqualTo(TriggerState.EXECUTING)
        advanceUntilIdle() // the 50 ms tap is done, finger still down
        assertThat(h.controller.state(60)).isEqualTo(TriggerState.WAITING_FOR_RELEASE)
        assertThat(h.down(150, 750, at = 60)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_BUSY))
        h.up(at = 100)
        assertThat(h.controller.state(100)).isEqualTo(TriggerState.COOLDOWN)
        assertThat(h.down(150, 750, at = 100)).isEqualTo(TouchOutcome.Rejected(ErrorCode.TRIGGER_COOLDOWN))
        h.up(at = 120)
        assertThat(h.controller.state(500)).isEqualTo(TriggerState.IDLE)
        assertThat(h.down(150, 750, at = 500)).isInstanceOf(TouchOutcome.Activated::class.java)
    }

    @Test
    fun `disarm during the reaction delay injects nothing`() = runTest {
        val h = Harness(this, TriggerFixtures.config(reactionDelayMs = 500))
        h.down(150, 750, at = 0)
        advanceTimeBy(100)
        h.controller.disarm()
        advanceUntilIdle()
        assertThat(h.adapter.gestures).isEmpty()
        assertThat(h.controller.state(100)).isEqualTo(TriggerState.IDLE)
    }

    @Test
    fun `missing injection capability is surfaced as the adapter reason`() = runTest {
        val h = Harness(this, TriggerFixtures.config())
        h.adapter.capability = InjectionCapability(false, 0, ErrorCode.A11Y_SERVICE_DISCONNECTED)
        assertThat(h.down(150, 750, at = 0)).isEqualTo(TouchOutcome.Rejected(ErrorCode.A11Y_SERVICE_DISCONNECTED))
    }
}
