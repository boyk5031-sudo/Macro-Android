package com.macroandroid.feature.apkimport.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.apkimport.R
import com.macroandroid.feature.apkimport.domain.ApkStatus
import com.macroandroid.feature.apkimport.domain.InstalledState
import kotlin.time.Instant

internal fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

internal fun formatInstant(context: Context, at: Instant): String = DateUtils.formatDateTime(
    context,
    at.toEpochMilliseconds(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
)

@Composable
internal fun statusLabel(status: ApkStatus): String = stringResource(
    when (status) {
        ApkStatus.READY -> R.string.apk_status_ready
        ApkStatus.INVALID -> R.string.apk_status_invalid
        ApkStatus.UNAVAILABLE -> R.string.apk_status_unavailable
    },
)

@Composable
internal fun statusColors(status: ApkStatus): Pair<Color, Color> {
    val s = MacroTheme.status
    return when (status) {
        ApkStatus.READY -> s.success to s.onSuccess
        ApkStatus.INVALID -> s.warning to s.onWarning
        ApkStatus.UNAVAILABLE -> s.neutral to androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    }
}

@Composable
internal fun installedLabel(state: InstalledState?): String = stringResource(
    when (state) {
        InstalledState.INSTALLED_SAME_VERSION -> R.string.apk_installed_same
        InstalledState.INSTALLED_OLDER -> R.string.apk_installed_older
        InstalledState.INSTALLED_NEWER -> R.string.apk_installed_newer
        InstalledState.INSTALLED_DIFFERENT_SIGNATURE -> R.string.apk_installed_diff_sig
        InstalledState.NOT_VISIBLE, null -> R.string.apk_installed_unknown
    },
)
