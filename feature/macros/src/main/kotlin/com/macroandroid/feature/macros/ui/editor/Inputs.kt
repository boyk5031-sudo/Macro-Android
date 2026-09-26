package com.macroandroid.feature.macros.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.TextMatch
import com.macroandroid.automation.model.TextValue
import com.macroandroid.feature.macros.R
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A generic enum dropdown; labels come from [label]. */
@Composable
internal fun <T : Enum<T>> EnumDropdown(
    label: String,
    value: T,
    values: List<T>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemLabel: @Composable (T) -> String = { it.name.lowercase().replace('_', ' ') },
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = itemLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { v ->
                DropdownMenuItem(text = { Text(itemLabel(v)) }, onClick = { onChange(v); expanded = false })
            }
        }
    }
}

@Composable
internal fun LabeledSwitch(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Duration in seconds with one decimal; stored as [Duration]. Empty = [emptyValue] (null when allowed). */
@Composable
internal fun DurationField(
    label: String,
    value: Duration?,
    onChange: (Duration?) -> Unit,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = false,
    minMillis: Long = 0,
    maxMillis: Long = Long.MAX_VALUE,
) {
    var text by rememberSaveable(value) { mutableStateOf(value?.let { formatSeconds(it) } ?: "") }
    val parsed = parseSeconds(text)
    val invalid = text.isNotBlank() && (parsed == null || parsed.inWholeMilliseconds !in minMillis..maxMillis) ||
        (text.isBlank() && !allowEmpty)
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val d = parseSeconds(t)
            when {
                t.isBlank() && allowEmpty -> onChange(null)
                d != null && d.inWholeMilliseconds in minMillis..maxMillis -> onChange(d)
            }
        },
        label = { Text(label) },
        suffix = { Text(stringResource(R.string.macro_unit_seconds)) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

internal fun formatSeconds(d: Duration): String {
    val ms = d.inWholeMilliseconds
    return if (ms % 1000L == 0L) (ms / 1000L).toString() else String.format(Locale.ROOT, "%.1f", ms / 1000.0)
}

internal fun parseSeconds(text: String): Duration? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (v < 0 || v.isNaN() || v.isInfinite()) return null
    return (v * 1000).toLong().milliseconds
}

@Composable
internal fun IntField(
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..Int.MAX_VALUE,
    allowEmpty: Boolean = false,
) {
    var text by rememberSaveable(value) { mutableStateOf(value?.toString() ?: "") }
    val parsed = text.trim().toIntOrNull()
    val invalid = (text.isNotBlank() && (parsed == null || parsed !in range)) || (text.isBlank() && !allowEmpty)
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val n = t.trim().toIntOrNull()
            when {
                t.isBlank() && allowEmpty -> onChange(null)
                n != null && n in range -> onChange(n)
            }
        },
        label = { Text(label) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Editor for the four [TextValue] shapes (secure values are stored via the step form, see [SecureTextField]). */
@Composable
internal fun TextValueEditor(
    label: String,
    value: TextValue,
    onChange: (TextValue) -> Unit,
    variables: List<String>,
    modifier: Modifier = Modifier,
    allowSecure: Boolean = false,
    onRequestSecure: (() -> Unit)? = null,
) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = value is TextValue.Literal,
                onClick = { onChange(TextValue.Literal(currentText(value))) },
                label = { Text(stringResource(R.string.macro_tv_literal)) },
            )
            FilterChip(
                selected = value is TextValue.Template,
                onClick = { onChange(TextValue.Template(currentText(value))) },
                label = { Text(stringResource(R.string.macro_tv_template)) },
            )
            FilterChip(
                selected = value is TextValue.Var,
                onClick = { onChange(TextValue.Var(variables.firstOrNull() ?: "")) },
                label = { Text(stringResource(R.string.macro_tv_variable)) },
            )
            if (allowSecure && onRequestSecure != null) {
                FilterChip(value is TextValue.Secure, onRequestSecure, { Text(stringResource(R.string.macro_tv_secure)) })
            }
        }
        when (value) {
            is TextValue.Literal -> OutlinedTextField(
                value.text,
                { onChange(TextValue.Literal(it)) },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
            is TextValue.Template -> OutlinedTextField(
                value.template,
                { onChange(TextValue.Template(it)) },
                label = { Text(label) },
                supportingText = { Text(stringResource(R.string.macro_tv_template_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            is TextValue.Var -> VariableNameField(label, value.name, variables) { onChange(TextValue.Var(it)) }
            is TextValue.Secure -> Text(
                stringResource(R.string.macro_tv_secure_stored),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

private fun currentText(v: TextValue): String = when (v) {
    is TextValue.Literal -> v.text
    is TextValue.Template -> v.template
    is TextValue.Var -> "{{${v.name}}}"
    is TextValue.Secure -> ""
}

@Composable
internal fun VariableNameField(label: String, value: String, variables: List<String>, onChange: (String) -> Unit) {
    Column {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (variables.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                variables.forEach { v -> FilterChip(v == value, { onChange(v) }, { Text(v) }) }
            }
        }
    }
}

/** FR-MAC-3 selector form. A `null` selector (when [optional]) means "the focused/any node". */
@Composable
internal fun SelectorEditor(
    selector: NodeSelector?,
    onChange: (NodeSelector?) -> Unit,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (optional) {
            LabeledSwitch(stringResource(R.string.macro_selector_specify), selector != null) {
                onChange(if (it) NodeSelector() else null)
            }
        }
        val s = selector ?: return@Column
        OutlinedTextField(
            s.text.orEmpty(),
            { onChange(s.copy(text = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.macro_selector_text)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            s.contentDescription.orEmpty(),
            { onChange(s.copy(contentDescription = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.macro_selector_desc)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            s.viewId.orEmpty(),
            { onChange(s.copy(viewId = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.macro_selector_view_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            s.className.orEmpty(),
            { onChange(s.copy(className = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.macro_selector_class)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            s.packageName.orEmpty(),
            { onChange(s.copy(packageName = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.macro_selector_package)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        EnumDropdown(stringResource(R.string.macro_selector_match), s.textMatch, TextMatch.entries, { onChange(s.copy(textMatch = it)) })
        IntField(stringResource(R.string.macro_selector_index), s.index, { onChange(s.copy(index = it ?: 0)) }, range = 0..MAX_INDEX)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(s.clickableAncestor, { onChange(s.copy(clickableAncestor = it)) })
            Text(stringResource(R.string.macro_selector_clickable_ancestor))
        }
        if (s.isEmpty) {
            Text(
                stringResource(R.string.macro_selector_empty),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_INDEX = 99

internal val DEFAULT_WAIT: Duration = 2.seconds
