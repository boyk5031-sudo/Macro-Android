package com.macroandroid.feature.apkimport.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.ConfirmDialog
import com.macroandroid.core.ui.component.ErrorState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.feature.apkimport.R
import com.macroandroid.feature.apkimport.domain.ApkStatus
import com.macroandroid.feature.apkimport.presentation.ApkDetailEvent
import com.macroandroid.feature.apkimport.presentation.ApkDetailUiState
import com.macroandroid.feature.apkimport.presentation.ApkDetailViewModel

@Composable
fun ApkDetailRoute(
    onBack: () -> Unit,
    onOpenHelpInstall: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApkDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val reselect = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.relink(it) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ApkDetailEvent.ChecksumVerified ->
                    snackbar.showSnackbar(context.getString(if (event.matches) R.string.apk_checksum_ok else R.string.apk_checksum_mismatch))
                is ApkDetailEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(event.error)))
                ApkDetailEvent.Relinked -> snackbar.showSnackbar(context.getString(R.string.apk_relinked))
                ApkDetailEvent.Deleted -> onBack()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { MacroTopBar(title = stringResource(R.string.apk_detail_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            ApkDetailUiState.Loading -> LoadingState(Modifier.padding(padding))
            ApkDetailUiState.NotFound -> ErrorState(
                title = stringResource(R.string.apk_detail_not_found),
                modifier = Modifier.padding(padding),
            )
            is ApkDetailUiState.Ready -> {
                val apk = s.apk
                val (bg, fg) = statusColors(apk.status)
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(apk.title, style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        StatusChip(statusLabel(apk.status), bg, fg)
                        if (apk.semanticDuplicateOf != null) {
                            val st = com.macroandroid.core.ui.theme.MacroTheme.status
                            StatusChip(stringResource(R.string.apk_possible_duplicate), st.warning, st.onWarning)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::reverify, enabled = !s.busy && apk.status != ApkStatus.UNAVAILABLE) {
                            Text(stringResource(R.string.apk_detail_reverify))
                        }
                        if (apk.status == ApkStatus.UNAVAILABLE) {
                            OutlinedButton(onClick = { reselect.launch(APK_MIME_TYPES) }, enabled = !s.busy) {
                                Text(stringResource(R.string.apk_detail_reselect))
                            }
                        } else {
                            OutlinedButton(
                                onClick = { context.startActivity(Intent.createChooser(viewModel.shareIntent(apk), apk.title)) },
                                enabled = apk.status == ApkStatus.READY,
                            ) { Text(stringResource(R.string.apk_detail_share)) }
                        }
                    }
                    TextButton(onClick = onOpenHelpInstall) { Text(stringResource(R.string.apk_detail_why_no_install)) }
                    HorizontalDivider()
                    apk.errorCode?.let { DetailRow(stringResource(R.string.apk_detail_error), stringResource(ErrorMessages.titleRes(it))) }
                    DetailRow(stringResource(R.string.apk_detail_file), apk.displayName)
                    DetailRow(stringResource(R.string.apk_detail_size), formatSize(context, apk.sizeBytes))
                    DetailRow(stringResource(R.string.apk_detail_package), apk.packageName ?: stringResource(R.string.apk_unknown))
                    DetailRow(
                        stringResource(R.string.apk_detail_version),
                        listOfNotNull(apk.versionName, apk.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "—" },
                    )
                    DetailRow(
                        stringResource(R.string.apk_detail_sdk),
                        stringResource(R.string.apk_detail_sdk_value, apk.minSdk?.toString() ?: "—", apk.targetSdk?.toString() ?: "—"),
                    )
                    DetailRow(stringResource(R.string.apk_detail_installed_state), installedLabel(s.installedState))
                    DetailRow(stringResource(R.string.apk_detail_split), stringResource(if (apk.isSplit) R.string.apk_yes else R.string.apk_no))
                    DetailRow(stringResource(R.string.apk_detail_sha256), apk.sha256.ifEmpty { "—" }, mono = true)
                    DetailRow(stringResource(R.string.apk_detail_signer), apk.signerSha256 ?: "—", mono = true)
                    DetailRow(stringResource(R.string.apk_detail_imported), formatInstant(context, apk.importedAt))
                    if (apk.permissions.isNotEmpty()) {
                        Text(
                            stringResource(R.string.apk_detail_permissions, apk.permissions.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        apk.permissions.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                    Spacer(Modifier.height(12.dp))
                    var notes by rememberSaveable(apk.id) { mutableStateOf(apk.notes) }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = {
                            notes = it
                            viewModel.setNotes(it)
                        },
                        label = { Text(stringResource(R.string.apk_detail_notes)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.apk_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.apk_delete_title),
            text = stringResource(R.string.apk_delete_text),
            confirmLabel = stringResource(R.string.apk_delete),
            dismissLabel = stringResource(R.string.apk_action_cancel),
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value, fontFamily = if (mono) FontFamily.Monospace else null, style = MaterialTheme.typography.bodyMedium) },
    )
}
