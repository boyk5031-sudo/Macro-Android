package com.macroandroid.feature.trigger.presentation

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.automation.trigger.TriggerJson
import com.macroandroid.core.common.contract.TriggerAccessStatus
import com.macroandroid.core.common.contract.TriggerRuntimeContract
import com.macroandroid.core.common.contract.TriggerRuntimeStatus
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.repository.StoredTrigger
import com.macroandroid.core.database.repository.TriggerRepository
import com.macroandroid.core.datastore.UserPreferences
import com.macroandroid.core.datastore.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TriggerListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<TriggerRepository>(relaxed = true)
    private val runtime = mockk<TriggerRuntimeContract>(relaxed = true)
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)

    private val bound = TriggerFixtures.config(id = "bound", packageName = "com.game")
    private val manual = TriggerFixtures.config(id = "manual")
    private val stored = MutableStateFlow(listOf(bound, manual).map { StoredTrigger(it, EPOCH, EPOCH) })
    private val status = MutableStateFlow(TriggerRuntimeStatus(visibleTriggerIds = setOf("bound"), manuallyArmedId = "manual"))
    private val access = MutableStateFlow(READY)
    private val preferences = MutableStateFlow(UserPreferences(showTriggerIndicator = false))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeAll() } returns stored
        every { runtime.status } returns status
        every { runtime.access } returns access
        every { prefs.preferences } returns preferences
        coEvery { repository.all() } answers { stored.value }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = TriggerListViewModel(repository, runtime, prefs)

    @Test
    fun `state combines triggers, runtime, access and indicator preference`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test {
            assertThat(awaitItem().loaded).isFalse()
            val s = awaitItem()
            assertThat(s.loaded).isTrue()
            assertThat(s.triggers.map { it.id.value }).containsExactly("bound", "manual").inOrder()
            assertThat(s.runtime.visibleTriggerIds).containsExactly("bound")
            assertThat(s.access?.ready).isTrue()
            assertThat(s.indicatorVisible).isFalse()
        }
    }

    @Test
    fun `disabling the armed trigger also disarms it`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            awaitItem()
            vm.setEnabled(manual.id, false)
            advanceUntilIdle()
            coVerify { repository.setEnabled(manual.id, false) }
            coVerify(exactly = 1) { runtime.disarm() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disabling a non-armed trigger leaves the armed one alone`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            awaitItem()
            vm.setEnabled(bound.id, false)
            advanceUntilIdle()
            coVerify(exactly = 0) { runtime.disarm() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `arm errors are surfaced as events`() = runTest(dispatcher) {
        coEvery { runtime.arm("bound") } returns AppResult.Err(AppError(ErrorCode.TRIGGER_DISABLED))
        val vm = vm()
        vm.events.test {
            vm.setArmed(bound.id, true)
            advanceUntilIdle()
            assertThat((awaitItem() as TriggerListEvent.Error).error.code).isEqualTo(ErrorCode.TRIGGER_DISABLED)
        }
    }

    @Test
    fun `deleting the armed trigger disarms first`() = runTest(dispatcher) {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            awaitItem()
            vm.delete(manual.id)
            advanceUntilIdle()
            coVerify(exactly = 1) { runtime.disarm() }
            coVerify { repository.delete(manual.id) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `export produces a document that import reads back`() = runTest(dispatcher) {
        coEvery { repository.importAll(any()) } answers { AppResult.Ok(firstArg<List<*>>().size) }
        val vm = vm()
        vm.events.test {
            vm.exportAll()
            advanceUntilIdle()
            val exported = awaitItem() as TriggerListEvent.Exported
            assertThat(exported.count).isEqualTo(2)
            val doc = TriggerJson.decodeDocument(exported.json)
            assertThat(doc).isInstanceOf(AppResult.Ok::class.java)

            vm.import(exported.json)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(TriggerListEvent.Imported(2))
        }
    }

    @Test
    fun `corrupt import is rejected without touching the repository`() = runTest(dispatcher) {
        val vm = vm()
        vm.events.test {
            vm.import("{not json")
            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(TriggerListEvent.Error::class.java)
        }
        coVerify(exactly = 0) { repository.importAll(any()) }
    }

    @Test
    fun `indicator toggle writes the preference`() = runTest(dispatcher) {
        val vm = vm()
        vm.setIndicatorVisible(true)
        advanceUntilIdle()
        coVerify { prefs.setShowTriggerIndicator(true) }
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
