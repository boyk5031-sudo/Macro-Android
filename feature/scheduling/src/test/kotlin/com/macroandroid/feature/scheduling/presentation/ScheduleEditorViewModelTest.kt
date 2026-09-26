package com.macroandroid.feature.scheduling.presentation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.MissedRunPolicy
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.automation.testing.MacroFixtures
import com.macroandroid.core.common.contract.SchedulerContract
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.database.repository.MacroSummary
import com.macroandroid.core.database.repository.ScheduleRepository
import com.macroandroid.core.database.repository.StoredSchedule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }
    private val macro = MacroFixtures.macro(MacroFixtures.step(1, ActionParameters.Wait(1.seconds)))
    private val summary = MacroSummary(
        id = macro.id,
        name = macro.name,
        description = macro.description,
        profile = macro.profile,
        tags = macro.tags,
        enabled = true,
        stepCount = 1,
        requiresAccessibility = false,
        updatedAt = now,
        lastRunAt = null,
        lastRunState = null,
    )
    private val schedules = mockk<ScheduleRepository>(relaxed = true)
    private val macros = mockk<MacroRepository>()
    private val scheduler = mockk<SchedulerContract>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { macros.observeSummaries() } returns flowOf(listOf(summary))
        coEvery { macros.get(macro.id) } returns macro
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(handle: SavedStateHandle = SavedStateHandle(mapOf("macroId" to macro.id.value))) =
        ScheduleEditorViewModel(handle, schedules, macros, scheduler, clock)

    @Test
    fun `new schedule defaults to daily and previews next run`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.loaded).isTrue()
        assertThat(s.spec?.kind).isInstanceOf(ScheduleKind.Daily::class.java)
        assertThat(s.validation.isValid).isTrue()
        assertThat(s.nextRunPreview).isNotNull()
        assertThat(s.nextRunPreview!! > now).isTrue()
    }

    @Test
    fun `one-time in the past is invalid and cannot be saved`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setKind(ScheduleKind.OneTime(now - 1.hours))
        val s = vm.uiState.value
        assertThat(s.validation.has(ErrorCode.SCHEDULE_TIME_IN_PAST)).isTrue()
        assertThat(s.canSave).isFalse()
        vm.save()
        advanceUntilIdle()
        coVerify(exactly = 0) { schedules.save(any()) }
    }

    @Test
    fun `save persists and plans via scheduler`() = runTest(dispatcher) {
        coEvery { schedules.save(any()) } answers {
            val spec = firstArg<ScheduleSpec>()
            AppResult.Ok(StoredSchedule(spec, "schedule:${spec.id.value}", null, null, null, null, now))
        }
        val vm = vm()
        advanceUntilIdle()
        vm.setKind(ScheduleKind.Interval(30))
        vm.setMissedPolicy(MissedRunPolicy.SKIP)
        vm.events.test {
            vm.save()
            advanceUntilIdle()
            val e = awaitItem()
            assertThat(e).isInstanceOf(ScheduleEditorEvent.Saved::class.java)
            val id = (e as ScheduleEditorEvent.Saved).id
            coVerify { scheduler.plan(id.value) }
        }
    }

    @Test
    fun `editing loads the stored spec`() = runTest(dispatcher) {
        val spec = ScheduleSpec(ScheduleId("s1"), macro.id, zoneId = "UTC", kind = ScheduleKind.Interval(60))
        coEvery { schedules.get(ScheduleId("s1")) } returns StoredSchedule(spec, "schedule:s1", null, null, null, null, now)
        val vm = vm(SavedStateHandle(mapOf("scheduleId" to "s1")))
        advanceUntilIdle()
        assertThat(vm.uiState.value.isNew).isFalse()
        assertThat(vm.uiState.value.spec).isEqualTo(spec)
    }
}
