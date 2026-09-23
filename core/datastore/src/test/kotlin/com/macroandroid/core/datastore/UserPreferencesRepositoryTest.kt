package com.macroandroid.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `defaults and round trip`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("prefs.preferences_pb") }
        val repo = UserPreferencesRepository(store)

        val defaults = repo.current()
        assertThat(defaults).isEqualTo(UserPreferences())
        assertThat(defaults.accessibilityConsentGranted).isFalse()

        repo.grantAccessibilityConsent(now = 42L)
        repo.setThemeMode(ThemeMode.DARK)
        repo.setHistoryRetentionDays(999)
        val updated = repo.current()
        assertThat(updated.accessibilityConsentGranted).isTrue()
        assertThat(updated.accessibilityDisclosureVersion).isEqualTo(CURRENT_A11Y_DISCLOSURE_VERSION)
        assertThat(updated.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(updated.historyRetentionDays).isEqualTo(365)

        repo.revokeAccessibilityConsent()
        assertThat(repo.current().accessibilityConsentGranted).isFalse()
        repo.clear()
        assertThat(repo.current()).isEqualTo(UserPreferences())
    }
}
