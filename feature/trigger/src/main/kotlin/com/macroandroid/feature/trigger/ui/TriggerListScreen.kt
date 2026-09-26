package com.macroandroid.feature.trigger.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.automation.trigger.ExecutionMode
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.core.database.repository.StoredTrigger
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.EmptyState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.trigger.R
import com.macroandroid.feature.trigger.presentation.TriggerListEvent
import com.macroandroid.feature.trigger.presentation.TriggerListUiState
import com.macroandroid.feature.trigger.presentation.TriggerListViewModel

object TriggerTestTags {
    const val LIST = "trg.list"
    const val NEW = "trg.new"
    const val ROW_PREFIX = "trg.row."
    const val EDITOR_SAVE = "trg.editor.save"
    const val EDITOR_CANVAS = "trg.editor.canvas"
}

private const val JSON_MIME = "application/json"
private const val EXPORT_NAME = "macroandroid-triggers.json"

@Composable
fun TriggerListRoute(
    onOpenEditor: (TriggerId?) -> Unit,
    onOpenDisclosure: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TriggerListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<TriggerId?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
        val json = pendingExport
        pendingExport = null
        if (uri != null && json != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null) viewModel.import(text)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is TriggerListEvent.Error -> snackbar.showSnackbar(resources.getString(ErrorMessages.titleRes(e.error)))
                is TriggerListEvent.Exported -> {
                    pendingExport = e.json
                    exportLauncher.launch(EXPORT_NAME)
                }
                is TriggerListEvent.Imported ->
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.trg_imported, e.count, e.count))
                is TriggerListEvent.Duplicated -> onOpenEditor(e.id)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(title = stringResource(R.string.trg_title), onBack = onBack) {
                IconButton(onClick = { importLauncher.launch(arrayOf(JSON_MIME, "*/*")) }) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.trg_import))
                }
                IconButton(onClick = viewModel::exportAll, enabled = state.triggers.isNotEmpty()) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.trg_export))
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenEditor(null) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.trg_new)) },
                modifier = Modifier.testTag(TriggerTestTags.NEW),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !state.loaded -> LoadingState(Modifier.padding(padding))
            state.triggers.isEmpty() -> EmptyState(
                icon = Icons.Outlined.TouchApp,
                title = stringResource(R.string.trg_empty_title),
                description = stringResource(R.string.trg_empty_body),
                modifier = Modifier.padding(padding),
                actionLabel = stringResource(R.string.trg_new),
                onAction = { onOpenEditor(null) },
            )
            else -> TriggerList(
                state = state,
                onOpenDisclosure = onOpenDisclosure,
                onOpen = { onOpenEditor(it) },
                onToggle = viewModel::setEnabled,
                onArm = viewModel::setArmed,
                onDuplicate = viewModel::duplicate,
                onDelete = { pendingDelete = it },
                onIndicator = viewModel::setIndicatorVisible,
                modifier = Modifier.padding(padding),
            )
        }
    }

    pendingDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.trg_delete_title),
            text = stringResource(R.string.trg_delete_body),
            confirmLabel = stringResource(R.string.trg_delete),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = {
                viewModel.delete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

@Composable
private fun TriggerList(
    state: TriggerListUiState,
    onOpenDisclosure: () -> Unit,
    onOpen: (TriggerId) -> Unit,
    onToggle: (TriggerId, Boolean) -> Unit,
    onArm: (TriggerId, Boolean) -> Unit,
    onDuplicate: (TriggerId) -> Unit,
    onDelete: (TriggerId) -> Unit,
    onIndicator: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag(TriggerTestTags.LIST)) {
        item {
            state.access?.let { AccessChecklist(it, onOpenDisclosure, Modifier.padding(16.dp)) }
        }
        item { RuntimeBanner(state) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.trg_indicator_setting), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.trg_indicator_setting_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.indicatorVisible, onCheckedChange = onIndicator)
            }
        }
        items(state.triggers, key = { it.id.value }) { stored ->
            TriggerListItem(
                stored = stored,
                visible = stored.id.value in state.runtime.visibleTriggerIds,
                armed = state.runtime.manuallyArmedId == stored.id.value,
                onOpen = { onOpen(stored.id) },
                onToggle = { onToggle(stored.id, it) },
                onArm = { onArm(stored.id, it) },
                onDuplicate = { onDuplicate(stored.id) },
                onDelete = { onDelete(stored.id) },
            )
        }
    }
}

@Composable
private fun RuntimeBanner(state: TriggerListUiState) {
    val reason = state.runtime.blockedReason
    val text = when {
        state.runtime.visibleTriggerIds.isNotEmpty() ->
            pluralStringResource(R.plurals.trg_runtime_active, state.runtime.visibleTriggerIds.size, state.runtime.visibleTriggerIds.size)
        reason != null -> stringResource(R.string.trg_runtime_blocked, stringResource(ErrorMessages.titleRes(reason)))
        else -> stringResource(R.string.trg_runtime_idle)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TriggerListItem(
    stored: StoredTrigger,
    visible: Boolean,
    armed: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onArm: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val config = stored.config
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).testTag(TriggerTestTags.ROW_PREFIX + config.id.value),
        headlineContent = { Text(config.name.ifBlank { stringResource(R.string.trg_unnamed) }) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    config.packageName?.let { stringResource(R.string.trg_bound_to, it) }
                        ?: stringResource(R.string.trg_unbound),
                    style = MaterialTheme.typography.bodySmall,
                )
                val modeLabel = stringResource(
                    if (config.executionMode == ExecutionMode.SEQUENTIAL) R.string.trg_mode_sequential else R.string.trg_mode_multitouch,
                )
                Text(
                    pluralStringResource(R.plurals.trg_summary, config.enabledTargets.size, config.enabledTargets.size, modeLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (visible) {
                        StatusChip(
                            stringResource(R.string.trg_chip_on_screen),
                            MacroTheme.status.success,
                            MacroTheme.status.onSuccess,
                        )
                    }
                    if (config.packageName == null) {
                        FilterChip(
                            selected = armed,
                            onClick = { onArm(!armed) },
                            enabled = config.enabled,
                            label = { Text(stringResource(if (armed) R.string.trg_armed else R.string.trg_arm)) },
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.trg_duplicate))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.trg_delete))
                }
                Switch(checked = config.enabled, onCheckedChange = onToggle)
            }
        },
    )
}
