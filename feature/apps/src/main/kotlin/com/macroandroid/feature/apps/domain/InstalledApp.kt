package com.macroandroid.feature.apps.domain

import kotlin.time.Instant

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val firstInstalledAt: Instant,
    val lastUpdatedAt: Instant,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val isFavorite: Boolean = false,
    /** Favourite whose package is no longer installed (FR-APP-3). */
    val isUninstalled: Boolean = false,
) {
    val normalisedSearchText: String = (label + " " + packageName).lowercase()
}

enum class AppsSortOrder { LABEL, RECENTLY_INSTALLED, RECENTLY_UPDATED, FAVORITES_FIRST }

data class AppsFilter(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val showSystem: Boolean = false,
    val sort: AppsSortOrder = AppsSortOrder.LABEL,
)
