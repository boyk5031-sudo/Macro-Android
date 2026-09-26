package com.macroandroid.feature.apps.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.EmptyState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.apps.R
import com.macroandroid.feature.apps.domain.AppsSortOrder
import com.macroandroid.feature.apps.domain.InstalledApp
import com.macroandroid.feature.apps.presentation.AppsUiEvent
import com.macroandroid.feature.apps.presentation.AppsUiState
import com.macroandroid.feature.apps.presentation.AppsViewModel

object AppsTestTags {
    const val LIST = "apps.list"
    const val SEARCH = "apps.search"
    const val ROW_PREFIX = "apps.row."
}

/** Launches [intent] from an Activity context; returns false on the documented failure modes (FR-APP-4). */
internal fun android.content.Context.tryStartActivity(intent: Intent?): Boolean {
    if (intent == null) return false
    return try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsRoute(
    onOpenDetail: (packageName: String) -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is AppsUiEvent.Launched -> context.getString(R.string.apps_launched, event.label)
                is AppsUiEvent.Message -> context.getString(R.string.apps_pin_requested, event.text)
                is AppsUiEvent.Error -> context.getString(ErrorMessages.titleRes(event.error))
            }
            snackbar.showSnackbar(text)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MacroTopBar(title = stringResource(R.string.apps_title), scrollBehavior = scrollBehavior) {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.apps_refresh))
                }
                SortMenu(current = (state as? AppsUiState.Ready)?.filter?.sort, onSelect = { viewModel.setSort(it) })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            AppsUiState.Loading -> LoadingState(Modifier.padding(padding))
            is AppsUiState.Ready -> AppsContent(
                state = s,
                contentPadding = padding,
                callbacks = AppsCallbacks(
                    onQueryChange = viewModel::setQuery,
                    onFavoritesOnly = viewModel::setFavoritesOnly,
                    onShowSystem = { viewModel.setShowSystem(it) },
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onRemoveGhost = { viewModel.removeUninstalledFavorite(it) },
                    onLaunch = { app -> viewModel.onLaunchResult(app, context.tryStartActivity(viewModel.launchIntent(app))) },
                    onOpenDetail = onOpenDetail,
                    onOpenHelp = onOpenHelp,
                    iconLoader = { pkg, px -> viewModel.icon(pkg, px) },
                ),
            )
        }
    }
}

internal class AppsCallbacks(
    val onQueryChange: (String) -> Unit,
    val onFavoritesOnly: (Boolean) -> Unit,
    val onShowSystem: (Boolean) -> Unit,
    val onToggleFavorite: (InstalledApp) -> Unit,
    val onRemoveGhost: (InstalledApp) -> Unit,
    val onLaunch: (InstalledApp) -> Unit,
    val onOpenDetail: (String) -> Unit,
    val onOpenHelp: () -> Unit,
    val iconLoader: suspend (String, Int) -> Bitmap?,
)

@Composable
private fun SortMenu(current: AppsSortOrder?, onSelect: (AppsSortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Sort, contentDescription = stringResource(R.string.apps_sort))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        AppsSortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = { Text(stringResource(order.labelRes())) },
                leadingIcon = { RadioButton(selected = order == current, onClick = null) },
                onClick = {
                    onSelect(order)
                    open = false
                },
            )
        }
    }
}

internal fun AppsSortOrder.labelRes(): Int = when (this) {
    AppsSortOrder.LABEL -> R.string.apps_sort_label
    AppsSortOrder.RECENTLY_INSTALLED -> R.string.apps_sort_installed
    AppsSortOrder.RECENTLY_UPDATED -> R.string.apps_sort_updated
    AppsSortOrder.FAVORITES_FIRST -> R.string.apps_sort_favorites
}

@Composable
private fun AppsContent(state: AppsUiState.Ready, contentPadding: PaddingValues, callbacks: AppsCallbacks) {
    var query by rememberSaveable { mutableStateOf(state.filter.query) }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                callbacks.onQueryChange(it)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(AppsTestTags.SEARCH),
            placeholder = { Text(stringResource(R.string.apps_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            query = ""
                            callbacks.onQueryChange("")
                        },
                    ) { Icon(Icons.Outlined.Close, contentDescription = null) }
                }
            },
            singleLine = true,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.filter.favoritesOnly,
                onClick = { callbacks.onFavoritesOnly(!state.filter.favoritesOnly) },
                label = { Text(stringResource(R.string.apps_filter_favorites)) },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
            )
            FilterChip(
                selected = state.filter.showSystem,
                onClick = { callbacks.onShowSystem(!state.filter.showSystem) },
                label = { Text(stringResource(R.string.apps_filter_system)) },
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.apps_count, state.totalVisible),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (state.isEmpty) {
            val filtered = state.filter.query.isNotBlank() || state.filter.favoritesOnly
            EmptyState(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.apps_empty_title),
                description = stringResource(if (filtered) R.string.apps_empty_filtered else R.string.apps_empty_description),
                actionLabel = if (filtered) null else stringResource(R.string.apps_help),
                onAction = if (filtered) null else callbacks.onOpenHelp,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag(AppsTestTags.LIST)) {
                items(state.apps, key = { it.packageName }) { app ->
                    AppRow(app = app, callbacks = callbacks)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, callbacks: AppsCallbacks) {
    val status = MacroTheme.status
    ListItem(
        modifier = Modifier
            .clickable(enabled = !app.isUninstalled) { callbacks.onOpenDetail(app.packageName) }
            .testTag(AppsTestTags.ROW_PREFIX + app.packageName),
        leadingContent = {
            AppIcon(
                packageName = app.packageName,
                contentDescription = null,
                loader = callbacks.iconLoader,
                dimmed = app.isUninstalled || !app.isEnabled,
            )
        },
        headlineContent = { Text(app.label, maxLines = 1) },
        supportingContent = {
            Column {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!app.isUninstalled) {
                        Text(
                            app.versionName ?: app.versionCode.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (app.isUninstalled) {
                        StatusChip(stringResource(R.string.apps_uninstalled_chip), status.neutral, MaterialTheme.colorScheme.onSurface)
                    }
                    if (app.isSystem) {
                        StatusChip(stringResource(R.string.apps_system_chip), status.info, status.onInfo)
                    }
                    if (!app.isEnabled && !app.isUninstalled) {
                        StatusChip(stringResource(R.string.apps_disabled_chip), status.warning, status.onWarning)
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.isUninstalled) {
                    TextButton(onClick = { callbacks.onRemoveGhost(app) }) { Text(stringResource(R.string.apps_remove_ghost)) }
                } else {
                    IconButton(onClick = { callbacks.onToggleFavorite(app) }) {
                        Icon(
                            if (app.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(
                                if (app.isFavorite) R.string.apps_favorite_remove else R.string.apps_favorite_add,
                            ),
                            tint = if (app.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { callbacks.onLaunch(app) }, enabled = app.isEnabled) {
                        Text(stringResource(R.string.apps_launch))
                    }
                }
            }
        },
    )
}
