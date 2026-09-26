package com.macroandroid.feature.scheduling.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.automation.model.MissedRunPolicy
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.feature.scheduling.R
import com.macroandroid.feature.scheduling.presentation.ScheduleEditorEvent
import com.macroandroid.feature.scheduling.presentation.ScheduleEditorViewModel
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private enum class KindTab { ONCE, INTERVAL, DAILY }

private val INTERVAL_PRESETS = listOf(15, 30, 60, 120, 360, 720, 1440)

@Composable
fun ScheduleEditorRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScheduleEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is ScheduleEditorEvent.Saved -> onBack()
                is ScheduleEditorEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(e.error)))
            }
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(
                title = stringResource(if (state.isNew) R.string.sch_editor_new else R.string.sch_editor_edit),
                onBack = onBack,
            ) {
                TextButton(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.testTag(ScheduleTestTags.EDITOR_SAVE),
                ) {
                    Text(stringResource(R.string.sch_save))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val spec = state.spec
        if (!state.loaded || spec == null) {
            LoadingState(Modifier.padding(padding))
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MacroPicker(state.macros.map { it.id.value to it.name }, spec.macroId.value) { id ->
                    viewModel.setMacro(MacroId(id))
                }
                if (state.macros.isEmpty()) {
                    Text(stringResource(R.string.sch_no_macros), color = MaterialTheme.colorScheme.error)
                }
                KindEditor(spec, viewModel::setKind)
                HorizontalDivider()
                Text(stringResource(R.string.sch_conditions), style = MaterialTheme.typography.titleMedium)
                SwitchRow(stringResource(R.string.sch_requires_charging), spec.requiresCharging, viewModel::setRequiresCharging)
                SwitchRow(stringResource(R.string.sch_requires_battery), spec.requiresBatteryNotLow, viewModel::setRequiresBatteryNotLow)
                SwitchRow(stringResource(R.string.sch_requires_idle), spec.requiresDeviceIdle, viewModel::setRequiresDeviceIdle)
                Text(stringResource(R.string.sch_idle_note), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(stringResource(R.string.sch_missed_title), style = MaterialTheme.typography.titleMedium)
                MissedPolicyPicker(spec.missedRunPolicy, viewModel::setMissedPolicy)
                var late by remember(spec.id) { mutableStateOf(spec.lateThreshold.inWholeMinutes.toString()) }
                OutlinedTextField(
                    late,
                    { t ->
                        late = t
                        t.toIntOrNull()?.let { viewModel.setLateThreshold(it.minutes) }
                    },
                    label = { Text(stringResource(R.string.sch_late_threshold)) },
                    suffix = { Text(stringResource(R.string.sch_minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(stringResource(R.string.sch_enabled), spec.enabled, viewModel::setEnabled)
                HorizontalDivider()
                state.validation.issues.forEach { issue ->
                    Text(
                        stringResource(ErrorMessages.titleRes(issue.code)) + (issue.detail?.let { ": $it" } ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.nextRunPreview?.let {
                    Text(
                        stringResource(R.string.sch_next_preview, relative(context, it)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.sch_inexact_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MacroPicker(macros: List<Pair<String, String>>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = macros.firstOrNull { it.first == selectedId }?.second ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.sch_macro)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            macros.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}

@Composable
private fun KindEditor(spec: ScheduleSpec, onKind: (ScheduleKind) -> Unit) {
    val zone = TimeZone.currentSystemDefault()
    val tab = when (spec.kind) {
        is ScheduleKind.OneTime -> KindTab.ONCE
        is ScheduleKind.Interval -> KindTab.INTERVAL
        is ScheduleKind.Daily -> KindTab.DAILY
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        KindTab.entries.forEachIndexed { i, k ->
            SegmentedButton(
                selected = tab == k,
                onClick = {
                    onKind(
                        when (k) {
                            KindTab.ONCE -> ScheduleKind.OneTime(Clock.System.now() + DEFAULT_ONE_TIME_DELAY)
                            KindTab.INTERVAL -> ScheduleKind.Interval(DEFAULT_INTERVAL_MINUTES)
                            KindTab.DAILY -> ScheduleKind.Daily(LocalTime(DEFAULT_HOUR, 0), DayOfWeek.entries.toSet())
                        },
                    )
                },
                shape = SegmentedButtonDefaults.itemShape(i, KindTab.entries.size),
            ) {
                Text(
                    stringResource(
                        when (k) {
                            KindTab.ONCE -> R.string.sch_kind_once
                            KindTab.INTERVAL -> R.string.sch_kind_interval
                            KindTab.DAILY -> R.string.sch_kind_daily
                        },
                    ),
                )
            }
        }
    }
    when (val k = spec.kind) {
        is ScheduleKind.OneTime -> OneTimeEditor(k, zone, onKind)
        is ScheduleKind.Interval -> IntervalEditor(k, onKind)
        is ScheduleKind.Daily -> DailyEditor(k, onKind)
    }
}

@Composable
private fun OneTimeEditor(k: ScheduleKind.OneTime, zone: TimeZone, onKind: (ScheduleKind) -> Unit) {
    val context = LocalContext.current
    val local = k.at.toLocalDateTime(zone)
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showDate = true }, Modifier.weight(1f)) { Text(local.date.toString()) }
        OutlinedButton(onClick = { showTime = true }, Modifier.weight(1f)) { Text(formatTime(local.time)) }
    }
    Text(
        stringResource(R.string.sch_kind_once_at, relative(context, k.at).toString()),
        style = MaterialTheme.typography.bodySmall,
    )
    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = k.at.toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                        onKind(ScheduleKind.OneTime(date.atTime(local.time).toInstant(zone)))
                    }
                    showDate = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        TimeDialog(local.time, onDismiss = { showTime = false }) { t ->
            onKind(ScheduleKind.OneTime(local.date.atTime(t).toInstant(zone)))
            showTime = false
        }
    }
}

@Composable
private fun IntervalEditor(k: ScheduleKind.Interval, onKind: (ScheduleKind) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        INTERVAL_PRESETS.forEach { m ->
            FilterChip(k.minutes == m, { onKind(ScheduleKind.Interval(m)) }, { Text(formatMinutes(m)) })
        }
    }
    var text by remember(k.minutes) { mutableStateOf(k.minutes.toString()) }
    OutlinedTextField(
        text,
        { t ->
            text = t
            t.toIntOrNull()?.let { onKind(ScheduleKind.Interval(it)) }
        },
        label = { Text(stringResource(R.string.sch_interval_minutes)) },
        supportingText = {
            Text(
                stringResource(
                    R.string.sch_interval_range,
                    MacroLimits.SCHEDULE_INTERVAL_MIN_MINUTES,
                    MacroLimits.SCHEDULE_INTERVAL_MAX_MINUTES,
                ),
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DailyEditor(k: ScheduleKind.Daily, onKind: (ScheduleKind) -> Unit) {
    var showTime by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showTime = true }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sch_at_time, formatTime(k.localTime)))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { d ->
            val on = d in k.daysOfWeek
            FilterChip(
                selected = on,
                onClick = { onKind(k.copy(daysOfWeek = if (on) k.daysOfWeek - d else k.daysOfWeek + d)) },
                label = { Text(dayShort(d)) },
            )
        }
    }
    if (showTime) {
        TimeDialog(k.localTime, onDismiss = { showTime = false }) { t -> onKind(k.copy(localTime = t)); showTime = false }
    }
}

@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onConfirm(LocalTime(state.hour, state.minute)) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun MissedPolicyPicker(policy: MissedRunPolicy, onChange: (MissedRunPolicy) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MissedRunPolicy.entries.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = policy == p, onClick = { onChange(p) })
                Column {
                    Text(
                        stringResource(
                            when (p) {
                                MissedRunPolicy.RUN_LATE -> R.string.sch_missed_run_late
                                MissedRunPolicy.SKIP -> R.string.sch_missed_skip
                                MissedRunPolicy.RUN_ONCE_COALESCED -> R.string.sch_missed_coalesce
                            },
                        ),
                    )
                    Text(
                        stringResource(
                            when (p) {
                                MissedRunPolicy.RUN_LATE -> R.string.sch_missed_run_late_help
                                MissedRunPolicy.SKIP -> R.string.sch_missed_skip_help
                                MissedRunPolicy.RUN_ONCE_COALESCED -> R.string.sch_missed_coalesce_help
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private val DEFAULT_ONE_TIME_DELAY = 60.minutes
private const val DEFAULT_INTERVAL_MINUTES = 60
private const val DEFAULT_HOUR = 8
