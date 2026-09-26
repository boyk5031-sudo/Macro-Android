package com.macroandroid.feature.settings.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.settings.R
import com.macroandroid.feature.settings.presentation.PermissionCenterUiState
import com.macroandroid.feature.settings.presentation.PermissionCenterViewModel

object PermissionTestTags {
    const val SCREEN = "perm.screen"
    const val A11Y_ROW = "perm.a11y"
    const val NOTIFICATIONS_ROW = "perm.notifications"
}

@Composable
fun PermissionCenterRoute(
    onOpenDisclosure: () -> Unit,
    onOpenHelp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionCenterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refresh()
        if (!granted) viewModel.markNotificationRationaleShown()
    }
    var confirmWithdraw by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.testTag(PermissionTestTags.SCREEN),
        topBar = { MacroTopBar(title = stringResource(R.string.perm_title), onBack = onBack) },
    ) { padding ->
        if (!state.loaded) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            NotificationsRow(
                state = state,
                onRequest = {
                    if (state.notificationsNeedRuntimePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.openNotificationSettings()
                    }
                },
                onOpenSettings = viewModel::openNotificationSettings,
            )
            HorizontalDivider()
            AccessibilityRow(
                state = state,
                onOpenDisclosure = onOpenDisclosure,
                onOpenSettings = viewModel::openAccessibilitySettings,
                onWithdraw = { confirmWithdraw = true },
                onOpenAppInfo = viewModel::openAppInfo,
            )
            HorizontalDivider()
            CapabilityRow(
                title = stringResource(R.string.perm_battery),
                body = stringResource(R.string.perm_battery_help),
                granted = state.batteryUnrestricted,
                grantedLabel = stringResource(R.string.perm_state_unrestricted),
                deniedLabel = stringResource(R.string.perm_state_optimized),
            ) {
                OutlinedButton(onClick = viewModel::openBatteryList) { Text(stringResource(R.string.perm_open_battery_list)) }
            }
            HorizontalDivider()
            CapabilityRow(
                title = stringResource(R.string.perm_shortcuts),
                body = stringResource(R.string.perm_shortcuts_help),
                granted = state.pinShortcutsSupported,
                grantedLabel = stringResource(R.string.perm_state_supported),
                deniedLabel = stringResource(R.string.perm_state_unsupported),
            ) {}
            HorizontalDivider()
            Text(
                stringResource(R.string.perm_footer),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
            OutlinedButton(onClick = onOpenHelp, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.perm_open_help))
            }
        }
    }
    if (confirmWithdraw) {
        ConfirmDialog(
            title = stringResource(R.string.perm_withdraw_title),
            text = stringResource(R.string.perm_withdraw_body, state.a11yMacroCount),
            confirmLabel = stringResource(R.string.perm_withdraw),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = { viewModel.withdrawConsent(); confirmWithdraw = false },
            onDismiss = { confirmWithdraw = false },
            destructive = true,
        )
    }
}

@Composable
private fun NotificationsRow(state: PermissionCenterUiState, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    CapabilityRow(
        title = stringResource(R.string.perm_notifications),
        body = stringResource(R.string.perm_notifications_help),
        granted = state.notificationsEnabled,
        grantedLabel = stringResource(R.string.perm_state_granted),
        deniedLabel = stringResource(R.string.perm_state_denied),
        modifier = Modifier.testTag(PermissionTestTags.NOTIFICATIONS_ROW),
    ) {
        if (!state.notificationsEnabled) {
            Button(onClick = onRequest) { Text(stringResource(R.string.perm_allow)) }
        }
        OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.perm_open_settings)) }
    }
}

@Composable
private fun AccessibilityRow(
    state: PermissionCenterUiState,
    onOpenDisclosure: () -> Unit,
    onOpenSettings: () -> Unit,
    onWithdraw: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val label = when {
        !state.consentGranted -> stringResource(R.string.perm_a11y_no_consent)
        state.consentOutdated -> stringResource(R.string.perm_a11y_consent_outdated)
        !state.serviceEnabledInSettings -> stringResource(R.string.perm_a11y_disabled)
        !state.serviceConnected -> stringResource(R.string.perm_a11y_connecting)
        else -> stringResource(R.string.perm_a11y_ready)
    }
    val ready = state.consentGranted && !state.consentOutdated && state.serviceConnected
    CapabilityRow(
        title = stringResource(R.string.perm_a11y),
        body = stringResource(R.string.perm_a11y_help),
        granted = ready,
        grantedLabel = label,
        deniedLabel = label,
        modifier = Modifier.testTag(PermissionTestTags.A11Y_ROW),
    ) {
        when {
            !state.consentGranted || state.consentOutdated ->
                Button(onClick = onOpenDisclosure) { Text(stringResource(R.string.perm_a11y_review)) }
            !state.serviceEnabledInSettings ->
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.perm_open_a11y_settings)) }
            else -> OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.perm_open_a11y_settings)) }
        }
        if (state.consentGranted) {
            OutlinedButton(onClick = onWithdraw) { Text(stringResource(R.string.perm_withdraw)) }
        }
        if (state.restrictedSettingsMayApply && !state.serviceEnabledInSettings) {
            Text(stringResource(R.string.perm_restricted_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.perm_open_app_info)) }
        }
    }
}

@Composable
private fun CapabilityRow(
    title: String,
    body: String,
    granted: Boolean,
    grantedLabel: String,
    deniedLabel: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(body)
                if (granted) {
                    StatusChip(grantedLabel, MacroTheme.status.success, MacroTheme.status.onSuccess)
                } else {
                    StatusChip(deniedLabel, MacroTheme.status.warning, MacroTheme.status.onWarning)
                }
                actions()
            }
        },
    )
}
