package com.macroandroid.feature.scheduling.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.EmptyState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.scheduling.R
import com.macroandroid.feature.scheduling.presentation.ScheduleRow
import com.macroandroid.feature.scheduling.presentation.SchedulesEvent
import com.macroandroid.feature.scheduling.presentation.SchedulesViewModel
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.time.Instant

object ScheduleTestTags {
    const val LIST = "sch.list"
    const val NEW = "sch.new"
    const val ROW_PREFIX = "sch.row."
    const val EDITOR_SAVE = "sch.editor.save"
}

@Composable
fun SchedulesRoute(
    onOpenEditor: (scheduleId: ScheduleId?, macroId: MacroId?) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: SchedulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ScheduleId?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is SchedulesEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(e.error)))
                SchedulesEvent.Deleted -> snackbar.showSnackbar(context.getString(R.string.sch_deleted))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MacroTopBar(
                title = state.macroName?.let { stringResource(R.string.sch_title_for, it) } ?: stringResource(R.string.sch_title),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenEditor(null, state.macroFilter) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.sch_new)) },
                modifier = Modifier.testTag(ScheduleTestTags.NEW),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !state.loaded -> LoadingState(Modifier.padding(padding))
            state.rows.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.sch_empty_title),
                description = stringResource(R.string.sch_empty_body),
                modifier = Modifier.padding(padding),
                actionLabel = stringResource(R.string.sch_new),
                onAction = { onOpenEditor(null, state.macroFilter) },
            )
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize().testTag(ScheduleTestTags.LIST)) {
                item {
                    Text(
                        stringResource(R.string.sch_inexact_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(state.rows, key = { it.stored.spec.id.value }) { row ->
                    ScheduleListItem(
                        row = row,
                        onOpen = { onOpenEditor(row.stored.spec.id, null) },
                        onToggle = { viewModel.setEnabled(row.stored.spec.id, it) },
                        onDelete = { pendingDelete = row.stored.spec.id },
                    )
                }
            }
        }
    }
    pendingDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.sch_delete_title),
            text = stringResource(R.string.sch_delete_body),
            confirmLabel = stringResource(R.string.sch_delete),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = { viewModel.delete(id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

@Composable
private fun ScheduleListItem(row: ScheduleRow, onOpen: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val s = row.stored
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).testTag(ScheduleTestTags.ROW_PREFIX + s.spec.id.value),
        headlineContent = { Text(row.macroName, maxLines = 1) },
        supportingContent = {
            Column {
                Text(kindSummary(s.spec.kind), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    val next = s.nextRunAt
                    val neutralFg = MaterialTheme.colorScheme.onSurface
                    when {
                        !s.spec.enabled -> StatusChip(stringResource(R.string.sch_chip_off), MacroTheme.status.neutral, neutralFg)
                        !row.macroEnabled -> StatusChip(
                            stringResource(R.string.sch_chip_macro_disabled),
                            MacroTheme.status.warning,
                            MacroTheme.status.onWarning,
                        )
                        next != null -> StatusChip(
                            stringResource(R.string.sch_next, relative(context, next)),
                            MacroTheme.status.info,
                            MacroTheme.status.onInfo,
                        )
                        else -> StatusChip(stringResource(R.string.sch_chip_done), MacroTheme.status.neutral, neutralFg)
                    }
                    s.lastResult?.let { StatusChip(stringResource(R.string.sch_last, it), MacroTheme.status.neutral, neutralFg) }
                }
            }
        },
        leadingContent = { Switch(checked = s.spec.enabled, onCheckedChange = onToggle) },
        trailingContent = {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.sch_delete)) }
        },
    )
}

internal fun relative(context: Context, at: Instant): CharSequence = DateUtils.getRelativeDateTimeString(
    context,
    at.toEpochMilliseconds(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.WEEK_IN_MILLIS,
    0,
)

@Composable
internal fun kindSummary(kind: ScheduleKind): String = when (kind) {
    is ScheduleKind.OneTime -> stringResource(R.string.sch_kind_once_at, relative(LocalContext.current, kind.at).toString())
    is ScheduleKind.Interval -> stringResource(R.string.sch_kind_every, formatMinutes(kind.minutes))
    is ScheduleKind.Daily -> stringResource(R.string.sch_kind_daily_at, formatTime(kind.localTime), days(kind.daysOfWeek))
}

@Composable
private fun days(set: Set<DayOfWeek>): String = when {
    set.size == DayOfWeek.entries.size -> stringResource(R.string.sch_every_day)
    set == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> stringResource(R.string.sch_weekends)
    set == DayOfWeek.entries.toSet() - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> stringResource(R.string.sch_weekdays)
    else -> DayOfWeek.entries.filter { it in set }.joinToString(" ") { dayShort(it) }
}

@Composable
internal fun dayShort(d: DayOfWeek): String = stringResource(
    when (d) {
        DayOfWeek.MONDAY -> R.string.sch_day_mon
        DayOfWeek.TUESDAY -> R.string.sch_day_tue
        DayOfWeek.WEDNESDAY -> R.string.sch_day_wed
        DayOfWeek.THURSDAY -> R.string.sch_day_thu
        DayOfWeek.FRIDAY -> R.string.sch_day_fri
        DayOfWeek.SATURDAY -> R.string.sch_day_sat
        DayOfWeek.SUNDAY -> R.string.sch_day_sun
    },
)

internal fun formatTime(t: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

internal fun formatMinutes(minutes: Int): String = when {
    minutes % (MINUTES_PER_HOUR * HOURS_PER_DAY) == 0 -> "${minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY)} d"
    minutes % MINUTES_PER_HOUR == 0 -> "${minutes / MINUTES_PER_HOUR} h"
    else -> "$minutes min"
}
