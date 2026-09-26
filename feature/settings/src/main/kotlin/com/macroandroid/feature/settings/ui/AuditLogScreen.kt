package com.macroandroid.feature.settings.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.EmptyState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.feature.settings.R
import com.macroandroid.feature.settings.presentation.AuditLogViewModel
import java.util.Date

@Composable
fun AuditLogRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: AuditLogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dateFormat = DateFormat.getMediumDateFormat(context)
    val timeFormat = DateFormat.getTimeFormat(context)
    Scaffold(modifier = modifier, topBar = { MacroTopBar(title = stringResource(R.string.audit_title), onBack = onBack) }) { padding ->
        val list = entries
        when {
            list == null -> LoadingState(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.audit_empty_title),
                description = stringResource(R.string.audit_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                itemsIndexed(list) { _, e ->
                    val date = Date(e.at.toEpochMilliseconds())
                    ListItem(
                        headlineContent = { Text(e.kind.name) },
                        supportingContent = { Text(listOfNotNull(e.subject, e.detail).joinToString(" · ")) },
                        trailingContent = { Text("${dateFormat.format(date)}\n${timeFormat.format(date)}") },
                    )
                }
            }
        }
    }
}
