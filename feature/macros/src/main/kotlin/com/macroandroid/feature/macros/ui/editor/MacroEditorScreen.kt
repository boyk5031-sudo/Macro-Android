package com.macroandroid.feature.macros.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.validation.Severity
import com.macroandroid.automation.validation.ValidationIssue
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.macros.R
import com.macroandroid.feature.macros.domain.MacroSentences
import com.macroandroid.feature.macros.presentation.MacroEditing
import com.macroandroid.feature.macros.presentation.MacroEditorEvent
import com.macroandroid.feature.macros.presentation.MacroEditorUiState
import com.macroandroid.feature.macros.presentation.MacroEditorViewModel
import com.macroandroid.feature.macros.presentation.StepPath
import com.macroandroid.feature.macros.ui.MacroTestTags

/** Where a new step goes: the list at [path], at [index]. */
private data class InsertTarget(val path: StepPath, val index: Int)

private data class EditTarget(val path: StepPath, val step: MacroStep)

@Composable
fun MacroEditorRoute(
    onBack: () -> Unit,
    onOpenPreview: (MacroId) -> Unit,
    onOpenExecution: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MacroEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var insertTarget by remember { mutableStateOf<InsertTarget?>(null) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.dirty) { showDiscard = true }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is MacroEditorEvent.Saved -> snackbar.showSnackbar(context.getString(R.string.macro_saved))
                is MacroEditorEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(e.error)))
                is MacroEditorEvent.StepRemoved -> {
                    val r = snackbar.showSnackbar(
                        context.getString(R.string.macro_step_removed),
                        actionLabel = context.getString(R.string.macro_undo),
                        duration = SnackbarDuration.Short,
                    )
                    if (r == SnackbarResult.ActionPerformed) viewModel.reinsertStep(e.step, e.path, e.index)
                }
                is MacroEditorEvent.StepTestStarted -> {
                    val r = snackbar.showSnackbar(
                        context.getString(R.string.macro_step_test_started),
                        actionLabel = context.getString(R.string.macro_open_execution),
                    )
                    if (r == SnackbarResult.ActionPerformed) onOpenExecution(e.executionId)
                }
                MacroEditorEvent.Closed -> onBack()
            }
        }
    }

    val macro = state.macro
    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(
                title = stringResource(if (state.isNew) R.string.macro_editor_new else R.string.macro_editor_edit),
                onBack = { if (state.dirty) showDiscard = true else viewModel.close(discard = false) },
            ) {
                TextButton(onClick = { onOpenPreview(viewModel.macroId) }, enabled = macro != null && !state.isNew) {
                    Text(stringResource(R.string.macro_preview))
                }
                IconButton(
                    onClick = { viewModel.save() },
                    enabled = state.canSave && state.dirty,
                    modifier = Modifier.testTag(MacroTestTags.EDITOR_SAVE),
                ) { Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.macro_save)) }
            }
        },
        floatingActionButton = {
            if (macro != null) {
                ExtendedFloatingActionButton(
                    onClick = { insertTarget = InsertTarget(emptyList(), macro.steps.size) },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.macro_add_step)) },
                    modifier = Modifier.testTag(MacroTestTags.EDITOR_ADD_STEP),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (macro == null || !state.loaded) {
            LoadingState(Modifier.padding(padding))
        } else {
            EditorBody(
                state = state,
                macro = macro,
                viewModel = viewModel,
                onInsert = { insertTarget = it },
                onEdit = { editTarget = it },
                modifier = Modifier.padding(padding),
            )
        }
    }

    insertTarget?.let { target ->
        ActionPickerSheet(
            depth = MacroEditing.depth(target.path),
            onPick = { kind ->
                viewModel.addStep(kind.create(), target.path, target.index)
                insertTarget = null
            },
            onDismiss = { insertTarget = null },
        )
    }
    editTarget?.let { target ->
        StepEditorSheet(
            step = target.step,
            issues = state.issuesByStep[target.step.id].orEmpty(),
            variables = macro?.variables?.keys?.sorted().orEmpty(),
            labels = macro?.allSteps()?.mapNotNull { it.label }.orEmpty(),
            storeSecret = { param, plain, existing -> viewModel.storeSecret(target.step.id, param, plain, existing) },
            onDone = { viewModel.replaceStep(target.path, it); editTarget = null },
            onDismiss = { editTarget = null },
        )
    }
    if (showDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.macro_unsaved_title),
            text = stringResource(R.string.macro_unsaved_body),
            confirmLabel = stringResource(R.string.macro_keep_draft),
            dismissLabel = stringResource(R.string.macro_discard),
            onConfirm = { showDiscard = false; viewModel.close(discard = false) },
            onDismiss = { showDiscard = false; viewModel.close(discard = true) },
        )
    }
}

@Composable
private fun EditorBody(
    state: MacroEditorUiState,
    macro: Macro,
    viewModel: MacroEditorViewModel,
    onInsert: (InsertTarget) -> Unit,
    onEdit: (EditTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(macro.steps) { MacroEditing.flatten(macro.steps) }
    val globalIssues = state.validation.issues.filter { it.stepId == null }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "draft") {
            if (state.draftAvailable) {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.macro_draft_found), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = viewModel::discardDraft) { Text(stringResource(R.string.macro_discard)) }
                            TextButton(onClick = viewModel::restoreDraft) { Text(stringResource(R.string.macro_restore_draft)) }
                        }
                    }
                }
            }
        }
        item(key = "header") { HeaderFields(macro, state, viewModel) }
        item(key = "issues") {
            globalIssues.forEach { issue ->
                Text(
                    stringResource(ErrorMessages.titleRes(issue.code)) + (issue.detail?.let { ": $it" } ?: ""),
                    color = if (issue.severity == Severity.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
        item(key = "steps-title") {
            Text(
                stringResource(R.string.macro_steps_title, macro.steps.size, MacroLimits.TOP_LEVEL_STEPS_MAX),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(rows, key = { _, r -> "${r.step.id.value}:${r.groupLabel ?: "s"}:${r.path.size}" }) { _, row ->
            if (row.groupLabel != null) {
                GroupHeader(row, onInsert)
            } else {
                StepRow(
                    row = row,
                    issues = state.issuesByStep[row.step.id].orEmpty(),
                    onEdit = { onEdit(EditTarget(row.path, row.step)) },
                    onMove = { viewModel.moveStep(row.path, row.step.id, it) },
                    onDuplicate = { viewModel.duplicateStep(row.path, row.step.id) },
                    onToggle = { viewModel.toggleStep(row.path, row.step.id) },
                    onTest = { viewModel.testStep(row.step.id) },
                    onDelete = { viewModel.removeStep(row.path, row.step.id) },
                    onInsertAfter = {
                        val idx = viewModelIndexOf(macro, row) + 1
                        onInsert(InsertTarget(row.path, idx))
                    },
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private fun viewModelIndexOf(macro: Macro, row: MacroEditing.Row): Int {
    var list = macro.steps
    for (seg in row.path) {
        val c = list.firstOrNull { it.id == seg.containerId } ?: return 0
        list = MacroEditing.groupsOf(c.action).getOrElse(seg.group) { emptyList() }
    }
    return list.indexOfFirst { it.id == row.step.id }.coerceAtLeast(0)
}

@Composable
private fun HeaderFields(macro: Macro, state: MacroEditorUiState, vm: MacroEditorViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            macro.name,
            vm::setName,
            label = { Text(stringResource(R.string.macro_name)) },
            singleLine = true,
            isError = state.validation.issues.any { it.stepId == null && it.field == "name" },
            supportingText = { Text("${macro.name.length}/${MacroLimits.NAME_MAX}") },
            modifier = Modifier.fillMaxWidth().testTag(MacroTestTags.EDITOR_NAME),
        )
        OutlinedTextField(
            macro.description,
            vm::setDescription,
            label = { Text(stringResource(R.string.macro_description)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        var profile by rememberSaveable(macro.id) { mutableStateOf(macro.profile) }
        OutlinedTextField(
            profile,
            { profile = it; vm.setProfile(it) },
            label = { Text(stringResource(R.string.macro_profile)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.profiles.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.profiles.forEach { p -> AssistChip(onClick = { profile = p; vm.setProfile(p) }, label = { Text(p) }) }
            }
        }
        TagsEditor(macro.tags, vm::addTag, vm::removeTag)
        LabeledSwitch(stringResource(R.string.macro_enabled), macro.enabled, vm::setEnabled)
        LabeledSwitch(stringResource(R.string.macro_confirm_before_run), macro.executionPolicy.requiresConfirmationBeforeRun) {
            vm.setPolicy(macro.executionPolicy.copy(requiresConfirmationBeforeRun = it))
        }
        DurationField(
            stringResource(R.string.macro_total_timeout),
            macro.executionPolicy.totalTimeout,
            { it?.let { d -> vm.setPolicy(macro.executionPolicy.copy(totalTimeout = d)) } },
            minMillis = MIN_TOTAL_TIMEOUT_MS,
            maxMillis = MAX_TOTAL_TIMEOUT_MS,
        )
        if (macro.requiresAccessibility) {
            StatusChip(stringResource(R.string.macro_requires_a11y_note), MacroTheme.status.warning, MacroTheme.status.onWarning)
        }
    }
}

private const val MIN_TOTAL_TIMEOUT_MS = 5_000L
private const val MAX_TOTAL_TIMEOUT_MS = 6L * 60 * 60 * 1000

@Composable
private fun TagsEditor(tags: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { t -> InputChip(selected = false, onClick = { onRemove(t) }, label = { Text("$t ×") }) }
        }
        if (tags.size < MacroLimits.TAGS_MAX) {
            OutlinedTextField(
                input,
                { input = it.take(MacroLimits.TAG_MAX) },
                label = { Text(stringResource(R.string.macro_add_tag)) },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { onAdd(input); input = "" }, enabled = input.isNotBlank()) {
                        Text(stringResource(R.string.macro_add))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GroupHeader(row: MacroEditing.Row, onInsert: (InsertTarget) -> Unit) {
    val label = when (row.step.action) {
        is ActionParameters.If -> if (row.groupLabel == 0) R.string.macro_branch_then else R.string.macro_branch_else
        else -> R.string.macro_branch_body
    }
    Row(
        Modifier.fillMaxWidth().padding(start = (16 + INDENT_DP * row.depth).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (row.depth < MacroLimits.NESTING_DEPTH_MAX) {
            TextButton(onClick = { onInsert(InsertTarget(row.path, Int.MAX_VALUE)) }) { Text(stringResource(R.string.macro_add_step)) }
        }
    }
}

private const val INDENT_DP = 16

@Suppress("LongParameterList")
@Composable
private fun StepRow(
    row: MacroEditing.Row,
    issues: List<ValidationIssue>,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onInsertAfter: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val step = row.step
    val hasError = issues.any { it.severity == Severity.ERROR }
    ListItem(
        modifier = Modifier
            .padding(start = (INDENT_DP * row.depth).dp)
            .clickable(onClick = onEdit)
            .testTag("macros.step.${step.id.value}"),
        headlineContent = {
            Text(
                step.label?.let { "$it — " }.orEmpty() + MacroSentences.sentence(step.action),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (step.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(actionKindLabel(ActionKind.of(step.action)), MacroTheme.status.neutral, MaterialTheme.colorScheme.onSurface)
                if (step.action.needsAccessibility) {
                    StatusChip(stringResource(R.string.macro_chip_a11y), MacroTheme.status.warning, MacroTheme.status.onWarning)
                }
                if (!step.enabled) {
                    StatusChip(stringResource(R.string.macro_chip_disabled), MacroTheme.status.neutral, MaterialTheme.colorScheme.onSurface)
                }
                if (hasError) {
                    StatusChip(
                        stringResource(R.string.macro_chip_invalid),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.macro_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.macro_edit)) }, { menu = false; onEdit() })
                DropdownMenuItem({ Text(stringResource(R.string.macro_move_up)) }, { menu = false; onMove(-1) })
                DropdownMenuItem({ Text(stringResource(R.string.macro_move_down)) }, { menu = false; onMove(1) })
                DropdownMenuItem({ Text(stringResource(R.string.macro_insert_after)) }, { menu = false; onInsertAfter() })
                DropdownMenuItem({ Text(stringResource(R.string.macro_duplicate)) }, { menu = false; onDuplicate() })
                DropdownMenuItem(
                    { Text(stringResource(if (step.enabled) R.string.macro_disable_step else R.string.macro_enable_step)) },
                    { menu = false; onToggle() },
                )
                DropdownMenuItem({ Text(stringResource(R.string.macro_test_step)) }, { menu = false; onTest() })
                DropdownMenuItem({ Text(stringResource(R.string.macro_delete)) }, { menu = false; onDelete() })
            }
        },
    )
}

@Composable
private fun ActionPickerSheet(depth: Int, onPick: (ActionKind) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.macro_pick_action),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val kinds = ActionKind.entries.filter { !it.container || depth < MacroLimits.NESTING_DEPTH_MAX - 1 }
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(kinds, key = { _, k -> k.name }) { _, kind ->
                ListItem(
                    modifier = Modifier.clickable { onPick(kind) },
                    headlineContent = { Text(actionKindLabel(kind)) },
                    supportingContent = { Text(stringResource(actionKindHelp(kind))) },
                    trailingContent = {
                        if (kind.needsA11y) {
                            StatusChip(stringResource(R.string.macro_chip_a11y), MacroTheme.status.warning, MacroTheme.status.onWarning)
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun actionKindHelp(k: ActionKind): Int = when (k) {
    ActionKind.LAUNCH_APP -> R.string.macro_help_launch_app
    ActionKind.OPEN_URL -> R.string.macro_help_open_url
    ActionKind.WAIT -> R.string.macro_help_wait
    ActionKind.GLOBAL_ACTION -> R.string.macro_help_global
    ActionKind.CLICK_NODE -> R.string.macro_help_click
    ActionKind.SCROLL_NODE -> R.string.macro_help_scroll
    ActionKind.ENTER_TEXT -> R.string.macro_help_enter_text
    ActionKind.WAIT_FOR_NODE -> R.string.macro_help_wait_for
    ActionKind.SEND_NOTIFICATION -> R.string.macro_help_notify
    ActionKind.SET_VARIABLE -> R.string.macro_help_set_variable
    ActionKind.IF -> R.string.macro_help_if
    ActionKind.REPEAT -> R.string.macro_help_repeat
    ActionKind.PARALLEL -> R.string.macro_help_parallel
    ActionKind.LOG -> R.string.macro_help_log
    ActionKind.STOP -> R.string.macro_help_stop
}
