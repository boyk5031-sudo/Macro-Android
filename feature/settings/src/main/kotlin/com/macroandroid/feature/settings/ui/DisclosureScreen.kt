package com.macroandroid.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.feature.settings.R
import com.macroandroid.feature.settings.presentation.DisclosureViewModel

object DisclosureTestTags {
    const val AGREE_CHECKBOX = "disclosure.agree"
    const val CONTINUE = "disclosure.continue"
    const val OPEN_SETTINGS = "disclosure.openSettings"
}

/**
 * FR-PRM-2 prominent disclosure (Play "AccessibilityService API" policy): shown before the system toggle; the
 * "Open Accessibility settings" button is enabled only after consent is stored.
 */
@Composable
fun DisclosureRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: DisclosureViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var checked by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = { MacroTopBar(title = stringResource(R.string.disc_title), onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.disc_intro), style = MaterialTheme.typography.bodyLarge)
            Section(R.string.disc_reads_title, R.string.disc_reads_body)
            Section(R.string.disc_does_title, R.string.disc_does_body)
            Section(R.string.disc_never_title, R.string.disc_never_body)
            Section(R.string.disc_data_title, R.string.disc_data_body)
            Section(R.string.disc_control_title, R.string.disc_control_body)
            if (!state.consentGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it }, Modifier.testTag(DisclosureTestTags.AGREE_CHECKBOX))
                    Text(stringResource(R.string.disc_agree))
                }
                Button(
                    onClick = viewModel::agree,
                    enabled = checked,
                    modifier = Modifier.fillMaxWidth().testTag(DisclosureTestTags.CONTINUE),
                ) { Text(stringResource(R.string.disc_continue)) }
            } else {
                Text(stringResource(R.string.disc_consented), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = viewModel::openAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth().testTag(DisclosureTestTags.OPEN_SETTINGS),
                ) {
                    Text(
                        stringResource(
                            if (state.serviceEnabled) R.string.disc_open_settings_enabled else R.string.disc_open_settings,
                        ),
                    )
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disc_done)) }
            }
        }
    }
}

@Composable
private fun Section(title: Int, body: Int) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}
