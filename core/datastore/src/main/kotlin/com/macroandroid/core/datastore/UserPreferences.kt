package com.macroandroid.core.datastore

/** Typed view of the preferences DataStore. Defaults are the privacy-preserving choices. */
data class UserPreferences(
    val onboardingCompleted: Boolean = false,
    /** Explicit, separate consent for the accessibility service (Play policy + ADR-0008). */
    val accessibilityConsentGrantedAt: Long? = null,
    val accessibilityDisclosureVersion: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val confirmBeforeRun: Boolean = false,
    val keepScreenOnDuringRun: Boolean = false,
    val historyRetentionDays: Int = 30,
    val historyMaxRuns: Int = 5_000,
    val logMaxEntries: Int = 200_000,
    val verboseLogging: Boolean = false,
    val includeSecureValuesInExport: Boolean = false,
    val copyApkOnImport: Boolean = false,
    val appsSort: AppsSort = AppsSort.NAME,
    val appsShowSystem: Boolean = false,
    val lastReconcileAt: Long? = null,
    val notificationsRationaleShown: Boolean = false,
) {
    val accessibilityConsentGranted: Boolean get() = accessibilityConsentGrantedAt != null
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppsSort { NAME, RECENT_UPDATE, INSTALL_DATE, FAVORITES_FIRST }

/** The disclosure text version the user must have accepted; bump when the disclosure changes. */
const val CURRENT_A11Y_DISCLOSURE_VERSION = 1
