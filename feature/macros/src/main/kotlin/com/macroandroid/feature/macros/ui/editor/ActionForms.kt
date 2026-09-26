package com.macroandroid.feature.macros.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.Expression
import com.macroandroid.automation.model.GlobalActionKind
import com.macroandroid.automation.model.LogLevelParam
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.NodeState
import com.macroandroid.automation.model.ScrollDirection
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.feature.macros.R
import kotlin.time.Duration.Companion.seconds

/** Catalogue of creatable actions (FR-MAC-3); order = picker order. */
internal enum class ActionKind(val needsA11y: Boolean, val container: Boolean = false) {
    LAUNCH_APP(false),
    OPEN_URL(false),
    WAIT(false),
    GLOBAL_ACTION(true),
    CLICK_NODE(true),
    SCROLL_NODE(true),
    ENTER_TEXT(true),
    WAIT_FOR_NODE(true),
    SEND_NOTIFICATION(false),
    SET_VARIABLE(false),
    IF(false, container = true),
    REPEAT(false, container = true),
    PARALLEL(false, container = true),
    LOG(false),
    STOP(false),
    ;

    fun create(): ActionParameters = when (this) {
        LAUNCH_APP -> ActionParameters.LaunchApp(packageName = "")
        OPEN_URL -> ActionParameters.OpenUrl(url = TextValue.Literal("https://"))
        WAIT -> ActionParameters.Wait(DEFAULT_WAIT)
        GLOBAL_ACTION -> ActionParameters.GlobalAction(GlobalActionKind.BACK)
        CLICK_NODE -> ActionParameters.ClickNode(NodeSelector())
        SCROLL_NODE -> ActionParameters.ScrollNode()
        ENTER_TEXT -> ActionParameters.EnterText(text = TextValue.Literal(""))
        WAIT_FOR_NODE -> ActionParameters.WaitForNode(NodeSelector())
        SEND_NOTIFICATION -> ActionParameters.SendNotification(TextValue.Literal(""), TextValue.Literal(""))
        SET_VARIABLE -> ActionParameters.SetVariable(name = "", value = VariableValue.Str(""))
        IF -> ActionParameters.If(Condition.AppInstalled(""), then = emptyList())
        REPEAT -> ActionParameters.Repeat(count = 2, body = emptyList())
        PARALLEL -> ActionParameters.Parallel(children = emptyList())
        LOG -> ActionParameters.Log(message = TextValue.Literal(""))
        STOP -> ActionParameters.Stop()
    }

    companion object {
        fun of(a: ActionParameters): ActionKind = when (a) {
            is ActionParameters.LaunchApp -> LAUNCH_APP
            is ActionParameters.OpenUrl -> OPEN_URL
            is ActionParameters.Wait -> WAIT
            is ActionParameters.GlobalAction -> GLOBAL_ACTION
            is ActionParameters.ClickNode -> CLICK_NODE
            is ActionParameters.ScrollNode -> SCROLL_NODE
            is ActionParameters.EnterText -> ENTER_TEXT
            is ActionParameters.WaitForNode -> WAIT_FOR_NODE
            is ActionParameters.SendNotification -> SEND_NOTIFICATION
            is ActionParameters.SetVariable -> SET_VARIABLE
            is ActionParameters.If -> IF
            is ActionParameters.Repeat -> REPEAT
            is ActionParameters.Parallel -> PARALLEL
            is ActionParameters.Log -> LOG
            is ActionParameters.Stop -> STOP
        }
    }
}

@Composable
internal fun actionKindLabel(k: ActionKind): String = stringResource(
    when (k) {
        ActionKind.LAUNCH_APP -> R.string.macro_action_launch_app
        ActionKind.OPEN_URL -> R.string.macro_action_open_url
        ActionKind.WAIT -> R.string.macro_action_wait
        ActionKind.GLOBAL_ACTION -> R.string.macro_action_global
        ActionKind.CLICK_NODE -> R.string.macro_action_click
        ActionKind.SCROLL_NODE -> R.string.macro_action_scroll
        ActionKind.ENTER_TEXT -> R.string.macro_action_enter_text
        ActionKind.WAIT_FOR_NODE -> R.string.macro_action_wait_for
        ActionKind.SEND_NOTIFICATION -> R.string.macro_action_notify
        ActionKind.SET_VARIABLE -> R.string.macro_action_set_variable
        ActionKind.IF -> R.string.macro_action_if
        ActionKind.REPEAT -> R.string.macro_action_repeat
        ActionKind.PARALLEL -> R.string.macro_action_parallel
        ActionKind.LOG -> R.string.macro_action_log
        ActionKind.STOP -> R.string.macro_action_stop
    },
)

/**
 * Renders the parameter form for one action. Child step lists of containers are edited in the step list itself
 * (nested rows), so only the scalar parameters appear here.
 */
@Composable
internal fun ActionForm(
    action: ActionParameters,
    onChange: (ActionParameters) -> Unit,
    variables: List<String>,
    onRequestSecure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (action) {
            is ActionParameters.LaunchApp -> LaunchAppForm(action, onChange)
            is ActionParameters.OpenUrl -> OpenUrlForm(action, onChange, variables)
            is ActionParameters.Wait -> DurationField(stringResource(R.string.macro_field_duration), action.duration, {
                it?.let { d -> onChange(action.copy(duration = d)) }
            }, minMillis = 1, maxMillis = MacroLimits.WAIT_MAX.inWholeMilliseconds)
            is ActionParameters.GlobalAction -> EnumDropdown(
                stringResource(R.string.macro_field_global_action),
                action.action,
                GlobalActionKind.entries,
                { onChange(action.copy(action = it)) },
            )
            is ActionParameters.ClickNode -> ClickForm(action, onChange)
            is ActionParameters.ScrollNode -> ScrollForm(action, onChange)
            is ActionParameters.EnterText -> EnterTextForm(action, onChange, variables, onRequestSecure)
            is ActionParameters.WaitForNode -> {
                SelectorEditor(action.selector, { onChange(action.copy(selector = it ?: NodeSelector())) })
                EnumDropdown(stringResource(R.string.macro_field_node_state), action.state, NodeState.entries, {
                    onChange(action.copy(state = it))
                })
            }
            is ActionParameters.SendNotification -> NotificationForm(action, onChange, variables)
            is ActionParameters.SetVariable -> SetVariableForm(action, onChange, variables)
            is ActionParameters.If -> ConditionEditor(action.condition, { onChange(action.copy(condition = it)) }, variables)
            is ActionParameters.Repeat -> RepeatForm(action, onChange, variables)
            is ActionParameters.Parallel -> LabeledSwitch(stringResource(R.string.macro_field_fail_fast), action.failFast) {
                onChange(action.copy(failFast = it))
            }
            is ActionParameters.Log -> {
                EnumDropdown(stringResource(R.string.macro_field_level), action.level, LogLevelParam.entries, {
                    onChange(action.copy(level = it))
                })
                TextValueEditor(
                    stringResource(R.string.macro_field_message),
                    action.message,
                    { onChange(action.copy(message = it)) },
                    variables,
                )
            }
            is ActionParameters.Stop -> {
                LabeledSwitch(stringResource(R.string.macro_field_stop_success), action.success) { onChange(action.copy(success = it)) }
                OutlinedTextField(
                    action.message.orEmpty(),
                    { onChange(action.copy(message = it.ifBlank { null })) },
                    label = { Text(stringResource(R.string.macro_field_message)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LaunchAppForm(a: ActionParameters.LaunchApp, onChange: (ActionParameters) -> Unit) {
    PackageNameField(a.packageName) { onChange(a.copy(packageName = it.trim())) }
    LabeledSwitch(stringResource(R.string.macro_field_wait_for_window), a.waitForWindow) {
        onChange(a.copy(waitForWindow = it))
    }
    if (a.waitForWindow) {
        DurationField(stringResource(R.string.macro_field_window_wait), a.windowWait, {
            it?.let { d -> onChange(a.copy(windowWait = d)) }
        }, minMillis = 500, maxMillis = 60.seconds.inWholeMilliseconds)
    }
}

@Composable
private fun OpenUrlForm(a: ActionParameters.OpenUrl, onChange: (ActionParameters) -> Unit, variables: List<String>) {
    TextValueEditor(stringResource(R.string.macro_field_url), a.url, { onChange(a.copy(url = it)) }, variables)
    OutlinedTextField(
        a.preferPackage.orEmpty(),
        { onChange(a.copy(preferPackage = it.trim().ifBlank { null })) },
        label = { Text(stringResource(R.string.macro_field_prefer_package)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ClickForm(a: ActionParameters.ClickNode, onChange: (ActionParameters) -> Unit) {
    SelectorEditor(a.selector, { onChange(a.copy(selector = it ?: NodeSelector())) })
    LabeledSwitch(stringResource(R.string.macro_field_long_click), a.longClick) { onChange(a.copy(longClick = it)) }
    LabeledSwitch(stringResource(R.string.macro_field_require_visible), a.requireVisible) {
        onChange(a.copy(requireVisible = it))
    }
}

@Composable
private fun ScrollForm(a: ActionParameters.ScrollNode, onChange: (ActionParameters) -> Unit) {
    SelectorEditor(a.selector, { onChange(a.copy(selector = it)) }, optional = true)
    EnumDropdown(stringResource(R.string.macro_field_direction), a.direction, ScrollDirection.entries, {
        onChange(a.copy(direction = it))
    })
    IntField(
        stringResource(R.string.macro_field_times),
        a.times,
        { onChange(a.copy(times = it ?: 1)) },
        range = 1..MacroLimits.SCROLL_TIMES_MAX,
    )
}

@Composable
private fun EnterTextForm(
    a: ActionParameters.EnterText,
    onChange: (ActionParameters) -> Unit,
    variables: List<String>,
    onRequestSecure: () -> Unit,
) {
    SelectorEditor(a.selector, { onChange(a.copy(selector = it)) }, optional = true)
    TextValueEditor(
        stringResource(R.string.macro_field_text),
        a.text,
        { onChange(a.copy(text = it, sensitive = it is TextValue.Secure)) },
        variables,
        allowSecure = true,
        onRequestSecure = onRequestSecure,
    )
    LabeledSwitch(stringResource(R.string.macro_field_append), a.append) { onChange(a.copy(append = it)) }
}

@Composable
private fun NotificationForm(a: ActionParameters.SendNotification, onChange: (ActionParameters) -> Unit, variables: List<String>) {
    TextValueEditor(stringResource(R.string.macro_field_title), a.title, { onChange(a.copy(title = it)) }, variables)
    TextValueEditor(stringResource(R.string.macro_field_text), a.text, { onChange(a.copy(text = it)) }, variables)
    LabeledSwitch(stringResource(R.string.macro_field_tap_opens_macro), a.tapOpensMacro) {
        onChange(a.copy(tapOpensMacro = it))
    }
}

private enum class SetMode { VALUE, INCREMENT, CONCAT, NOW, NODE_TEXT }

@Composable
private fun SetVariableForm(a: ActionParameters.SetVariable, onChange: (ActionParameters) -> Unit, variables: List<String>) {
    VariableNameField(stringResource(R.string.macro_variable_name), a.name, variables) { onChange(a.copy(name = it.trim())) }
    val mode = when (a.expression) {
        null -> SetMode.VALUE
        is Expression.Increment -> SetMode.INCREMENT
        is Expression.Concat -> SetMode.CONCAT
        Expression.Now -> SetMode.NOW
        is Expression.NodeText -> SetMode.NODE_TEXT
    }
    EnumDropdown(stringResource(R.string.macro_field_set_mode), mode, SetMode.entries, { m ->
        onChange(
            when (m) {
                SetMode.VALUE -> a.copy(value = a.value ?: VariableValue.Str(""), expression = null)
                SetMode.INCREMENT -> a.copy(value = null, expression = Expression.Increment())
                SetMode.CONCAT -> a.copy(value = null, expression = Expression.Concat(listOf(TextValue.Literal(""))))
                SetMode.NOW -> a.copy(value = null, expression = Expression.Now)
                SetMode.NODE_TEXT -> a.copy(value = null, expression = Expression.NodeText(NodeSelector()))
            },
        )
    })
    when (val e = a.expression) {
        null -> VariableValueEditor(a.value ?: VariableValue.Str("")) { onChange(a.copy(value = it)) }
        is Expression.Increment -> OutlinedTextField(
            e.by.toString(),
            { t -> t.toLongOrNull()?.let { onChange(a.copy(expression = Expression.Increment(it))) } },
            label = { Text(stringResource(R.string.macro_field_increment_by)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is Expression.Concat -> ConcatEditor(e, { onChange(a.copy(expression = it)) }, variables)
        Expression.Now -> Unit
        is Expression.NodeText -> SelectorEditor(e.selector, {
            onChange(a.copy(expression = Expression.NodeText(it ?: NodeSelector())))
        })
    }
}


@Composable
private fun ConcatEditor(e: Expression.Concat, onChange: (Expression.Concat) -> Unit, variables: List<String>) {
    e.parts.forEachIndexed { i, part ->
        TextValueEditor(
            stringResource(R.string.macro_field_part, i + 1),
            part,
            { p -> onChange(Expression.Concat(e.parts.toMutableList().also { it[i] = p })) },
            variables,
        )
    }
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (e.parts.size < MacroLimits.CONCAT_PARTS_MAX) {
            androidx.compose.material3.TextButton(onClick = { onChange(Expression.Concat(e.parts + TextValue.Literal(""))) }) {
                Text(stringResource(R.string.macro_field_add_part))
            }
        }
        if (e.parts.size > 1) {
            androidx.compose.material3.TextButton(onClick = { onChange(Expression.Concat(e.parts.dropLast(1))) }) {
                Text(stringResource(R.string.macro_remove))
            }
        }
    }
}

@Composable
private fun RepeatForm(a: ActionParameters.Repeat, onChange: (ActionParameters) -> Unit, variables: List<String>) {
    val byCount = a.whileCondition == null
    LabeledSwitch(stringResource(R.string.macro_field_repeat_while), !byCount) { whileMode ->
        onChange(
            if (whileMode) {
                a.copy(
                    count = null,
                    whileCondition = Condition.AppInstalled(""),
                    maxIterations = a.maxIterations ?: DEFAULT_MAX_ITERATIONS,
                )
            } else {
                a.copy(count = a.maxIterations ?: DEFAULT_COUNT, whileCondition = null, maxIterations = null)
            },
        )
    }
    if (byCount) {
        IntField(
            stringResource(R.string.macro_field_count),
            a.count,
            { onChange(a.copy(count = it ?: 1)) },
            range = 1..MacroLimits.REPEAT_MAX,
        )
    } else {
        ConditionEditor(a.whileCondition ?: Condition.AppInstalled(""), { onChange(a.copy(whileCondition = it)) }, variables)
        IntField(
            stringResource(R.string.macro_field_max_iterations),
            a.maxIterations,
            { onChange(a.copy(maxIterations = it ?: DEFAULT_MAX_ITERATIONS)) },
            range = 1..MacroLimits.REPEAT_MAX,
        )
    }
    DurationField(stringResource(R.string.macro_field_delay_between), a.delayBetween, {
        it?.let { d -> onChange(a.copy(delayBetween = d)) }
    }, maxMillis = MacroLimits.WAIT_MAX.inWholeMilliseconds)
}

private const val DEFAULT_COUNT = 2
private const val DEFAULT_MAX_ITERATIONS = 10
