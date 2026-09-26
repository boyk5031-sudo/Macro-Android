package com.macroandroid.feature.execution.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.EmptyState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.feature.execution.R
import com.macroandroid.feature.execution.presentation.ExecutionsUiState
import com.macroandroid.feature.execution.presentation.ExecutionsViewModel
import kotlin.time.Clock

object ExecutionTestTags {
    const val LIST_ACTIVE = "exe.active"
    const val LIST_HISTORY = "exe.history"
    const val ROW_PREFIX = "exe.row."
}

private val HISTORY_FILTER_STATES = listOf(
    ExecutionState.COMPLETED,
    ExecutionState.FAILED,
    ExecutionState.CANCELLED,
    ExecutionState.INTERRUPTED,
    ExecutionState.SKIPPED,
)

private const val TAB_ACTIVE = 0
private const val TAB_HISTORY = 1

@Composable
fun ExecutionsRoute(
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExecutionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_ACTIVE) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(title = stringResource(R.string.exe_title)) {
                if (tab == TAB_ACTIVE && state.active.isNotEmpty()) {
                    TextButton(onClick = { confirmCancelAll = true }) { Text(stringResource(R.string.exe_cancel_all)) }
                }
                if (tab == TAB_HISTORY && state.history.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.exe_clear_history)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == TAB_ACTIVE, onClick = { tab = TAB_ACTIVE }, text = {
                    Text(stringResource(R.string.exe_tab_active, state.active.size))
                })
                Tab(selected = tab == TAB_HISTORY, onClick = { tab = TAB_HISTORY }, text = {
                    Text(stringResource(R.string.exe_tab_history))
                })
            }
            when {
                !state.loaded -> LoadingState()
                tab == TAB_ACTIVE -> ActiveList(state.active, viewModel, onOpenDetail)
                else -> HistoryList(state, viewModel, onOpenDetail)
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.exe_clear_history_title),
            text = stringResource(R.string.exe_clear_history_body),
            confirmLabel = stringResource(R.string.exe_clear_history),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = { viewModel.clearHistory(); confirmClear = false },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
    if (confirmCancelAll) {
        ConfirmDialog(
            title = stringResource(R.string.exe_cancel_all_title),
            text = stringResource(R.string.exe_cancel_all_body),
            confirmLabel = stringResource(R.string.exe_cancel_all),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = { viewModel.cancelAll(); confirmCancelAll = false },
            onDismiss = { confirmCancelAll = false },
            destructive = true,
        )
    }
}

@Composable
private fun ActiveList(active: List<ExecutionRecord>, vm: ExecutionsViewModel, onOpen: (String) -> Unit) {
    if (active.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.PlayArrow,
            title = stringResource(R.string.exe_active_empty_title),
            description = stringResource(R.string.exe_active_empty_body),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag(ExecutionTestTags.LIST_ACTIVE)) {
        items(active, key = { it.id.value }) { r ->
            ExecutionRow(
                record = r,
                onOpen = { onOpen(r.id.value) },
                controls = {
                    when (r.state) {
                        ExecutionState.RUNNING, ExecutionState.QUEUED, ExecutionState.PREPARING -> IconButton({ vm.pause(r.id) }) {
                            Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.exe_pause))
                        }
                        ExecutionState.PAUSED -> IconButton({ vm.resume(r.id) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.exe_resume))
                        }
                        else -> Unit
                    }
                    IconButton({ vm.cancel(r.id) }) {
                        Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.exe_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun HistoryList(
    state: ExecutionsUiState,
    vm: ExecutionsViewModel,
    onOpen: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(state.filter.state == null, { vm.setStateFilter(null) }, { Text(stringResource(R.string.exe_filter_all)) })
        HISTORY_FILTER_STATES.forEach { s -> FilterChip(state.filter.state == s, { vm.setStateFilter(s) }, { Text(stateLabel(s)) }) }
    }
    if (state.macros.size > 1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(state.filter.macroId == null, { vm.setMacroFilter(null) }, {
                Text(stringResource(R.string.exe_filter_all_macros))
            })
            state.macros.forEach { m ->
                FilterChip(state.filter.macroId == m.id, { vm.setMacroFilter(m.id) }, { Text(m.name) })
            }
        }
    }
    if (state.history.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.exe_history_empty_title),
            description = stringResource(R.string.exe_history_empty_body),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag(ExecutionTestTags.LIST_HISTORY)) {
        items(state.history, key = { it.id.value }) { r -> ExecutionRow(record = r, onOpen = { onOpen(r.id.value) }, controls = {}) }
        if (state.canLoadMore) {
            item {
                TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.exe_load_more)) }
            }
        }
    }
}

@Composable
internal fun ExecutionRow(record: ExecutionRecord, onOpen: () -> Unit, controls: @Composable () -> Unit) {
    val context = LocalContext.current
    val (bg, fg) = stateColors(record.state)
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).testTag(ExecutionTestTags.ROW_PREFIX + record.id.value),
        headlineContent = { Text(record.macroName, maxLines = 1) },
        supportingContent = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(stateLabel(record.state), bg, fg)
                    StatusChip(
                        originLabel(record.origin),
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val at = record.endedAt ?: record.startedAt ?: record.queuedAt
                Text(
                    formatRelative(at, Clock.System.now()).toString() + " · " + formatInstant(context, at) +
                        progressSuffix(record),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (record.state.isActive && record.totalStaticSteps > 0) {
                    LinearProgressIndicator(
                        progress = { record.completedSteps.toFloat() / record.totalStaticSteps },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        trailingContent = { Row { controls() } },
    )
}

private fun progressSuffix(r: ExecutionRecord): String {
    val started = r.startedAt
    val ended = r.endedAt
    return when {
        r.state.isActive -> " · ${r.completedSteps}/${r.totalStaticSteps}"
        started != null && ended != null -> " · ${formatDuration(ended - started)}"
        else -> ""
    }
}
