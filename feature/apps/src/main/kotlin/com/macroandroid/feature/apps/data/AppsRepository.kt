package com.macroandroid.feature.apps.data

import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.database.dao.AppDao
import com.macroandroid.core.database.entity.AppFavoriteEntity
import com.macroandroid.feature.apps.domain.InstalledApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class AppsRepository @Inject constructor(
    private val source: InstalledAppsDataSource,
    private val dao: AppDao,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)

    /** Installed apps merged with favourites; uninstalled favourites are kept as greyed rows (FR-APP-3). */
    fun observeApps(): Flow<List<InstalledApp>> =
        combine(installed.onStart { if (installed.value == null) refresh() }, dao.observeFavorites()) { apps, favs ->
            val favSet = favs.toSet()
            val present = apps.orEmpty().map { it.copy(isFavorite = it.packageName in favSet) }
            val presentPkgs = present.mapTo(HashSet()) { it.packageName }
            val ghosts = favSet.filterNot { it in presentPkgs }.map { pkg ->
                InstalledApp(
                    packageName = pkg, label = pkg, versionName = null, versionCode = 0,
                    firstInstalledAt = Instant.fromEpochMilliseconds(0), lastUpdatedAt = Instant.fromEpochMilliseconds(0),
                    isSystem = false, isEnabled = false, isFavorite = true, isUninstalled = true,
                )
            }
            present + ghosts
        }

    val isLoaded: Boolean get() = installed.value != null

    suspend fun refresh() {
        installed.value = source.loadLaunchable()
    }

    suspend fun refreshPackage(packageName: String) {
        val current = installed.value ?: return refresh()
        val updated = source.load(packageName)
        installed.value = if (updated == null) {
            current.filterNot { it.packageName == packageName }
        } else {
            current.filterNot { it.packageName == packageName } + updated
        }
    }

    fun packageChanges(): Flow<String?> = source.packageChanges()

    suspend fun app(packageName: String): InstalledApp? =
        installed.value?.firstOrNull { it.packageName == packageName } ?: source.load(packageName)

    suspend fun setFavorite(packageName: String, favorite: Boolean) = withContext(dispatchers.io) {
        if (favorite) {
            dao.addFavorite(AppFavoriteEntity(packageName, clock.now().toEpochMilliseconds()))
        } else {
            dao.removeFavorite(packageName)
        }
    }

    suspend fun icon(packageName: String, sizePx: Int) = source.icon(packageName, sizePx)
    fun launchIntent(packageName: String) = source.launchIntent(packageName)
}
