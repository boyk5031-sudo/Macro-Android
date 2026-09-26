package com.macroandroid.feature.macros.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.automation.model.MacroId
import com.macroandroid.core.database.repository.MacroSummary
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.EmptyState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.macros.R
import com.macroandroid.feature.macros.presentation.MacroListEvent
import com.macroandroid.feature.macros.presentation.MacroListViewModel
import com.macroandroid.feature.macros.presentation.MacroRow

object MacroTestTags {
    const val LIST = "macros.list"
    const val NEW = "macros.new"
    const val SEARCH = "macros.search"
    const val ROW_PREFIX = "macros.row."
    const val EDITOR_SAVE = "macros.editor.save"
    const val EDITOR_NAME = "macros.editor.name"
    const val EDITOR_ADD_STEP = "macros.editor.addStep"
}

private const val JSON_MIME = "application/json"

@Composable
fun MacroListRoute(
    onOpenEditor: (MacroId?) -> Unit,
    onOpenPreview: (MacroId) -> Unit,
    onOpenExecution: (String) -> Unit,
    onOpenSchedules: (MacroId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MacroListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Pair<MacroId, String>?>(null) }
    var pendingRun by remember { mutableStateOf<Pair<MacroId, String>?>(null) }
    var exportChoice by remember { mutableStateOf<List<MacroId>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
        viewModel.completeExport(uri, appVersionCode(context))
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.previewImport(uri)
    }

    LaunchedEffect(state.pendingExport) {
        state.pendingExport?.let { exportLauncher.launch(it.suggestedName) }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is MacroListEvent.Started -> {
                    val r = snackbar.showSnackbar(
                        context.getString(R.string.macro_run_started, e.name),
                        actionLabel = context.getString(R.string.macro_open_execution),
                    )
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenExecution(e.executionId)
                }
                is MacroListEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(e.error)))
                is MacroListEvent.Deleted -> snackbar.showSnackbar(context.getString(R.string.macro_deleted, e.name))
                is MacroListEvent.Duplicated -> snackbar.showSnackbar(context.getString(R.string.macro_duplicated, e.name))
                is MacroListEvent.Exported ->
                    snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.macro_exported, e.count, e.count))
                is MacroListEvent.Imported ->
                    snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.macro_imported, e.count, e.count))
                is MacroListEvent.ConfirmRun -> pendingRun = e.macroId to e.name
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(title = stringResource(R.string.macros_title)) {
                IconButton(onClick = { importLauncher.launch(arrayOf(JSON_MIME, "*/*")) }) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.macro_import))
                }
                IconButton(
                    onClick = { exportChoice = state.groups.flatMap { g -> g.second.map { it.summary.id } } },
                    enabled = state.total > 0,
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.macro_export_all))
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenEditor(null) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.macro_new)) },
                modifier = Modifier.testTag(MacroTestTags.NEW),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Filters(viewModel, state.filter.query, state.filter.enabledOnly, state.filter.requiresA11yOnly, state.filter.scheduledOnly)
            ProfileChips(state.profiles, state.filter.profile, viewModel::setProfile)
            when {
                !state.loaded -> LoadingState()
                state.total == 0 -> EmptyState(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.macros_empty_title),
                    description = stringResource(R.string.macros_empty_body),
                    actionLabel = stringResource(R.string.macro_new),
                    onAction = { onOpenEditor(null) },
                )
                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.macros_no_match),
                    description = stringResource(R.string.macros_no_match_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize().testTag(MacroTestTags.LIST)) {
                    state.groups.forEach { (profile, rows) ->
                        item(key = "profile:$profile") {
                            Text(
                                profile,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(rows, key = { it.summary.id.value }) { row ->
                            MacroListItem(
                                row = row,
                                onOpen = { onOpenEditor(row.summary.id) },
                                onRun = { viewModel.run(row.summary.id, row.summary.name) },
                                onToggle = { viewModel.setEnabled(row.summary.id, it) },
                                onPreview = { onOpenPreview(row.summary.id) },
                                onSchedules = { onOpenSchedules(row.summary.id) },
                                onDuplicate = { viewModel.duplicate(row.summary.id) },
                                onExport = { exportChoice = listOf(row.summary.id) },
                                onDelete = { pendingDelete = row.summary.id to row.summary.name },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { (id, name) ->
        ConfirmDialog(
            title = stringResource(R.string.macro_delete_title),
            text = stringResource(R.string.macro_delete_body, name),
            confirmLabel = stringResource(R.string.macro_delete),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = { viewModel.delete(id, name); pendingDelete = null },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
    pendingRun?.let { (id, name) ->
        ConfirmDialog(
            title = stringResource(R.string.macro_confirm_run_title),
            text = stringResource(R.string.macro_confirm_run_body, name),
            confirmLabel = stringResource(R.string.macro_run),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = { viewModel.run(id, name, confirmed = true); pendingRun = null },
            onDismiss = { pendingRun = null },
        )
    }
    exportChoice?.let { ids ->
        ExportDialog(
            count = ids.size,
            onConfirm = { includeSecrets -> viewModel.requestExport(ids, includeSecrets); exportChoice = null },
            onDismiss = { exportChoice = null },
        )
    }
    state.importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            onCommit = viewModel::commitImport,
            onDismiss = viewModel::cancelImport,
        )
    }
}

private fun appVersionCode(context: Context): Int = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    (info.longVersionCode and 0xFFFF_FFFFL).toInt()
} catch (_: PackageManager.NameNotFoundException) {
    0
}

@Composable
private fun Filters(vm: MacroListViewModel, query: String, enabledOnly: Boolean, a11yOnly: Boolean, scheduledOnly: Boolean) {
    OutlinedTextField(
        value = query,
        onValueChange = vm::setQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(MacroTestTags.SEARCH),
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.macros_search_hint)) },
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(enabledOnly, { vm.setEnabledOnly(!enabledOnly) }, { Text(stringResource(R.string.macro_filter_enabled)) })
        FilterChip(a11yOnly, { vm.setRequiresA11yOnly(!a11yOnly) }, { Text(stringResource(R.string.macro_filter_a11y)) })
        FilterChip(scheduledOnly, { vm.setScheduledOnly(!scheduledOnly) }, { Text(stringResource(R.string.macro_filter_scheduled)) })
    }
}

@Composable
private fun ProfileChips(profiles: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    if (profiles.size < 2) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected == null, { onSelect(null) }, { Text(stringResource(R.string.macro_profile_all)) })
        profiles.forEach { p -> FilterChip(selected == p, { onSelect(if (selected == p) null else p) }, { Text(p) }) }
    }
}

@Suppress("LongParameterList")
@Composable
private fun MacroListItem(
    row: MacroRow,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onSchedules: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = row.summary
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).testTag(MacroTestTags.ROW_PREFIX + s.id.value),
        headlineContent = { Text(s.name, maxLines = 1) },
        supportingContent = { MacroSummaryLine(s, row.scheduleCount, row.hasDraft) },
        leadingContent = { Switch(checked = s.enabled, onCheckedChange = onToggle) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRun, enabled = s.enabled && s.stepCount > 0) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.macro_run))
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.macro_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.macro_preview)) }, { menu = false; onPreview() })
                    DropdownMenuItem({ Text(stringResource(R.string.macro_schedules)) }, { menu = false; onSchedules() })
                    DropdownMenuItem({ Text(stringResource(R.string.macro_duplicate)) }, { menu = false; onDuplicate() })
                    DropdownMenuItem({ Text(stringResource(R.string.macro_export)) }, { menu = false; onExport() })
                    DropdownMenuItem({ Text(stringResource(R.string.macro_delete)) }, { menu = false; onDelete() })
                }
            }
        },
    )
}

@Composable
private fun MacroSummaryLine(s: MacroSummary, scheduleCount: Int, hasDraft: Boolean) {
    Column {
        Text(
            pluralStringResource(R.plurals.macro_step_count, s.stepCount, s.stepCount) +
                (s.description.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            maxLines = 2,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (s.requiresAccessibility) {
                StatusChip(stringResource(R.string.macro_chip_a11y), MacroTheme.status.warning, MacroTheme.status.onWarning)
            }
            if (scheduleCount > 0) {
                StatusChip(
                    pluralStringResource(R.plurals.macro_chip_schedules, scheduleCount, scheduleCount),
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (hasDraft) {
                StatusChip(stringResource(R.string.macro_chip_draft), MacroTheme.status.neutral, MaterialTheme.colorScheme.onSurface)
            }
            s.lastRunState?.let { StatusChip(it.name, MacroTheme.status.neutral, MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun ExportDialog(count: Int, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var includeSecrets by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.macro_export_title, count, count)) },
        text = {
            Column {
                Text(stringResource(R.string.macro_export_body))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Checkbox(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                    Text(stringResource(R.string.macro_export_include_secrets))
                }
                if (includeSecrets) {
                    Text(
                        stringResource(R.string.macro_export_secrets_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(includeSecrets) }) { Text(stringResource(R.string.macro_export)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
