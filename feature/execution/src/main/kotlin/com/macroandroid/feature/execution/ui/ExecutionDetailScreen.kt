package com.macroandroid.feature.execution.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.engine.StepState
import com.macroandroid.core.common.logging.LogLevel
import com.macroandroid.core.ui.component.ErrorState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.execution.R
import com.macroandroid.feature.execution.presentation.ExecutionDetailEvent
import com.macroandroid.feature.execution.presentation.ExecutionDetailViewModel

@Composable
fun ExecutionDetailRoute(
    onBack: () -> Unit,
    onOpenMacro: (String) -> Unit,
    onOpenExecution: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExecutionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        viewModel.export(uri)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is ExecutionDetailEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(e.error)))
                ExecutionDetailEvent.Exported -> snackbar.showSnackbar(context.getString(R.string.exe_exported))
                is ExecutionDetailEvent.Rerun -> {
                    val r = snackbar.showSnackbar(
                        context.getString(R.string.exe_rerun_started),
                        actionLabel = context.getString(R.string.exe_open),
                    )
                    if (r == SnackbarResult.ActionPerformed) onOpenExecution(e.executionId)
                }
            }
        }
    }
    val record = state.record
    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(title = record?.macroName ?: stringResource(R.string.exe_detail_title), onBack = onBack) {
                if (record != null) {
                    if (state.isLive) {
                        when (record.state) {
                            ExecutionState.PAUSED -> IconButton(viewModel::resume) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.exe_resume))
                            }
                            ExecutionState.RUNNING, ExecutionState.QUEUED, ExecutionState.PREPARING -> IconButton(viewModel::pause) {
                                Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.exe_pause))
                            }
                            else -> Unit
                        }
                        IconButton(viewModel::cancel) {
                            Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.exe_cancel))
                        }
                    } else {
                        IconButton(viewModel::rerun) {
                            Icon(Icons.Outlined.Replay, contentDescription = stringResource(R.string.exe_rerun))
                        }
                    }
                    IconButton({ exportLauncher.launch(viewModel.suggestedExportName()) }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.exe_export))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !state.loaded -> LoadingState(Modifier.padding(padding))
            record == null -> ErrorState(title = stringResource(R.string.exe_not_found), modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item { Header(record, onOpenMacro = { onOpenMacro(record.macroId.value) }) }
                item { SectionTitle(stringResource(R.string.exe_steps_section, state.steps.size)) }
                items(state.steps, key = { "${it.stepId.value}:${it.attempt}" }) { StepRow(it) }
                item { SectionTitle(stringResource(R.string.exe_log_section, state.logs.size)) }
                items(state.logs) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun Header(record: ExecutionRecord, onOpenMacro: () -> Unit) {
    val context = LocalContext.current
    val (bg, fg) = stateColors(record.state)
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(stateLabel(record.state), bg, fg)
                StatusChip(
                    originLabel(record.origin),
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.exe_queued_at, formatInstant(context, record.queuedAt)),
                style = MaterialTheme.typography.bodySmall,
            )
            record.startedAt?.let {
                Text(stringResource(R.string.exe_started_at, formatInstant(context, it)), style = MaterialTheme.typography.bodySmall)
            }
            record.endedAt?.let { ended ->
                val started = record.startedAt
                val dur = if (started != null) " (${formatDuration(ended - started)})" else ""
                Text(
                    stringResource(R.string.exe_ended_at, formatInstant(context, ended)) + dur,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.exe_progress, record.completedSteps, record.totalStaticSteps, record.retryTotal),
                style = MaterialTheme.typography.bodySmall,
            )
            if (record.state.isActive && record.totalStaticSteps > 0) {
                LinearProgressIndicator(
                    progress = { record.completedSteps.toFloat() / record.totalStaticSteps },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            record.blockedReason?.let {
                Text(
                    stringResource(R.string.exe_blocked_reason, stringResource(ErrorMessages.titleRes(it.code))),
                    color = MacroTheme.status.warning,
                )
            }
            record.errorCode?.let { code ->
                Text(
                    stringResource(ErrorMessages.titleRes(code)) + (record.errorDetail?.let { ": $it" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onOpenMacro) { Text(stringResource(R.string.exe_open_macro)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    HorizontalDivider()
}

@Composable
private fun StepRow(s: StepAttemptRecord) {
    val context = LocalContext.current
    val color = when (s.state) {
        StepState.COMPLETED -> MacroTheme.status.success
        StepState.FAILED, StepState.TIMED_OUT -> MaterialTheme.colorScheme.error
        StepState.RUNNING -> MacroTheme.status.info
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("#${s.stepIndex + 1}", style = MaterialTheme.typography.labelLarge)
            Text(s.actionType, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (s.attempt > 1) Text(stringResource(R.string.exe_attempt, s.attempt), style = MaterialTheme.typography.labelSmall)
            Text(stepStateLabel(s.state), color = color, style = MaterialTheme.typography.labelLarge)
        }
        val ended = s.endedAt
        Text(
            formatInstant(context, s.startedAt) + (ended?.let { " · ${formatDuration(it - s.startedAt)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        s.errorCode?.let {
            Text(
                stringResource(ErrorMessages.titleRes(it)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        s.outputSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LogRow(l: ExecutionLogEntry) {
    val context = LocalContext.current
    val color = when (l.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MacroTheme.status.warning
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        "${formatInstant(context, l.at)} ${l.stepIndex?.let { "[#${it + 1}] " } ?: ""}${l.message}",
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
