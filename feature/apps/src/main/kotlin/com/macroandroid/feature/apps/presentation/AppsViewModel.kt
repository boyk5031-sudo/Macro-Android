package com.macroandroid.feature.apps.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.contract.ShortcutsContract
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.datastore.AppsSort
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.feature.apps.data.AppsRepository
import com.macroandroid.feature.apps.data.InstalledAppsDataSource
import com.macroandroid.feature.apps.domain.AppsFilter
import com.macroandroid.feature.apps.domain.AppsSortOrder
import com.macroandroid.feature.apps.domain.InstalledApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppsUiState {
    data object Loading : AppsUiState
    data class Ready(
        val apps: ImmutableList<InstalledApp>,
        val filter: AppsFilter,
        val totalVisible: Int,
    ) : AppsUiState {
        val isEmpty: Boolean get() = apps.isEmpty()
    }
}

sealed interface AppsUiEvent {
    data class Launched(val label: String) : AppsUiEvent
    data class Error(val error: AppError) : AppsUiEvent
    data class Message(val text: String) : AppsUiEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repository: AppsRepository,
    private val prefs: UserPreferencesRepository,
    private val shortcuts: ShortcutsContract,
    private val audit: AuditContract,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val _events = MutableSharedFlow<AppsUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AppsUiEvent> = _events.asSharedFlow()

    val isPinSupported: Boolean get() = shortcuts.isPinSupported

    private val filter = combine(
        query.debounce(QUERY_DEBOUNCE_MS).distinctUntilChanged(),
        favoritesOnly,
        prefs.preferences.map { it.appsShowSystem to it.appsSort }.distinctUntilChanged(),
    ) { q, favs, (showSystem, sort) ->
        AppsFilter(query = q, favoritesOnly = favs, showSystem = showSystem, sort = sort.toDomain())
    }

    val uiState: StateFlow<AppsUiState> = combine(repository.observeApps(), filter) { apps, f ->
        val visible = apps.asSequence()
            .filter { f.showSystem || !it.isSystem || it.isFavorite }
            .filter { !f.favoritesOnly || it.isFavorite }
            .filter { matches(it, f.query) }
            .sortedWith(comparator(f.sort))
            .toList()
        AppsUiState.Ready(visible.toImmutableList(), f, visible.size)
    }.flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppsUiState.Loading)

    init {
        repository.packageChanges()
            .onEach { pkg -> if (pkg != null) repository.refreshPackage(pkg) else repository.refresh() }
            .launchIn(viewModelScope)
    }

    fun setQuery(value: String) = query.update { value }
    fun setFavoritesOnly(value: Boolean) = favoritesOnly.update { value }
    fun setShowSystem(value: Boolean) = viewModelScope.launch { prefs.setAppsShowSystem(value) }
    fun setSort(value: AppsSortOrder) = viewModelScope.launch { prefs.setAppsSort(value.toPref()) }
    fun refresh() = viewModelScope.launch { repository.refresh() }

    fun toggleFavorite(app: InstalledApp) = viewModelScope.launch { repository.setFavorite(app.packageName, !app.isFavorite) }

    fun removeUninstalledFavorite(app: InstalledApp) = viewModelScope.launch { repository.setFavorite(app.packageName, false) }

    fun pin(app: InstalledApp) = viewModelScope.launch {
        when (val r = shortcuts.pinApp(app.packageName, app.label)) {
            is AppResult.Ok -> _events.emit(AppsUiEvent.Message(app.label))
            is AppResult.Err -> _events.emit(AppsUiEvent.Error(r.error))
        }
    }

    /** The Activity performs the actual `startActivity` (needs an Activity context for BAL rules); we log the outcome. */
    fun onLaunchResult(app: InstalledApp, success: Boolean) = viewModelScope.launch {
        if (success) {
            _events.emit(AppsUiEvent.Launched(app.label))
        } else {
            audit.record("APP_LAUNCH_FAILED", app.packageName)
            _events.emit(AppsUiEvent.Error(AppError(ErrorCode.APP_LAUNCH_FAILED, app.packageName)))
        }
    }

    fun launchIntent(app: InstalledApp) = repository.launchIntent(app.packageName)

    suspend fun icon(packageName: String, sizePx: Int) = repository.icon(packageName, sizePx)

    private fun matches(app: InstalledApp, query: String): Boolean {
        if (query.isBlank()) return true
        val q = InstalledAppsDataSource.foldDiacritics(query.trim())
        return InstalledAppsDataSource.foldDiacritics(app.label).contains(q) || app.packageName.lowercase().contains(q)
    }

    private fun comparator(sort: AppsSortOrder): Comparator<InstalledApp> {
        val byLabel = Comparator<InstalledApp> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label) }
        return when (sort) {
            AppsSortOrder.LABEL -> byLabel
            AppsSortOrder.RECENTLY_INSTALLED -> compareByDescending<InstalledApp> { it.firstInstalledAt }.then(byLabel)
            AppsSortOrder.RECENTLY_UPDATED -> compareByDescending<InstalledApp> { it.lastUpdatedAt }.then(byLabel)
            AppsSortOrder.FAVORITES_FIRST -> compareByDescending<InstalledApp> { it.isFavorite }.then(byLabel)
        }
    }

    private fun AppsSort.toDomain() = when (this) {
        AppsSort.NAME -> AppsSortOrder.LABEL
        AppsSort.INSTALL_DATE -> AppsSortOrder.RECENTLY_INSTALLED
        AppsSort.RECENT_UPDATE -> AppsSortOrder.RECENTLY_UPDATED
        AppsSort.FAVORITES_FIRST -> AppsSortOrder.FAVORITES_FIRST
    }

    private fun AppsSortOrder.toPref() = when (this) {
        AppsSortOrder.LABEL -> AppsSort.NAME
        AppsSortOrder.RECENTLY_INSTALLED -> AppsSort.INSTALL_DATE
        AppsSortOrder.RECENTLY_UPDATED -> AppsSort.RECENT_UPDATE
        AppsSortOrder.FAVORITES_FIRST -> AppsSort.FAVORITES_FIRST
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 250L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
