package com.macroandroid.feature.apps.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.ErrorState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.feature.apps.R
import com.macroandroid.feature.apps.domain.InstalledApp
import com.macroandroid.feature.apps.presentation.AppDetailUiState
import com.macroandroid.feature.apps.presentation.AppDetailViewModel
import com.macroandroid.feature.apps.presentation.AppsUiEvent

@Composable
fun AppDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is AppsUiEvent.Launched -> context.getString(R.string.apps_launched, event.label)
                is AppsUiEvent.Message -> context.getString(R.string.apps_pin_requested, event.text)
                is AppsUiEvent.Error -> context.getString(ErrorMessages.titleRes(event.error))
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { MacroTopBar(title = stringResource(R.string.apps_detail_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            AppDetailUiState.Loading -> LoadingState(Modifier.padding(padding))
            AppDetailUiState.NotFound -> ErrorState(
                title = stringResource(R.string.apps_detail_not_found),
                modifier = Modifier.padding(padding),
            )
            is AppDetailUiState.Ready -> AppDetailContent(
                state = s,
                modifier = Modifier.padding(padding),
                iconLoader = { _, px -> viewModel.icon(px) },
                onLaunch = { viewModel.onLaunchResult(s.app, context.tryStartActivity(viewModel.launchIntent())) },
                onToggleFavorite = { viewModel.toggleFavorite(s.app) },
                onPin = { viewModel.pin(s.app) },
                onOpenSystemInfo = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", s.app.packageName, null))
                    context.tryStartActivity(intent)
                },
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun AppDetailContent(
    state: AppDetailUiState.Ready,
    modifier: Modifier,
    iconLoader: suspend (String, Int) -> android.graphics.Bitmap?,
    onLaunch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPin: () -> Unit,
    onOpenSystemInfo: () -> Unit,
) {
    val app = state.app
    val context = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName = app.packageName, contentDescription = null, loader = iconLoader, size = 56.dp, dimmed = !app.isEnabled)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(app.label, style = MaterialTheme.typography.headlineSmall)
                Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onLaunch, enabled = app.isEnabled) {
                Icon(Icons.Outlined.Launch, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.apps_launch))
            }
            OutlinedButton(onClick = onToggleFavorite) {
                Icon(if (app.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (app.isFavorite) R.string.apps_favorite_remove else R.string.apps_favorite_add))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onPin, enabled = state.pinSupported) {
                Icon(Icons.Outlined.AddToHomeScreen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.apps_pin))
            }
            OutlinedButton(onClick = onOpenSystemInfo) {
                Icon(Icons.Outlined.Settings, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.apps_detail_open_settings))
            }
        }
        if (!state.pinSupported) {
            Text(
                stringResource(R.string.apps_pin_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        DetailRow(stringResource(R.string.apps_detail_package), app.packageName)
        DetailRow(
            stringResource(R.string.apps_detail_version),
            stringResource(R.string.apps_detail_version_value, app.versionName ?: "—", app.versionCode),
        )
        DetailRow(stringResource(R.string.apps_detail_installed), formatDate(context, app))
        DetailRow(stringResource(R.string.apps_detail_updated), formatUpdated(context, app))
        DetailRow(stringResource(R.string.apps_detail_enabled), yesNo(app.isEnabled))
        DetailRow(stringResource(R.string.apps_detail_system), yesNo(app.isSystem))
        DetailRow(stringResource(R.string.apps_detail_macros), state.macroCount.toString())
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    ListItem(headlineContent = { Text(value) }, overlineContent = { Text(label) })
}

@Composable
private fun yesNo(value: Boolean) = stringResource(if (value) R.string.apps_yes else R.string.apps_no)

private fun formatDate(context: android.content.Context, app: InstalledApp): String =
    DateUtils.formatDateTime(context, app.firstInstalledAt.toEpochMilliseconds(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

private fun formatUpdated(context: android.content.Context, app: InstalledApp): String =
    DateUtils.formatDateTime(
        context,
        app.lastUpdatedAt.toEpochMilliseconds(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
    )
