package com.macroandroid.feature.settings.presentation

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.datastore.UserPreferences
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.feature.settings.data.DiagnosticsExporter
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val executions = mockk<ExecutionRepository>(relaxed = true)
    private val audit = mockk<AuditContract>(relaxed = true)
    private val diagnostics = mockk<DiagnosticsExporter>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { prefs.preferences } returns flowOf(UserPreferences())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SettingsViewModel(prefs, executions, audit, diagnostics)

    @Test
    fun `security relevant toggles are audited`() = runTest(dispatcher) {
        val vm = vm()
        vm.setVerboseLogging(true)
        vm.setIncludeSecureValuesInExport(true)
        vm.setDynamicColor(false)
        advanceUntilIdle()
        coVerify { prefs.setVerboseLogging(true) }
        coVerify { audit.record(AuditKind.OTHER.name, "settings.verboseLogging", "true") }
        coVerify { audit.record(AuditKind.OTHER.name, "settings.includeSecureValuesInExport", "true") }
        coVerify(exactly = 2) { audit.record(any(), any(), any()) }
    }

    @Test
    fun `clear history wipes executions, audits and emits`() = runTest(dispatcher) {
        coEvery { executions.deleteAll() } returns Unit
        val vm = vm()
        vm.events.test {
            vm.clearHistory()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SettingsEvent.HistoryCleared)
        }
        coVerify { executions.deleteAll() }
        coVerify { audit.record(AuditKind.DATA_WIPED.name, "executions", null) }
    }

    @Test
    fun `export with null uri is a no-op`() = runTest(dispatcher) {
        val vm = vm()
        vm.exportDiagnostics(null)
        advanceUntilIdle()
        coVerify(exactly = 0) { diagnostics.render() }
    }
}
