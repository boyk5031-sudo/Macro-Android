package com.macroandroid.feature.macros.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.core.ui.component.ErrorState
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.component.StatusChip
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.macros.R
import com.macroandroid.feature.macros.domain.MacroSentences
import com.macroandroid.feature.macros.presentation.MacroPreviewViewModel

/** FR-MAC-4: read-only, numbered natural-language listing of the macro. */
@Composable
fun MacroPreviewRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MacroPreviewViewModel = hiltViewModel(),
) {
    val macro by viewModel.macro.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = { MacroTopBar(title = macro?.name ?: stringResource(R.string.macro_preview), onBack = onBack) },
    ) { padding ->
        val m = macro
        when {
            !loaded -> LoadingState(Modifier.padding(padding))
            m == null -> ErrorState(
                title = stringResource(R.string.macro_not_found),
                description = null,
                modifier = Modifier.padding(padding),
            )
            else -> {
                val words = previewWords()
                val lines = MacroSentences.describe(m, words)
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                            if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusChip(m.profile, MacroTheme.status.neutral, MaterialTheme.colorScheme.onSurface)
                                StatusChip(
                                    pluralStringResource(R.plurals.macro_step_count, m.steps.size, m.steps.size),
                                    MacroTheme.status.neutral,
                                    MaterialTheme.colorScheme.onSurface,
                                )
                                if (m.requiresAccessibility) {
                                    StatusChip(
                                        stringResource(R.string.macro_chip_a11y),
                                        MacroTheme.status.warning,
                                        MacroTheme.status.onWarning,
                                    )
                                }
                            }
                        }
                    }
                    items(lines) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    if (lines.isEmpty()) {
                        item { Text(stringResource(R.string.macro_preview_empty), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun previewWords(): MacroSentences.Words = MacroSentences.Words(
    launch = stringResource(R.string.w_launch),
    openUrl = stringResource(R.string.w_open_url),
    wait = stringResource(R.string.w_wait),
    press = stringResource(R.string.w_press),
    click = stringResource(R.string.w_click),
    longClick = stringResource(R.string.w_long_click),
    scroll = stringResource(R.string.w_scroll),
    enterText = stringResource(R.string.w_enter_text),
    enterSecret = stringResource(R.string.w_enter_secret),
    waitFor = stringResource(R.string.w_wait_for),
    appears = stringResource(R.string.w_appears),
    disappears = stringResource(R.string.w_disappears),
    notify = stringResource(R.string.w_notify),
    set = stringResource(R.string.w_set),
    ifWord = stringResource(R.string.w_if),
    thenWord = stringResource(R.string.w_then),
    elseWord = stringResource(R.string.w_else),
    repeat = stringResource(R.string.w_repeat),
    times = stringResource(R.string.w_times),
    whileWord = stringResource(R.string.w_while),
    parallel = stringResource(R.string.w_parallel),
    log = stringResource(R.string.w_log),
    stop = stringResource(R.string.w_stop),
    success = stringResource(R.string.w_success),
    failure = stringResource(R.string.w_failure),
    disabled = stringResource(R.string.w_disabled),
    node = stringResource(R.string.w_node),
    variable = stringResource(R.string.w_variable),
    exists = stringResource(R.string.w_exists),
    installed = stringResource(R.string.w_installed),
    not = stringResource(R.string.w_not),
    and = stringResource(R.string.w_and),
    or = stringResource(R.string.w_or),
    currentTime = stringResource(R.string.w_current_time),
    textOf = stringResource(R.string.w_text_of),
)
