package com.macroandroid.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.feature.settings.R

/** Stable topic ids other features deep-link to (`onOpenHelp(topic)`). */
object HelpTopics {
    const val AUTOMATION = "automation"
    const val PACKAGE_VISIBILITY = "package-visibility"
    const val SCHEDULES = "schedules"
    const val BATTERY = "battery"
    const val INSTALL = "install"
    const val ROBUST_MACROS = "robust-macros"
    const val TROUBLESHOOTING = "troubleshooting"
    const val FILE_ACCESS = "file-access"
    const val PRIVACY = "privacy"
}

private data class HelpTopic(val id: String, val title: Int, val body: Int)

private val TOPICS = listOf(
    HelpTopic(HelpTopics.AUTOMATION, R.string.help_automation_title, R.string.help_automation_body),
    HelpTopic(HelpTopics.ROBUST_MACROS, R.string.help_robust_title, R.string.help_robust_body),
    HelpTopic(HelpTopics.SCHEDULES, R.string.help_schedules_title, R.string.help_schedules_body),
    HelpTopic(HelpTopics.BATTERY, R.string.help_battery_title, R.string.help_battery_body),
    HelpTopic(HelpTopics.PACKAGE_VISIBILITY, R.string.help_visibility_title, R.string.help_visibility_body),
    HelpTopic(HelpTopics.INSTALL, R.string.help_install_title, R.string.help_install_body),
    HelpTopic(HelpTopics.FILE_ACCESS, R.string.help_files_title, R.string.help_files_body),
    HelpTopic(HelpTopics.TROUBLESHOOTING, R.string.help_troubleshooting_title, R.string.help_troubleshooting_body),
    HelpTopic(HelpTopics.PRIVACY, R.string.help_privacy_title, R.string.help_privacy_body),
)

/** FR-HLP-1: static, offline, filterable. `initialTopic` moves the matching card to the top. */
@Composable
fun HelpRoute(initialTopic: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val ordered = if (initialTopic == null) TOPICS else TOPICS.sortedBy { if (it.id == initialTopic) 0 else 1 }
    Scaffold(
        modifier = modifier,
        topBar = { MacroTopBar(title = stringResource(R.string.help_title), onBack = onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.help_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("help.search"),
            )
            HelpList(ordered, query)
        }
    }
}

@Composable
private fun HelpList(topics: List<HelpTopic>, query: String) {
    val q = query.trim()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(topics, key = { it.id }) { topic ->
            val title = stringResource(topic.title)
            val body = stringResource(topic.body)
            if (q.isEmpty() || title.contains(q, ignoreCase = true) || body.contains(q, ignoreCase = true)) {
                Card(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("help.topic.${topic.id}")) {
                    Column(Modifier.padding(16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
