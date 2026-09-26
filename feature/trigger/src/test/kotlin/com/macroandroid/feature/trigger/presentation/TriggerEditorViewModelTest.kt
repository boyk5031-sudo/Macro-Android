package com.macroandroid.feature.trigger.presentation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.automation.trigger.CoordinateSpace
import com.macroandroid.automation.trigger.DisplayCompatibility
import com.macroandroid.automation.trigger.ExecutionMode
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.automation.trigger.TriggerLimits
import com.macroandroid.core.common.contract.TriggerAccessStatus
import com.macroandroid.core.common.contract.TriggerRuntimeContract
import com.macroandroid.core.common.contract.TriggerRuntimeStatus
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.repository.StoredTrigger
import com.macroandroid.core.database.repository.TriggerRepository
import com.macroandroid.feature.trigger.data.LauncherApps
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TriggerEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<TriggerRepository>(relaxed = true)
    private val runtime = mockk<TriggerRuntimeContract>(relaxed = true)
    private val launcherApps = mockk<LauncherApps>()
    private val access = MutableStateFlow(READY)
    private val geometry = TriggerFixtures.PORTRAIT
    private val existing = TriggerFixtures.config()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { runtime.access } returns access
        every { runtime.status } returns MutableStateFlow(TriggerRuntimeStatus())
        coEvery { launcherApps.list() } returns listOf(LauncherApps.Entry("com.game", "Game"))
        coEvery { repository.get(existing.id) } returns StoredTrigger(existing, EPOCH, EPOCH)
        coEvery { repository.save(any()) } answers { AppResult.Ok(StoredTrigger(firstArg(), EPOCH, EPOCH)) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newVm(id: String? = null): TriggerEditorViewModel {
        val handle = SavedStateHandle(if (id == null) emptyMap() else mapOf(TriggerEditorViewModel.ARG_TRIGGER_ID to id))
        return TriggerEditorViewModel(handle, repository, runtime, launcherApps).also { it.onGeometry(geometry) }
    }

    @Test
    fun `new trigger starts with a default area, no points, and cannot be saved without a name`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.isNew).isTrue()
        val config = checkNotNull(s.config)
        assertThat(config.targetPoints).isEmpty()
        assertThat(config.authoredDisplay.widthPx).isEqualTo(geometry.widthPx)
        assertThat(s.errors.map { it.code }).contains(ErrorCode.NAME_INVALID)
        assertThat(s.canSave).isFalse()
        assertThat(s.apps.map { it.packageName }).containsExactly("com.game")
    }

    @Test
    fun `existing trigger loads and validates clean`() = runTest(dispatcher) {
        val vm = newVm(existing.id.value)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.isNew).isFalse()
        assertThat(s.config).isEqualTo(existing)
        assertThat(s.errors).isEmpty()
        assertThat(s.compatibility).isEqualTo(DisplayCompatibility.EXACT)
        assertThat(s.canSave).isTrue()
    }

    @Test
    fun `missing trigger emits not-found`() = runTest(dispatcher) {
        coEvery { repository.get(TriggerId("nope")) } returns null
        val vm = TriggerEditorViewModel(
            SavedStateHandle(mapOf(TriggerEditorViewModel.ARG_TRIGGER_ID to "nope")),
            repository,
            runtime,
            launcherApps,
        )
        vm.events.test {
            advanceUntilIdle()
            val e = awaitItem() as TriggerEditorEvent.Error
            assertThat(e.error.code).isEqualTo(ErrorCode.TRIGGER_NOT_FOUND)
        }
    }

    @Test
    fun `numeric area edit and drag both change the normalised area`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        vm.setAreaPx(xPx = 108, yPx = null, wPx = null, hPx = 240)
        var area = vm.uiState.value.config!!.triggerArea
        assertThat(area.x).isWithin(1e-9).of(0.1)
        assertThat(area.height).isWithin(1e-9).of(0.1)
        vm.moveArea(0.05, -0.02)
        area = vm.uiState.value.config!!.triggerArea
        assertThat(area.x).isWithin(1e-9).of(0.15)
        assertThat(vm.uiState.value.dirty).isTrue()
    }

    @Test
    fun `moving the area does not move display-space points but moves relative ones`() = runTest(dispatcher) {
        val vm = newVm(existing.id.value)
        advanceUntilIdle()
        vm.addPoint(0.5, 0.5)
        val relativeId = vm.uiState.value.selectedPointId!!
        vm.setPointSpace(relativeId, CoordinateSpace.TRIGGER_RELATIVE)
        val before = vm.uiState.value.config!!
        vm.moveArea(0.1, 0.0)
        val after = vm.uiState.value.config!!
        val displayPoints = after.targetPoints.filter { it.coordinateSpace == CoordinateSpace.DISPLAY }
        assertThat(displayPoints).isEqualTo(before.targetPoints.filter { it.coordinateSpace == CoordinateSpace.DISPLAY })
        val relativeBefore = before.targetPoints.first { it.id == relativeId }
        val relativeAfter = after.targetPoints.first { it.id == relativeId }
        assertThat(relativeAfter.x).isWithin(1e-9).of(relativeBefore.x)
        assertThat(relativeAfter.y).isWithin(1e-9).of(relativeBefore.y)
    }

    @Test
    fun `point management - add, move, reorder, disable, delete, cap`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        vm.addPoint(0.2, 0.2)
        vm.addPoint(0.4, 0.4)
        var points = vm.uiState.value.config!!.targetPoints
        assertThat(points).hasSize(2)
        val first = points[0].id
        val second = points[1].id
        assertThat(vm.uiState.value.selectedPointId).isEqualTo(second)

        vm.movePoint(first, 0.1, 0.1)
        assertThat(vm.uiState.value.config!!.targetPoints[0].x).isWithin(1e-9).of(0.3)
        vm.setPointPx(first, xPx = 540, yPx = null)
        assertThat(vm.uiState.value.config!!.targetPoints[0].x).isWithin(1e-9).of(0.5)

        vm.reorderPoint(second, -1)
        assertThat(vm.uiState.value.config!!.targetPoints.map { it.id }).containsExactly(second, first).inOrder()

        vm.setPointEnabled(first, false)
        assertThat(vm.uiState.value.config!!.enabledTargets.map { it.id }).containsExactly(second)

        vm.deletePoint(second)
        points = vm.uiState.value.config!!.targetPoints
        assertThat(points.map { it.id }).containsExactly(first)

        repeat(TriggerLimits.MAX_TARGETS + 3) { vm.addPoint(0.5, 0.5) }
        assertThat(vm.uiState.value.config!!.targetPoints).hasSize(TriggerLimits.MAX_TARGETS)
        assertThat(vm.uiState.value.canAddPoint).isFalse()
    }

    @Test
    fun `invalid values are clamped or reported`() = runTest(dispatcher) {
        val vm = newVm(existing.id.value)
        advanceUntilIdle()
        vm.setCooldown(-5)
        assertThat(vm.uiState.value.config!!.cooldownMs).isEqualTo(TriggerLimits.MIN_COOLDOWN_MS)
        vm.setRepeatCount(999)
        assertThat(vm.uiState.value.config!!.repeatCount).isEqualTo(TriggerLimits.MAX_REPEAT)
        vm.setAreaPx(xPx = null, yPx = null, wPx = 100_000, hPx = null)
        assertThat(vm.uiState.value.errors.map { it.code }).contains(ErrorCode.TRIGGER_AREA_INVALID)
        assertThat(vm.uiState.value.canSave).isFalse()
        vm.setAreaPx(xPx = null, yPx = null, wPx = 200, hPx = null)
        assertThat(vm.uiState.value.errors).isEmpty()
        vm.setName("   ")
        assertThat(vm.uiState.value.errors.map { it.code }).contains(ErrorCode.NAME_INVALID)
        assertThat(vm.uiState.value.canSave).isFalse()
    }

    @Test
    fun `save persists and emits Saved`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        vm.setName("Combo")
        vm.addPoint(0.5, 0.2)
        vm.setMode(ExecutionMode.MULTI_TOUCH)
        vm.events.test {
            vm.save()
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(TriggerEditorEvent.Saved::class.java)
        }
        coVerify { repository.save(match { it.name == "Combo" && it.executionMode == ExecutionMode.MULTI_TOUCH }) }
        assertThat(vm.uiState.value.dirty).isFalse()
        assertThat(vm.uiState.value.isNew).isFalse()
    }

    @Test
    fun `save failure surfaces the repository error`() = runTest(dispatcher) {
        coEvery { repository.save(any()) } returns AppResult.Err(AppError(ErrorCode.DB_ERROR))
        val vm = newVm(existing.id.value)
        advanceUntilIdle()
        vm.events.test {
            vm.save()
            advanceUntilIdle()
            assertThat((awaitItem() as TriggerEditorEvent.Error).error.code).isEqualTo(ErrorCode.DB_ERROR)
        }
        assertThat(vm.uiState.value.saving).isFalse()
    }

    @Test
    fun `test is refused while access is missing and runs once it is granted`() = runTest(dispatcher) {
        access.value = READY.copy(serviceConnected = false)
        coEvery { runtime.test(any()) } returns AppResult.Ok(Unit)
        val vm = newVm(existing.id.value)
        advanceUntilIdle()
        assertThat(vm.uiState.value.canTest).isFalse()
        vm.testHere()
        advanceUntilIdle()
        coVerify(exactly = 0) { runtime.test(any()) }

        access.value = READY
        advanceUntilIdle()
        assertThat(vm.uiState.value.canTest).isTrue()
        vm.events.test {
            vm.testHere()
            advanceTimeBy(500)
            assertThat(vm.uiState.value.testPhase).isEqualTo(TestPhase.RunningHere)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(TriggerEditorEvent.TestSucceeded)
        }
        assertThat(vm.uiState.value.testPhase).isEqualTo(TestPhase.Idle)
        coVerify(exactly = 1) { runtime.test(any()) }
    }

    @Test
    fun `in-game test counts down and can be cancelled before injecting`() = runTest(dispatcher) {
        val vm = newVm(existing.id.value)
        advanceUntilIdle()
        vm.testInGame()
        advanceTimeBy(1)
        assertThat(vm.uiState.value.testPhase).isEqualTo(TestPhase.Countdown(TriggerEditorViewModel.IN_GAME_COUNTDOWN_S))
        advanceTimeBy(2_000)
        assertThat(vm.uiState.value.testPhase).isEqualTo(TestPhase.Countdown(TriggerEditorViewModel.IN_GAME_COUNTDOWN_S - 2))
        vm.cancelTest()
        advanceUntilIdle()
        assertThat(vm.uiState.value.testPhase).isEqualTo(TestPhase.Idle)
        coVerify(exactly = 0) { runtime.test(any()) }
    }

    @Test
    fun `rotation flags an orientation mismatch for the loaded trigger`() = runTest(dispatcher) {
        val vm = newVm(existing.id.value)
        advanceUntilIdle()
        vm.onGeometry(TriggerFixtures.LANDSCAPE)
        assertThat(vm.uiState.value.compatibility).isEqualTo(DisplayCompatibility.ORIENTATION_MISMATCH)
        // The authored display of an existing trigger is never rewritten by a rotation.
        assertThat(vm.uiState.value.config!!.authoredDisplay).isEqualTo(existing.authoredDisplay)
    }

    private companion object {
        val EPOCH = Instant.fromEpochMilliseconds(0)
        val READY = TriggerAccessStatus(
            consentGranted = true,
            serviceEnabledInSettings = true,
            serviceConnected = true,
            gestureDispatchSupported = true,
            restrictedSettingsMayApply = false,
        )
    }
}
