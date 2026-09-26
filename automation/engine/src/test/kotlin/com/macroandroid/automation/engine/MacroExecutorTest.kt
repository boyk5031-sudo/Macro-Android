package com.macroandroid.automation.engine

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.ExecutionPolicy
import com.macroandroid.automation.model.Expression
import com.macroandroid.automation.model.FailureBehavior
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.RetryPolicy
import com.macroandroid.automation.model.SecureValueRef
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.automation.port.EngineConfig
import com.macroandroid.automation.port.GateResult
import com.macroandroid.automation.testing.FakeAccessibilityGateway
import com.macroandroid.automation.testing.FakeAppLauncher
import com.macroandroid.automation.testing.FakeMacroSource
import com.macroandroid.automation.testing.FakePreconditionGate
import com.macroandroid.automation.testing.FakeSecureValueResolver
import com.macroandroid.automation.testing.InMemoryExecutionStore
import com.macroandroid.automation.testing.MacroFixtures
import com.macroandroid.automation.testing.MacroFixtures.literal
import com.macroandroid.automation.testing.MacroFixtures.log
import com.macroandroid.automation.testing.MacroFixtures.macro
import com.macroandroid.automation.testing.MacroFixtures.step
import com.macroandroid.automation.testing.RecordingHooks
import com.macroandroid.automation.testing.fakePorts
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MacroExecutorTest {

    private class Harness(scope: TestScope, config: EngineConfig = EngineConfig()) {
        val source = FakeMacroSource()
        val store = InMemoryExecutionStore()
        val secure = FakeSecureValueResolver()
        val gate = FakePreconditionGate()
        val a11y = FakeAccessibilityGateway()
        val launcher = FakeAppLauncher("com.android.settings")
        val hooks = RecordingHooks()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val clock = object : Clock {
            override fun now(): Instant = MacroFixtures.T0 + scope.testScheduler.currentTime.milliseconds
        }
        val executor = MacroExecutor(
            ports = fakePorts(source, store, secure, gate, a11y, launcher, hooks = hooks),
            config = config,
            clock = clock,
            dispatcher = dispatcher,
        )

        fun add(m: Macro) = apply { source.macros[m.id] = m }
        suspend fun run(m: Macro, request: String = "req-${m.id}") =
            executor.enqueue(RunRequest(m.id, ExecutionOrigin.Manual, request))
    }

    @Test
    fun `sequential macro completes and persists every step`() = runTest {
        val h = Harness(this)
        val m = macro(log(1, "a"), step(2, ActionParameters.Wait(2.seconds)), log(3, "c"))
        h.add(m)
        val handle = (h.run(m) as AppResult.Ok).value
        val outcome = handle.await()

        assertThat(outcome.state).isEqualTo(ExecutionState.COMPLETED)
        assertThat(outcome.completedSteps).isEqualTo(3)
        assertThat(h.store.statesOf(handle.id)).containsExactly(
            ExecutionState.QUEUED, ExecutionState.PREPARING, ExecutionState.RUNNING, ExecutionState.COMPLETED,
        ).inOrder()
        assertThat(h.store.attempts.map { it.state }).containsExactly(StepState.COMPLETED, StepState.COMPLETED, StepState.COMPLETED)
        assertThat(h.store.logs.map { it.message }).containsAtLeast("a", "c")
        assertThat(h.store.records[handle.id]!!.endedAt).isEqualTo(MacroFixtures.T0 + 2.seconds)
        assertThat(h.hooks.events).containsExactly("running:${handle.id}", "terminal:COMPLETED").inOrder()
        assertThat(h.executor.activeCount.value).isEqualTo(0)
    }

    @Test
    fun `step timeout then retry with backoff then abort`() = runTest {
        val h = Harness(this)
        h.a11y.activePackageName = "x"
        // No node → waitForNode times out each attempt (timeout 1s), retry twice with 500 ms fixed backoff.
        val m = macro(
            step(
                1,
                ActionParameters.WaitForNode(NodeSelector(text = "missing")),
                timeout = 1.seconds,
                retry = RetryPolicy(maxAttempts = 3, initialDelay = 500.milliseconds),
            ),
            log(2, "never"),
        )
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()

        assertThat(outcome.state).isEqualTo(ExecutionState.FAILED)
        assertThat(outcome.error?.code).isEqualTo(ErrorCode.NODE_NOT_FOUND)
        assertThat(outcome.retryTotal).isEqualTo(2)
        assertThat(h.store.attempts.map { it.attempt }).containsExactly(1, 2, 3).inOrder()
        assertThat(h.store.attempts.all { it.state == StepState.FAILED }).isTrue()
        assertThat(h.store.logs.none { it.message == "never" }).isTrue()
        // 3 × ~1s + 2 × 0.5s
        assertThat(currentTime).isAtLeast(3_000)
        assertThat(currentTime).isAtMost(4_500)
    }

    @Test
    fun `hard timeout of a hanging action is reported as STEP_TIMEOUT`() = runTest {
        val h = Harness(this)
        h.a11y.activePackageName = "x"
        h.a11y.node(text = "Go")
        h.a11y.clickLatency = 30.seconds
        val m = macro(step(1, ActionParameters.ClickNode(NodeSelector(text = "Go")), timeout = 2.seconds))
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.error?.code).isEqualTo(ErrorCode.STEP_TIMEOUT)
        assertThat(h.store.attempts.single().state).isEqualTo(StepState.TIMED_OUT)
        assertThat(currentTime).isEqualTo(2_000)
    }

    @Test
    fun `onFailure continue and jump`() = runTest {
        val h = Harness(this)
        val m = macro(
            step(1, ActionParameters.LaunchApp("not.installed"), onFailure = FailureBehavior.Continue),
            step(2, ActionParameters.LaunchApp("also.missing"), onFailure = FailureBehavior.JumpToLabel("end")),
            log(3, "skipped"),
            log(4, "end").copy(label = "end"),
        )
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.COMPLETED)
        val messages = h.store.logs.map { it.message }
        assertThat(messages).contains("end")
        assertThat(messages).doesNotContain("skipped")
        assertThat(h.store.attempts.map { it.errorCode }).containsExactly(ErrorCode.APP_NOT_INSTALLED, ErrorCode.APP_NOT_INSTALLED, null)
    }

    @Test
    fun `stop action ends run with success or failure`() = runTest {
        val h = Harness(this)
        val ok = macro(step(1, ActionParameters.Stop(success = true)), log(2, "no"), id = MacroFixtures.macroId(1))
        val bad = macro(step(1, ActionParameters.Stop(success = false, message = "bail")), id = MacroFixtures.macroId(2))
        h.add(ok).add(bad)
        assertThat((h.run(ok) as AppResult.Ok).value.await().state).isEqualTo(ExecutionState.COMPLETED)
        val r = (h.run(bad) as AppResult.Ok).value.await()
        assertThat(r.state).isEqualTo(ExecutionState.FAILED)
        assertThat(r.error?.code).isEqualTo(ErrorCode.STOPPED_BY_MACRO)
        assertThat(r.error?.detail).isEqualTo("bail")
    }

    @Test
    fun `variables conditions and repeat`() = runTest {
        val h = Harness(this)
        val m = macro(
            step(1, ActionParameters.SetVariable("n", VariableValue.Int64(0))),
            step(
                2,
                ActionParameters.Repeat(
                    whileCondition = Condition.VarCompare("n", com.macroandroid.automation.model.CompareOp.LT, 3),
                    maxIterations = 10,
                    body = listOf(step(3, ActionParameters.SetVariable("n", expression = Expression.Increment(1)))),
                ),
            ),
            step(
                4,
                ActionParameters.If(
                    Condition.VarEquals("n", VariableValue.Int64(3)),
                    then = listOf(step(5, ActionParameters.Log(message = TextValue.Template("n is {{n}}")))),
                    `else` = listOf(log(6, "wrong")),
                ),
            ),
        )
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.COMPLETED)
        assertThat(h.store.logs.map { it.message }).contains("n is 3")
        assertThat(h.store.logs.map { it.message }).doesNotContain("wrong")
        assertThat(outcome.completedSteps).isEqualTo(1 + 3 + 1)
    }

    @Test
    fun `secure values are resolved once and never logged`() = runTest {
        val h = Harness(this)
        h.secure["s1"] = "hunter2"
        h.a11y.activePackageName = "x"
        h.a11y.node(viewId = "x:id/pw", editable = true, clickable = false)
        val m = macro(
            step(1, ActionParameters.EnterText(NodeSelector(viewId = "x:id/pw"), TextValue.Secure(SecureValueRef("s1")), sensitive = true)),
            step(2, ActionParameters.SetVariable("copy", VariableValue.Secure(SecureValueRef("s1")))),
            step(3, ActionParameters.Log(message = TextValue.Var("copy"))),
        )
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.COMPLETED)
        assertThat(h.a11y.actions).contains("setText:n0:hunter2")
        val everything = h.store.logs.joinToString { it.message } + h.store.attempts.joinToString { it.outputSummary.orEmpty() }
        assertThat(everything).doesNotContain("hunter2")
    }

    @Test
    fun `missing secure value fails at PREPARING`() = runTest {
        val h = Harness(this)
        val m = macro(step(1, ActionParameters.EnterText(null, TextValue.Secure(SecureValueRef("nope")), sensitive = true)))
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.FAILED)
        assertThat(outcome.error?.code).isEqualTo(ErrorCode.SECURE_VALUE_UNAVAILABLE)
        assertThat(h.store.attempts).isEmpty()
    }

    @Test
    fun `password nodes are refused`() = runTest {
        val h = Harness(this)
        h.a11y.node(viewId = "x:id/pw", editable = true, password = true)
        val m = macro(step(1, ActionParameters.EnterText(NodeSelector(viewId = "x:id/pw"), literal("x"))))
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.error?.code).isEqualTo(ErrorCode.NODE_IS_PASSWORD)
        assertThat(h.a11y.actions).isEmpty()
    }

    @Test
    fun `user cancel mid-step persists CANCELLED and releases resources`() = runTest {
        val h = Harness(this)
        val m = macro(step(1, ActionParameters.Wait(1.minutes)), log(2, "no"))
        h.add(m)
        val handle = (h.run(m) as AppResult.Ok).value
        advanceTimeBy(5.seconds)
        handle.cancel()
        val outcome = handle.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.CANCELLED)
        assertThat(outcome.error?.code).isEqualTo(ErrorCode.CANCELLED_BY_USER)
        assertThat(h.store.attempts.single().state).isEqualTo(StepState.CANCELLED)
        assertThat(h.store.records[handle.id]!!.state).isEqualTo(ExecutionState.CANCELLED)
        advanceUntilIdle()
        assertThat(h.executor.activeCount.value).isEqualTo(0)
        // Slot and lock were released: another run completes normally.
        val again = macro(log(1, "ok"), id = MacroFixtures.macroId(9))
        h.add(again)
        assertThat((h.run(again) as AppResult.Ok).value.await().state).isEqualTo(ExecutionState.COMPLETED)
    }

    @Test
    fun `total timeout yields MACRO_TIMEOUT`() = runTest {
        val h = Harness(this)
        val m = macro(
            step(1, ActionParameters.Wait(5.minutes)),
            policy = ExecutionPolicy(totalTimeout = 1.minutes),
        )
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.FAILED)
        assertThat(outcome.error?.code).isEqualTo(ErrorCode.MACRO_TIMEOUT)
        assertThat(currentTime).isEqualTo(60_000)
    }

    @Test
    fun `UI steps are serialized across executions by the UI lock`() = runTest {
        val h = Harness(this)
        h.a11y.activePackageName = "x"
        h.a11y.node(text = "Go")
        h.a11y.clickLatency = 1.seconds
        val m1 = macro(step(1, ActionParameters.ClickNode(NodeSelector(text = "Go"))), id = MacroFixtures.macroId(1))
        val m2 = macro(step(1, ActionParameters.ClickNode(NodeSelector(text = "Go"))), id = MacroFixtures.macroId(2))
        h.add(m1).add(m2)
        val h1 = (h.run(m1) as AppResult.Ok).value
        val h2 = (h.run(m2) as AppResult.Ok).value
        h1.await()
        h2.await()
        // Two 1s clicks that cannot overlap → ≥ 2s of virtual time.
        assertThat(currentTime).isAtLeast(2_000)
        val starts = h.store.attempts.map { it.startedAt }
        val ends = h.store.attempts.map { it.endedAt!! }
        assertThat(starts.max() >= ends.min()).isTrue()
    }

    @Test
    fun `background-safe steps of two executions run concurrently`() = runTest {
        val h = Harness(this)
        val m1 = macro(step(1, ActionParameters.Wait(10.seconds)), id = MacroFixtures.macroId(1))
        val m2 = macro(step(1, ActionParameters.Wait(10.seconds)), id = MacroFixtures.macroId(2))
        h.add(m1).add(m2)
        val a = (h.run(m1) as AppResult.Ok).value
        val b = (h.run(m2) as AppResult.Ok).value
        a.await()
        b.await()
        assertThat(currentTime).isEqualTo(10_000)
    }

    @Test
    fun `parallel block runs children concurrently and fails fast`() = runTest {
        val h = Harness(this)
        val m = macro(
            step(
                1,
                ActionParameters.Parallel(
                    children = listOf(
                        step(2, ActionParameters.Wait(3.seconds)),
                        step(3, ActionParameters.Wait(3.seconds)),
                        step(4, ActionParameters.Wait(3.seconds)),
                    ),
                ),
            ),
        )
        h.add(m)
        assertThat((h.run(m) as AppResult.Ok).value.await().state).isEqualTo(ExecutionState.COMPLETED)
        assertThat(currentTime).isEqualTo(3_000)

        val failing = macro(
            step(
                1,
                ActionParameters.Parallel(
                    children = listOf(
                        step(2, ActionParameters.Wait(30.seconds)),
                        step(3, ActionParameters.LaunchApp("missing.app")),
                    ),
                ),
            ),
            id = MacroFixtures.macroId(2),
        )
        h.add(failing)
        val before = currentTime
        val r = (h.run(failing) as AppResult.Ok).value.await()
        assertThat(r.state).isEqualTo(ExecutionState.FAILED)
        assertThat(r.error?.code).isEqualTo(ErrorCode.APP_NOT_INSTALLED)
        assertThat(currentTime - before).isLessThan(30_000)
    }

    @Test
    fun `blocked when gate fails then resumes when it passes`() = runTest {
        val h = Harness(this)
        h.gate.state.value = GateResult.Blocked(ErrorCode.PRECONDITION_SCREEN_LOCKED)
        h.a11y.node(text = "Go")
        val m = macro(step(1, ActionParameters.ClickNode(NodeSelector(text = "Go"))), log(2, "after"))
        h.add(m)
        val handle = (h.run(m) as AppResult.Ok).value
        advanceTimeBy(2.seconds)
        runCurrent()
        assertThat(h.store.records[handle.id]!!.state).isEqualTo(ExecutionState.BLOCKED)
        assertThat(h.store.records[handle.id]!!.blockedReason?.code).isEqualTo(ErrorCode.PRECONDITION_SCREEN_LOCKED)
        assertThat(h.hooks.events).contains("blocked:PRECONDITION_SCREEN_LOCKED")
        // Slot was released while blocked: a background macro can run.
        val bg = macro(log(1, "bg"), id = MacroFixtures.macroId(5))
        h.add(bg)
        assertThat((h.run(bg) as AppResult.Ok).value.await().state).isEqualTo(ExecutionState.COMPLETED)

        h.gate.state.value = GateResult.Pass
        val outcome = handle.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.COMPLETED)
        assertThat(h.a11y.actions).containsExactly("click:Go")
        assertThat(h.store.statesOf(handle.id)).containsExactly(
            ExecutionState.QUEUED, ExecutionState.PREPARING, ExecutionState.RUNNING, ExecutionState.BLOCKED,
            ExecutionState.RUNNING, ExecutionState.COMPLETED,
        ).inOrder()
    }

    @Test
    fun `blocked timeout cancels the run`() = runTest {
        val h = Harness(this)
        h.gate.state.value = GateResult.Blocked(ErrorCode.A11Y_SERVICE_DISCONNECTED)
        val m = macro(
            step(1, ActionParameters.GlobalAction(com.macroandroid.automation.model.GlobalActionKind.BACK)),
            policy = ExecutionPolicy(blockedTimeout = 1.minutes),
        )
        h.add(m)
        val outcome = (h.run(m) as AppResult.Ok).value.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.CANCELLED)
        assertThat(outcome.error?.code).isEqualTo(ErrorCode.BLOCKED_TIMEOUT)
        assertThat(currentTime).isEqualTo(60_000)
    }

    @Test
    fun `pause between steps and resume`() = runTest {
        val h = Harness(this)
        val m = macro(step(1, ActionParameters.Wait(1.seconds)), step(2, ActionParameters.Wait(1.seconds)), log(3, "done"))
        h.add(m)
        val handle = (h.run(m) as AppResult.Ok).value
        handle.pause()
        advanceTimeBy(1_500.milliseconds)
        runCurrent()
        assertThat(h.store.records[handle.id]!!.state).isEqualTo(ExecutionState.PAUSED)
        advanceTimeBy(10.seconds)
        assertThat(h.store.records[handle.id]!!.state).isEqualTo(ExecutionState.PAUSED)
        handle.resume()
        val outcome = handle.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.COMPLETED)
        assertThat(h.store.statesOf(handle.id)).contains(ExecutionState.PAUSED)
    }

    @Test
    fun `policy rejections`() = runTest {
        val h = Harness(this, EngineConfig(queueCapacity = 1, maxConcurrentExecutions = 1))
        val disabled = macro(log(1, "x"), enabled = false, id = MacroFixtures.macroId(1))
        h.add(disabled)
        val r1 = h.run(disabled)
        assertThat(r1.errorOrNull()?.code).isEqualTo(ErrorCode.MACRO_DISABLED)
        assertThat(h.store.records.values.single().state).isEqualTo(ExecutionState.REJECTED)

        val slow = macro(step(1, ActionParameters.Wait(1.minutes)), id = MacroFixtures.macroId(2))
        h.add(slow)
        val first = (h.run(slow) as AppResult.Ok).value
        assertThat(h.run(slow, "req-again").errorOrNull()?.code).isEqualTo(ErrorCode.CONCURRENT_SELF_NOT_ALLOWED)
        val other = macro(log(1, "y"), id = MacroFixtures.macroId(3))
        h.add(other)
        assertThat(h.run(other).errorOrNull()?.code).isEqualTo(ErrorCode.QUEUE_FULL)
        // Duplicate runRequestId returns the same active handle.
        assertThat((h.run(slow) as AppResult.Ok).value.id).isEqualTo(first.id)
        first.cancel()
        first.await()
    }

    @Test
    fun `rate limit per macro`() = runTest {
        val h = Harness(this, EngineConfig(perMacroStartsPerMinute = 2))
        val m = macro(log(1, "x"), policy = ExecutionPolicy(allowConcurrentSelf = true))
        h.add(m)
        (h.run(m, "a") as AppResult.Ok).value.await()
        (h.run(m, "b") as AppResult.Ok).value.await()
        assertThat(h.run(m, "c").errorOrNull()?.code).isEqualTo(ErrorCode.RATE_LIMITED)
        advanceTimeBy(61.seconds)
        assertThat(h.run(m, "d")).isInstanceOf(AppResult.Ok::class.java)
    }

    @Test
    fun `missed schedule with SKIP policy is SKIPPED before step 0`() = runTest {
        val h = Harness(this)
        val m = macro(log(1, "x"))
        h.add(m)
        val handle = (
            h.executor.enqueue(
                RunRequest(
                    m.id,
                    ExecutionOrigin.Schedule(com.macroandroid.automation.model.ScheduleId("s")),
                    "r",
                    skipBecauseMissed = true,
                ),
            ) as AppResult.Ok
            ).value
        val outcome = handle.await()
        assertThat(outcome.state).isEqualTo(ExecutionState.SKIPPED)
        assertThat(h.store.attempts).isEmpty()
    }

    @Test
    fun `events are emitted for UI`() = runTest {
        val h = Harness(this)
        val seen = ArrayList<ExecutionEvent>()
        val collector = launch { h.executor.events.collect { seen += it } }
        val m = macro(log(1, "x"))
        h.add(m)
        (h.run(m) as AppResult.Ok).value.await()
        runCurrent()
        collector.cancel()
        assertThat(seen.filterIsInstance<ExecutionEvent.StateChanged>().map { it.to }).containsExactly(
            ExecutionState.PREPARING, ExecutionState.RUNNING, ExecutionState.COMPLETED,
        ).inOrder()
        assertThat(seen.filterIsInstance<ExecutionEvent.StepStarted>()).hasSize(1)
        assertThat(seen.filterIsInstance<ExecutionEvent.StepFinished>().single().state).isEqualTo(StepState.COMPLETED)
        assertThat(seen.filterIsInstance<ExecutionEvent.Progress>().single().completed).isEqualTo(1)
    }

    @Test
    fun `illegal transitions are impossible`() {
        assertThat(ExecutionState.COMPLETED.canTransitionTo(ExecutionState.RUNNING)).isFalse()
        assertThat(ExecutionState.QUEUED.canTransitionTo(ExecutionState.RUNNING)).isFalse()
        assertThat(ExecutionState.BLOCKED.canTransitionTo(ExecutionState.RUNNING)).isTrue()
        ExecutionState.entries.filter { it.isTerminal }.forEach { t ->
            ExecutionState.entries.forEach { assertThat(t.canTransitionTo(it)).isFalse() }
        }
    }
}
