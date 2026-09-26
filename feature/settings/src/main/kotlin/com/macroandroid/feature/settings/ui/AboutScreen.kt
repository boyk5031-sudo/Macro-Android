package com.macroandroid.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.feature.settings.R

/** FR-SET-1 "About". Version facts come from the host app; licences are listed in the generated notices file. */
@Composable
fun AboutRoute(versionName: String, versionCode: Long, onOpenHelp: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier, topBar = { MacroTopBar(title = stringResource(R.string.about_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, versionName, versionCode), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_what_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_what_body))
            Text(stringResource(R.string.about_limits_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_limits_body))
            Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_privacy_body))
            Text(stringResource(R.string.about_licences_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_licences_body))
            OutlinedButton(onClick = onOpenHelp) { Text(stringResource(R.string.about_open_help)) }
        }
    }
}
