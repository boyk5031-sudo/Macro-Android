package com.macroandroid.feature.apkimport.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.EmptyState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.apkimport.R
import com.macroandroid.feature.apkimport.domain.ImportedApk
import com.macroandroid.feature.apkimport.presentation.ApkListEvent
import com.macroandroid.feature.apkimport.presentation.ApkListViewModel
import com.macroandroid.feature.apkimport.presentation.ApkSort

object ApkTestTags {
    const val LIST = "apk.list"
    const val IMPORT = "apk.import"
    const val ROW_PREFIX = "apk.row."
}

/** MIME types accepted by the picker (FR-APK-1). */
internal val APK_MIME_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")

@Composable
fun ApkListRoute(
    onOpenDetail: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApkListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var highlightId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ImportedApk?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ApkListEvent.ImportFinished ->
                    snackbar.showSnackbar(context.getString(R.string.apk_import_summary, event.imported, event.invalid, event.rejected.size))
                is ApkListEvent.HighlightExisting -> highlightId = event.id
                is ApkListEvent.Deleted -> snackbar.showSnackbar(context.getString(R.string.apk_deleted, event.name))
            }
        }
    }
    LaunchedEffect(highlightId, state.apks) {
        val id = highlightId ?: return@LaunchedEffect
        val index = state.apks.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(title = stringResource(R.string.apk_title)) {
                SortMenu(current = state.sort, onSelect = viewModel::setSort)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.importProgress == null) picker.launch(APK_MIME_TYPES) },
                icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                text = { Text(stringResource(R.string.apk_import)) },
                modifier = Modifier.testTag(ApkTestTags.IMPORT),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.importProgress?.let { p ->
                LinearProgressIndicator(
                    progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.apk_importing, p.done + 1, p.total),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            var query by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.setQuery(it)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.apk_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
            when {
                !state.loaded -> LoadingState()
                state.apks.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Android,
                    title = stringResource(R.string.apk_empty_title),
                    description = stringResource(R.string.apk_empty_description),
                    actionLabel = stringResource(R.string.apk_import),
                    onAction = { picker.launch(APK_MIME_TYPES) },
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(ApkTestTags.LIST)) {
                    items(state.apks, key = { it.id }) { apk ->
                        ApkRow(
                            apk = apk,
                            highlighted = apk.id == highlightId,
                            installedLabel = installedLabel(viewModel.installedState(apk)),
                            onClick = { onOpenDetail(apk.id) },
                            onDelete = { pendingDelete = apk },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { apk ->
        ConfirmDialog(
            title = stringResource(R.string.apk_delete_title),
            text = stringResource(R.string.apk_delete_text),
            confirmLabel = stringResource(R.string.apk_delete),
            dismissLabel = stringResource(R.string.apk_action_cancel),
            onConfirm = {
                viewModel.delete(apk)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

@Composable
private fun SortMenu(current: ApkSort, onSelect: (ApkSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Sort, contentDescription = stringResource(R.string.apk_sort)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        ApkSort.entries.forEach { s ->
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            when (s) {
                                ApkSort.NAME -> R.string.apk_sort_name
                                ApkSort.DATE -> R.string.apk_sort_date
                                ApkSort.SIZE -> R.string.apk_sort_size
                            },
                        ),
                    )
                },
                leadingIcon = { RadioButton(selected = s == current, onClick = null) },
                onClick = {
                    onSelect(s)
                    open = false
                },
            )
        }
    }
}

@Composable
private fun ApkRow(apk: ImportedApk, highlighted: Boolean, installedLabel: String, onClick: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val (bg, fg) = statusColors(apk.status)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag(ApkTestTags.ROW_PREFIX + apk.id),
        colors = if (highlighted) {
            androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            androidx.compose.material3.ListItemDefaults.colors()
        },
        headlineContent = { Text(apk.title, maxLines = 1) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(apk.packageName, apk.versionName, formatSize(context, apk.sizeBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(statusLabel(apk.status), bg, fg)
                    if (apk.semanticDuplicateOf != null) {
                        StatusChip(stringResource(R.string.apk_possible_duplicate), MacroTheme.status.warning, MacroTheme.status.onWarning)
                    }
                    if (apk.packageName != null) {
                        Text(installedLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.apk_delete)) }
        },
    )
}
