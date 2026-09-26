package com.macroandroid.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.datastore.ThemeMode
import com.macroandroid.core.datastore.UserPreferences
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.feature.settings.R
import com.macroandroid.feature.settings.presentation.SettingsEvent
import com.macroandroid.feature.settings.presentation.SettingsViewModel

object SettingsTestTags {
    const val SCREEN = "settings.screen"
    const val PERMISSION_CENTER = "settings.permissions"
    const val AUDIT = "settings.audit"
    const val HELP = "settings.help"
    const val ABOUT = "settings.about"
}

@Composable
fun SettingsRoute(
    onOpenPermissionCenter: () -> Unit,
    onOpenAuditLog: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        viewModel.exportDiagnostics(uri)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            val text = when (e) {
                SettingsEvent.HistoryCleared -> context.getString(R.string.set_history_cleared)
                SettingsEvent.OnboardingReset -> context.getString(R.string.set_onboarding_reset_done)
                SettingsEvent.DiagnosticsExported -> context.getString(R.string.set_diagnostics_exported)
                is SettingsEvent.Error -> context.getString(ErrorMessages.titleRes(e.error))
            }
            snackbar.showSnackbar(text)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = { MacroTopBar(title = stringResource(R.string.set_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val p = prefs
        if (p == null) {
            LoadingState(Modifier.padding(padding))
        } else {
            SettingsContent(
                p = p,
                viewModel = viewModel,
                onOpenPermissionCenter = onOpenPermissionCenter,
                onOpenAuditLog = onOpenAuditLog,
                onOpenHelp = onOpenHelp,
                onOpenAbout = onOpenAbout,
                onExport = { exportLauncher.launch("macroandroid-diagnostics.txt") },
                onClearHistory = { confirmClear = true },
                modifier = Modifier.padding(padding),
            )
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.set_clear_history),
            text = stringResource(R.string.set_clear_history_body),
            confirmLabel = stringResource(R.string.set_clear),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = { viewModel.clearHistory(); confirmClear = false },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun SettingsContent(
    p: UserPreferences,
    viewModel: SettingsViewModel,
    onOpenPermissionCenter: () -> Unit,
    onOpenAuditLog: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onExport: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(R.string.set_section_permissions)
        NavRow(
            R.string.set_permission_center,
            R.string.set_permission_center_help,
            onOpenPermissionCenter,
            SettingsTestTags.PERMISSION_CENTER,
        )
        HorizontalDivider()

        SectionTitle(R.string.set_section_appearance)
        ThemeRow(p.themeMode, viewModel::setThemeMode)
        SwitchRow(R.string.set_dynamic_color, R.string.set_dynamic_color_help, p.dynamicColor, viewModel::setDynamicColor)
        HorizontalDivider()

        SectionTitle(R.string.set_section_execution)
        SwitchRow(
            R.string.set_confirm_before_run,
            R.string.set_confirm_before_run_help,
            p.confirmBeforeRun,
            viewModel::setConfirmBeforeRun,
        )
        SwitchRow(R.string.set_keep_screen_on, R.string.set_keep_screen_on_help, p.keepScreenOnDuringRun, viewModel::setKeepScreenOn)
        SwitchRow(R.string.set_verbose, R.string.set_verbose_help, p.verboseLogging, viewModel::setVerboseLogging)
        HorizontalDivider()

        SectionTitle(R.string.set_section_retention)
        SliderRow(
            label = stringResource(R.string.set_retention_days, p.historyRetentionDays),
            value = p.historyRetentionDays.toFloat(),
            range = RETENTION_DAYS_MIN..RETENTION_DAYS_MAX,
            steps = ((RETENTION_DAYS_MAX - RETENTION_DAYS_MIN) / RETENTION_DAYS_STEP).toInt() - 1,
            onChange = { viewModel.setHistoryRetentionDays(it.toInt()) },
        )
        SliderRow(
            label = stringResource(R.string.set_retention_runs, p.historyMaxRuns),
            value = p.historyMaxRuns.toFloat(),
            range = RETENTION_RUNS_MIN..RETENTION_RUNS_MAX,
            steps = ((RETENTION_RUNS_MAX - RETENTION_RUNS_MIN) / RETENTION_RUNS_STEP).toInt() - 1,
            onChange = { viewModel.setHistoryMaxRuns(it.toInt()) },
        )
        HorizontalDivider()

        SectionTitle(R.string.set_section_privacy)
        SwitchRow(
            R.string.set_export_secure,
            R.string.set_export_secure_help,
            p.includeSecureValuesInExport,
            viewModel::setIncludeSecureValuesInExport,
        )
        SwitchRow(R.string.set_copy_apk, R.string.set_copy_apk_help, p.copyApkOnImport, viewModel::setCopyApkOnImport)
        NavRow(R.string.set_audit_log, R.string.set_audit_log_help, onOpenAuditLog, SettingsTestTags.AUDIT)
        HorizontalDivider()

        SectionTitle(R.string.set_section_diagnostics)
        NavRow(R.string.set_export_diagnostics, R.string.set_export_diagnostics_help, onExport)
        NavRow(R.string.set_clear_history, R.string.set_clear_history_help, onClearHistory)
        NavRow(R.string.set_reset_onboarding, R.string.set_reset_onboarding_help, viewModel::resetOnboarding)
        HorizontalDivider()

        NavRow(R.string.set_help, R.string.set_help_help, onOpenHelp, SettingsTestTags.HELP)
        NavRow(R.string.set_about, R.string.set_about_help, onOpenAbout, SettingsTestTags.ABOUT)
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NavRow(title: Int, help: Int, onClick: () -> Unit, tag: String? = null) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(help)) },
        modifier = Modifier.clickable(onClick = onClick).then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

@Composable
private fun SwitchRow(title: Int, help: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(help)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            ThemeMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (m) {
                                ThemeMode.SYSTEM -> R.string.set_theme_system
                                ThemeMode.LIGHT -> R.string.set_theme_light
                                ThemeMode.DARK -> R.string.set_theme_dark
                            },
                        ),
                    )
                }
            }
        }
    }
}

private const val RETENTION_DAYS_MIN = 7f
private const val RETENTION_DAYS_MAX = 90f
private const val RETENTION_DAYS_STEP = 1f
private const val RETENTION_RUNS_MIN = 500f
private const val RETENTION_RUNS_MAX = 20_000f
private const val RETENTION_RUNS_STEP = 500f
