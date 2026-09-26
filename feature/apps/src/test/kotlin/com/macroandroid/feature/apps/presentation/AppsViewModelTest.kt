package com.macroandroid.feature.apps.presentation

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.contract.ShortcutsContract
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.core.testing.MainDispatcherRule
import com.macroandroid.feature.apps.data.AppsRepository
import com.macroandroid.feature.apps.domain.AppsSortOrder
import com.macroandroid.feature.apps.domain.InstalledApp
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val apps = listOf(
        app("com.b.beta", "Beta", installed = 10, system = false, favorite = false),
        app("com.a.alpha", "Álpha", installed = 20, system = false, favorite = true),
        app("com.sys.core", "System Core", installed = 5, system = true, favorite = false),
    )

    private fun viewModel(showSystem: Boolean = false): AppsViewModel {
        val repo = mockk<AppsRepository>(relaxed = true)
        every { repo.observeApps() } returns flowOf(apps)
        every { repo.packageChanges() } returns emptyFlow()
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.preferences } returns flowOf(
            com.macroandroid.core.datastore.UserPreferences(appsShowSystem = showSystem),
        )
        val shortcuts = mockk<ShortcutsContract>(relaxed = true)
        coEvery { shortcuts.pinApp(any(), any()) } returns AppResult.Ok(Unit)
        val audit = mockk<AuditContract>(relaxed = true)
        return AppsViewModel(repo, prefs, shortcuts, audit, mainDispatcherRule.dispatchers)
    }

    @Test
    fun `system apps hidden by default and list sorted by label`() = runTest {
        viewModel().uiState.test {
            skipItems(1) // Loading
            advanceTimeBy(300)
            val ready = expectMostRecentItem() as AppsUiState.Ready
            assertThat(ready.apps.map { it.packageName }).containsExactly("com.a.alpha", "com.b.beta").inOrder()
        }
    }

    @Test
    fun `search is diacritic-insensitive`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            skipItems(1)
            vm.setQuery("alpha")
            advanceTimeBy(400)
            val ready = expectMostRecentItem() as AppsUiState.Ready
            assertThat(ready.apps.map { it.packageName }).containsExactly("com.a.alpha")
        }
    }

    @Test
    fun `favorites-only filter`() = runTest {
        val vm = viewModel(showSystem = true)
        vm.uiState.test {
            skipItems(1)
            vm.setFavoritesOnly(true)
            advanceTimeBy(400)
            val ready = expectMostRecentItem() as AppsUiState.Ready
            assertThat(ready.apps.map { it.packageName }).containsExactly("com.a.alpha")
            assertThat(ready.filter.sort).isEqualTo(AppsSortOrder.LABEL)
        }
    }

    private fun app(pkg: String, label: String, installed: Long, system: Boolean, favorite: Boolean) = InstalledApp(
        packageName = pkg,
        label = label,
        versionName = "1.0",
        versionCode = 1,
        firstInstalledAt = Instant.fromEpochMilliseconds(installed),
        lastUpdatedAt = Instant.fromEpochMilliseconds(installed),
        isSystem = system,
        isEnabled = true,
        isFavorite = favorite,
    )
}
