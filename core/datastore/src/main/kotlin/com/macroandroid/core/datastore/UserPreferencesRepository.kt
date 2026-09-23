package com.macroandroid.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> = dataStore.data.map { it.toModel() }

    suspend fun current(): UserPreferences = preferences.first()

    suspend fun setOnboardingCompleted(done: Boolean) = edit { it[Keys.ONBOARDING] = done }

    suspend fun grantAccessibilityConsent(now: Long, disclosureVersion: Int = CURRENT_A11Y_DISCLOSURE_VERSION) = edit {
        it[Keys.A11Y_CONSENT_AT] = now
        it[Keys.A11Y_DISCLOSURE_VERSION] = disclosureVersion
    }

    suspend fun revokeAccessibilityConsent() = edit {
        it.remove(Keys.A11Y_CONSENT_AT)
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setConfirmBeforeRun(enabled: Boolean) = edit { it[Keys.CONFIRM_BEFORE_RUN] = enabled }
    suspend fun setKeepScreenOn(enabled: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    suspend fun setHistoryRetentionDays(days: Int) = edit { it[Keys.RETENTION_DAYS] = days.coerceIn(1, 365) }
    suspend fun setHistoryMaxRuns(max: Int) = edit { it[Keys.RETENTION_RUNS] = max.coerceIn(100, 20_000) }
    suspend fun setVerboseLogging(enabled: Boolean) = edit { it[Keys.VERBOSE_LOGGING] = enabled }
    suspend fun setIncludeSecureValuesInExport(enabled: Boolean) = edit { it[Keys.EXPORT_SECURE] = enabled }
    suspend fun setCopyApkOnImport(enabled: Boolean) = edit { it[Keys.COPY_APK] = enabled }
    suspend fun setAppsSort(sort: AppsSort) = edit { it[Keys.APPS_SORT] = sort.name }
    suspend fun setAppsShowSystem(show: Boolean) = edit { it[Keys.APPS_SHOW_SYSTEM] = show }
    suspend fun setLastReconcileAt(at: Long) = edit { it[Keys.LAST_RECONCILE] = at }
    suspend fun setNotificationsRationaleShown(shown: Boolean) = edit { it[Keys.NOTIF_RATIONALE] = shown }

    /** Wipes everything (Settings → Delete all data). */
    suspend fun clear() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private fun Preferences.toModel() = UserPreferences(
        onboardingCompleted = this[Keys.ONBOARDING] ?: false,
        accessibilityConsentGrantedAt = this[Keys.A11Y_CONSENT_AT],
        accessibilityDisclosureVersion = this[Keys.A11Y_DISCLOSURE_VERSION] ?: 0,
        themeMode = this[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        confirmBeforeRun = this[Keys.CONFIRM_BEFORE_RUN] ?: false,
        keepScreenOnDuringRun = this[Keys.KEEP_SCREEN_ON] ?: false,
        historyRetentionDays = this[Keys.RETENTION_DAYS] ?: 30,
        historyMaxRuns = this[Keys.RETENTION_RUNS] ?: 5_000,
        logMaxEntries = this[Keys.LOG_MAX] ?: 200_000,
        verboseLogging = this[Keys.VERBOSE_LOGGING] ?: false,
        includeSecureValuesInExport = this[Keys.EXPORT_SECURE] ?: false,
        copyApkOnImport = this[Keys.COPY_APK] ?: false,
        appsSort = this[Keys.APPS_SORT]?.let { runCatching { AppsSort.valueOf(it) }.getOrNull() } ?: AppsSort.NAME,
        appsShowSystem = this[Keys.APPS_SHOW_SYSTEM] ?: false,
        lastReconcileAt = this[Keys.LAST_RECONCILE],
        notificationsRationaleShown = this[Keys.NOTIF_RATIONALE] ?: false,
    )

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val A11Y_CONSENT_AT = longPreferencesKey("a11y_consent_at")
        val A11Y_DISCLOSURE_VERSION = intPreferencesKey("a11y_disclosure_version")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CONFIRM_BEFORE_RUN = booleanPreferencesKey("confirm_before_run")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val RETENTION_DAYS = intPreferencesKey("history_retention_days")
        val RETENTION_RUNS = intPreferencesKey("history_max_runs")
        val LOG_MAX = intPreferencesKey("log_max_entries")
        val VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging")
        val EXPORT_SECURE = booleanPreferencesKey("include_secure_values_in_export")
        val COPY_APK = booleanPreferencesKey("copy_apk_on_import")
        val APPS_SORT = stringPreferencesKey("apps_sort")
        val APPS_SHOW_SYSTEM = booleanPreferencesKey("apps_show_system")
        val LAST_RECONCILE = longPreferencesKey("last_reconcile_at")
        val NOTIF_RATIONALE = booleanPreferencesKey("notifications_rationale_shown")
    }
}
