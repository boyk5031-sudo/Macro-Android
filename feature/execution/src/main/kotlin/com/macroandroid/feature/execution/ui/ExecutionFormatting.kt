package com.macroandroid.feature.execution.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.macroandroid.automation.engine.ExecutionOrigin
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.engine.StepState
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.execution.R
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal fun formatInstant(context: Context, at: Instant): String = DateUtils.formatDateTime(
    context,
    at.toEpochMilliseconds(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
)

internal fun formatRelative(at: Instant, now: Instant): CharSequence =
    DateUtils.getRelativeTimeSpanString(at.toEpochMilliseconds(), now.toEpochMilliseconds(), DateUtils.MINUTE_IN_MILLIS)

internal fun formatDuration(d: Duration): String {
    val totalSeconds = d.inWholeSeconds
    return when {
        d < 1000.milliseconds -> "${d.inWholeMilliseconds} ms"
        totalSeconds < SECONDS_PER_MINUTE -> "${totalSeconds}s"
        totalSeconds < SECONDS_PER_HOUR -> "${totalSeconds / SECONDS_PER_MINUTE}m ${totalSeconds % SECONDS_PER_MINUTE}s"
        else -> "${totalSeconds / SECONDS_PER_HOUR}h ${(totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE}m"
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

@Composable
internal fun stateLabel(state: ExecutionState): String = stringResource(
    when (state) {
        ExecutionState.QUEUED -> R.string.exe_state_queued
        ExecutionState.PREPARING -> R.string.exe_state_preparing
        ExecutionState.RUNNING -> R.string.exe_state_running
        ExecutionState.PAUSED -> R.string.exe_state_paused
        ExecutionState.BLOCKED -> R.string.exe_state_blocked
        ExecutionState.COMPLETED -> R.string.exe_state_completed
        ExecutionState.FAILED -> R.string.exe_state_failed
        ExecutionState.CANCELLED -> R.string.exe_state_cancelled
        ExecutionState.REJECTED -> R.string.exe_state_rejected
        ExecutionState.SKIPPED -> R.string.exe_state_skipped
        ExecutionState.INTERRUPTED -> R.string.exe_state_interrupted
    },
)

@Composable
internal fun stateColors(state: ExecutionState): Pair<Color, Color> {
    val s = MacroTheme.status
    val c = MaterialTheme.colorScheme
    return when (state) {
        ExecutionState.COMPLETED -> s.success to s.onSuccess
        ExecutionState.RUNNING, ExecutionState.PREPARING -> s.info to s.onInfo
        ExecutionState.PAUSED, ExecutionState.BLOCKED, ExecutionState.QUEUED -> s.warning to s.onWarning
        ExecutionState.FAILED, ExecutionState.INTERRUPTED -> c.errorContainer to c.onErrorContainer
        ExecutionState.CANCELLED, ExecutionState.REJECTED, ExecutionState.SKIPPED -> s.neutral to c.onSurface
    }
}

@Composable
internal fun stepStateLabel(state: StepState): String = stringResource(
    when (state) {
        StepState.PENDING -> R.string.exe_step_pending
        StepState.RUNNING -> R.string.exe_step_running
        StepState.COMPLETED -> R.string.exe_step_completed
        StepState.FAILED -> R.string.exe_step_failed
        StepState.TIMED_OUT -> R.string.exe_step_timed_out
        StepState.CANCELLED -> R.string.exe_step_cancelled
        StepState.SKIPPED -> R.string.exe_step_skipped
    },
)

@Composable
internal fun originLabel(origin: ExecutionOrigin): String = when (origin) {
    ExecutionOrigin.Manual -> stringResource(R.string.exe_origin_manual)
    ExecutionOrigin.Shortcut -> stringResource(R.string.exe_origin_shortcut)
    is ExecutionOrigin.Schedule -> stringResource(if (origin.late) R.string.exe_origin_schedule_late else R.string.exe_origin_schedule)
    is ExecutionOrigin.StepTest -> stringResource(R.string.exe_origin_step_test)
}
