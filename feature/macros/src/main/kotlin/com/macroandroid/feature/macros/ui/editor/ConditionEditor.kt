package com.macroandroid.feature.macros.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.macroandroid.automation.model.CompareOp
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.feature.macros.R

internal enum class ConditionKind { VAR_EQUALS, VAR_COMPARE, VAR_CONTAINS, NODE_EXISTS, APP_INSTALLED, NOT, ALL, ANY }

internal fun Condition.kind(): ConditionKind = when (this) {
    is Condition.VarEquals -> ConditionKind.VAR_EQUALS
    is Condition.VarCompare -> ConditionKind.VAR_COMPARE
    is Condition.VarContains -> ConditionKind.VAR_CONTAINS
    is Condition.NodeExists -> ConditionKind.NODE_EXISTS
    is Condition.AppInstalled -> ConditionKind.APP_INSTALLED
    is Condition.Not -> ConditionKind.NOT
    is Condition.All -> ConditionKind.ALL
    is Condition.Any -> ConditionKind.ANY
}

internal fun ConditionKind.default(): Condition = when (this) {
    ConditionKind.VAR_EQUALS -> Condition.VarEquals("", VariableValue.Str(""))
    ConditionKind.VAR_COMPARE -> Condition.VarCompare("", CompareOp.EQ, 0)
    ConditionKind.VAR_CONTAINS -> Condition.VarContains("", TextValue.Literal(""))
    ConditionKind.NODE_EXISTS -> Condition.NodeExists(NodeSelector())
    ConditionKind.APP_INSTALLED -> Condition.AppInstalled("")
    ConditionKind.NOT -> Condition.Not(Condition.AppInstalled(""))
    ConditionKind.ALL -> Condition.All(listOf(Condition.AppInstalled("")))
    ConditionKind.ANY -> Condition.Any(listOf(Condition.AppInstalled("")))
}

@Composable
internal fun conditionKindLabel(k: ConditionKind): String = stringResource(
    when (k) {
        ConditionKind.VAR_EQUALS -> R.string.macro_cond_var_equals
        ConditionKind.VAR_COMPARE -> R.string.macro_cond_var_compare
        ConditionKind.VAR_CONTAINS -> R.string.macro_cond_var_contains
        ConditionKind.NODE_EXISTS -> R.string.macro_cond_node_exists
        ConditionKind.APP_INSTALLED -> R.string.macro_cond_app_installed
        ConditionKind.NOT -> R.string.macro_cond_not
        ConditionKind.ALL -> R.string.macro_cond_all
        ConditionKind.ANY -> R.string.macro_cond_any
    },
)

private const val MAX_CONDITION_DEPTH = 3
private const val MAX_GROUP_SIZE = 5

/** Recursive condition form (depth-limited to keep the UI usable; the validator enforces the real limits). */
@Composable
internal fun ConditionEditor(
    condition: Condition,
    onChange: (Condition) -> Unit,
    variables: List<String>,
    modifier: Modifier = Modifier,
    depth: Int = 0,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val composite = setOf(ConditionKind.NOT, ConditionKind.ALL, ConditionKind.ANY)
        val kinds = if (depth >= MAX_CONDITION_DEPTH) ConditionKind.entries.filter { it !in composite } else ConditionKind.entries
        EnumDropdown(stringResource(R.string.macro_condition), condition.kind(), kinds, { onChange(it.default()) }) {
            conditionKindLabel(it)
        }
        when (condition) {
            is Condition.VarEquals -> {
                VariableNameField(stringResource(R.string.macro_variable_name), condition.name, variables) {
                    onChange(condition.copy(name = it))
                }
                VariableValueEditor(condition.value) { onChange(condition.copy(value = it)) }
            }
            is Condition.VarCompare -> {
                VariableNameField(stringResource(R.string.macro_variable_name), condition.name, variables) {
                    onChange(condition.copy(name = it))
                }
                EnumDropdown(stringResource(R.string.macro_cond_operator), condition.op, CompareOp.entries, {
                    onChange(condition.copy(op = it))
                }) { opSymbol(it) }
                OutlinedTextField(
                    condition.value.toString(),
                    { t -> t.toLongOrNull()?.let { onChange(condition.copy(value = it)) } },
                    label = { Text(stringResource(R.string.macro_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is Condition.VarContains -> {
                VariableNameField(stringResource(R.string.macro_variable_name), condition.name, variables) {
                    onChange(condition.copy(name = it))
                }
                TextValueEditor(
                    stringResource(R.string.macro_cond_needle),
                    condition.needle,
                    { onChange(condition.copy(needle = it)) },
                    variables,
                )
            }
            is Condition.NodeExists -> SelectorEditor(condition.selector, { onChange(condition.copy(selector = it ?: NodeSelector())) })
            is Condition.AppInstalled -> PackageNameField(condition.packageName) { onChange(condition.copy(packageName = it)) }
            is Condition.Not -> Nested(depth) {
                ConditionEditor(condition.inner, { onChange(condition.copy(inner = it)) }, variables, depth = depth + 1)
            }
            is Condition.All -> GroupEditor(condition.conditions, { onChange(condition.copy(conditions = it)) }, variables, depth)
            is Condition.Any -> GroupEditor(condition.conditions, { onChange(condition.copy(conditions = it)) }, variables, depth)
        }
    }
}

@Composable
private fun GroupEditor(items: List<Condition>, onChange: (List<Condition>) -> Unit, variables: List<String>, depth: Int) {
    items.forEachIndexed { i, c ->
        Nested(depth) {
            ConditionEditor(c, { n -> onChange(items.toMutableList().also { it[i] = n }) }, variables, depth = depth + 1)
            if (items.size > 1) {
                TextButton(onClick = { onChange(items.toMutableList().also { it.removeAt(i) }) }) {
                    Text(stringResource(R.string.macro_remove))
                }
            }
        }
    }
    if (items.size < MAX_GROUP_SIZE) {
        TextButton(onClick = { onChange(items + Condition.AppInstalled("")) }) { Text(stringResource(R.string.macro_cond_add)) }
    }
}

@Composable
private fun Nested(depth: Int, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = (8 * (depth + 1)).dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
    ) { content() }
}

@Composable
internal fun PackageNameField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(stringResource(R.string.macro_package_name)) },
        singleLine = true,
        placeholder = { Text("com.example.app") },
        modifier = Modifier.fillMaxWidth(),
    )
}

internal enum class ValueKind { TEXT, NUMBER, BOOLEAN }

@Composable
internal fun VariableValueEditor(value: VariableValue, onChange: (VariableValue) -> Unit) {
    val kind = when (value) {
        is VariableValue.Str -> ValueKind.TEXT
        is VariableValue.Int64 -> ValueKind.NUMBER
        is VariableValue.Bool -> ValueKind.BOOLEAN
        is VariableValue.Secure -> ValueKind.TEXT
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EnumDropdown(
            stringResource(R.string.macro_value_type),
            kind,
            ValueKind.entries,
            {
                onChange(
                    when (it) {
                        ValueKind.TEXT -> VariableValue.Str("")
                        ValueKind.NUMBER -> VariableValue.Int64(0)
                        ValueKind.BOOLEAN -> VariableValue.Bool(false)
                    },
                )
            },
            Modifier.weight(1f),
        )
    }
    when (value) {
        is VariableValue.Str -> OutlinedTextField(
            value.value,
            { onChange(VariableValue.Str(it)) },
            label = { Text(stringResource(R.string.macro_value)) },
            modifier = Modifier.fillMaxWidth(),
        )
        is VariableValue.Int64 -> OutlinedTextField(
            value.value.toString(),
            { t -> t.toLongOrNull()?.let { onChange(VariableValue.Int64(it)) } },
            label = { Text(stringResource(R.string.macro_value)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        is VariableValue.Bool -> LabeledSwitch(stringResource(R.string.macro_value), value.value) { onChange(VariableValue.Bool(it)) }
        is VariableValue.Secure -> Text(stringResource(R.string.macro_tv_secure_stored))
    }
}

private fun opSymbol(op: CompareOp): String = when (op) {
    CompareOp.LT -> "<"
    CompareOp.LE -> "≤"
    CompareOp.EQ -> "="
    CompareOp.NE -> "≠"
    CompareOp.GE -> "≥"
    CompareOp.GT -> ">"
}
