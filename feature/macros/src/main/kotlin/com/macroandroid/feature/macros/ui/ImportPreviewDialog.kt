package com.macroandroid.feature.macros.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macroandroid.automation.model.MacroId
import com.macroandroid.feature.macros.R
import com.macroandroid.feature.macros.data.ImportCandidate
import com.macroandroid.feature.macros.data.ImportConflictChoice
import com.macroandroid.feature.macros.data.ImportPreview

/** FR-MAC-6/7: shows every macro in the file, its validation issues and the per-conflict choice. */
@Composable
internal fun ImportPreviewDialog(
    preview: ImportPreview,
    onCommit: (Map<MacroId, ImportConflictChoice>) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = remember(preview) {
        mutableStateMapOf<MacroId, ImportConflictChoice>().apply {
            preview.candidates.filter { it.conflictsWith != null }.forEach { put(it.macro.id, ImportConflictChoice.RENAME) }
        }
    }
    val importable = preview.candidates.count { it.issues.isEmpty() && choices[it.macro.id] != ImportConflictChoice.SKIP }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.macro_import_preview_title, preview.candidates.size, preview.schemaVersion)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(preview.candidates, key = { it.macro.id.value }) { c ->
                    CandidateRow(c, choices[c.macro.id]) { choices[c.macro.id] = it }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCommit(choices.toMap()) }, enabled = importable > 0) {
                Text(pluralStringResource(R.plurals.macro_import_n, importable, importable))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun CandidateRow(c: ImportCandidate, choice: ImportConflictChoice?, onChoice: (ImportConflictChoice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(c.macro.name, style = MaterialTheme.typography.titleSmall)
        Text(
            pluralStringResource(R.plurals.macro_step_count, c.macro.steps.size, c.macro.steps.size) + " · " + c.macro.profile,
            style = MaterialTheme.typography.bodySmall,
        )
        c.issues.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (c.hasRedactedSecrets) {
            Text(
                stringResource(R.string.macro_import_redacted),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (c.conflictsWith != null && c.issues.isEmpty()) {
            Text(stringResource(R.string.macro_import_conflict), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImportConflictChoice.entries.forEach { opt ->
                    FilterChip(selected = choice == opt, onClick = { onChoice(opt) }, label = { Text(stringResource(opt.labelRes())) })
                }
            }
        }
    }
}

private fun ImportConflictChoice.labelRes(): Int = when (this) {
    ImportConflictChoice.RENAME -> R.string.macro_import_rename
    ImportConflictChoice.REPLACE -> R.string.macro_import_replace
    ImportConflictChoice.SKIP -> R.string.macro_import_skip
}
