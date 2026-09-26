package com.macroandroid.feature.macros.domain

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.Expression
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import kotlin.time.Duration

/**
 * Renders a macro as numbered, TalkBack-friendly sentences (FR-MAC-6). Pure Kotlin so it is unit-testable;
 * the UI localises the connective words through [Words].
 */
object MacroSentences {

    data class Words(
        val launch: String = "Launch",
        val openUrl: String = "Open URL",
        val wait: String = "Wait",
        val press: String = "Press",
        val click: String = "Click",
        val longClick: String = "Long-press",
        val scroll: String = "Scroll",
        val enterText: String = "Enter text into",
        val enterSecret: String = "Enter a secret value into",
        val waitFor: String = "Wait until",
        val appears: String = "appears",
        val disappears: String = "disappears",
        val notify: String = "Show notification",
        val set: String = "Set",
        val ifWord: String = "If",
        val thenWord: String = "then",
        val elseWord: String = "otherwise",
        val repeat: String = "Repeat",
        val times: String = "times",
        val whileWord: String = "while",
        val parallel: String = "In parallel",
        val log: String = "Log",
        val stop: String = "Stop",
        val success: String = "with success",
        val failure: String = "with failure",
        val disabled: String = "(disabled)",
        val node: String = "the element",
        val secret: String = "•••••",
        val variable: String = "variable",
        val exists: String = "exists",
        val installed: String = "is installed",
        val not: String = "not",
        val and: String = "and",
        val or: String = "or",
        val currentTime: String = "the current time",
        val textOf: String = "the text of",
    )

    fun describe(macro: Macro, words: Words = Words()): List<String> {
        val out = ArrayList<String>()
        var n = 1
        fun walk(steps: List<MacroStep>, indent: String) {
            for (step in steps) {
                val prefix = "$indent${n++}. "
                val suffix = if (step.enabled) "" else " ${words.disabled}"
                out += prefix + sentence(step.action, words) + suffix
                when (val a = step.action) {
                    is ActionParameters.If -> {
                        walk(a.then, "$indent    ")
                        if (a.`else`.isNotEmpty()) {
                            out += "$indent    ${words.elseWord}:"
                            walk(a.`else`, "$indent    ")
                        }
                    }
                    is ActionParameters.Repeat -> walk(a.body, "$indent    ")
                    is ActionParameters.Parallel -> walk(a.children, "$indent    ")
                    else -> Unit
                }
            }
        }
        walk(macro.steps, "")
        return out
    }

    @Suppress("CyclomaticComplexMethod") // one branch per action type
    fun sentence(action: ActionParameters, w: Words = Words()): String = when (action) {
        is ActionParameters.LaunchApp -> "${w.launch} ${action.packageName}."
        is ActionParameters.OpenUrl -> "${w.openUrl} ${text(action.url, w)}."
        is ActionParameters.Wait -> "${w.wait} ${duration(action.duration)}."
        is ActionParameters.GlobalAction -> "${w.press} ${action.action.name.lowercase().replace('_', ' ')}."
        is ActionParameters.ClickNode -> "${if (action.longClick) w.longClick else w.click} ${selector(action.selector, w)}."
        is ActionParameters.ScrollNode ->
            "${w.scroll} ${action.direction.name.lowercase()} ×${action.times}" +
                (action.selector?.let { " ${selector(it, w)}" } ?: "") + "."
        is ActionParameters.EnterText ->
            "${if (action.sensitive || action.text is TextValue.Secure) w.enterSecret else w.enterText} " +
                "${action.selector?.let { selector(it, w) } ?: w.node}" +
                (if (action.sensitive || action.text is TextValue.Secure) "" else ": ${text(action.text, w)}") + "."
        is ActionParameters.WaitForNode ->
            "${w.waitFor} ${selector(action.selector, w)} " +
                "${if (action.state == com.macroandroid.automation.model.NodeState.PRESENT) w.appears else w.disappears}."
        is ActionParameters.SendNotification -> "${w.notify} “${text(action.title, w)}”."
        is ActionParameters.SetVariable -> "${w.set} ${w.variable} ${action.name} = ${valueOrExpr(action, w)}."
        is ActionParameters.If -> "${w.ifWord} ${condition(action.condition, w)}, ${w.thenWord}:"
        is ActionParameters.Repeat -> when {
            action.count != null -> "${w.repeat} ${action.count} ${w.times}:"
            else -> "${w.repeat} ${w.whileWord} ${action.whileCondition?.let { condition(it, w) } ?: "…"} (≤ ${action.maxIterations}):"
        }
        is ActionParameters.Parallel -> "${w.parallel}:"
        is ActionParameters.Log -> "${w.log} “${text(action.message, w)}”."
        is ActionParameters.Stop -> "${w.stop} ${if (action.success) w.success else w.failure}${action.message?.let { ": $it" } ?: ""}."
    }

    fun selector(s: NodeSelector, w: Words): String {
        val parts = buildList {
            s.text?.let { add("“$it”") }
            s.contentDescription?.let { add("[$it]") }
            s.viewId?.let { add("#$it") }
            s.className?.let { add(it.substringAfterLast('.')) }
        }
        return if (parts.isEmpty()) w.node else parts.joinToString(" ") + (if (s.index > 0) " (#${s.index + 1})" else "")
    }

    fun condition(c: Condition, w: Words): String = when (c) {
        is Condition.VarEquals -> "${c.name} = ${value(c.value, w)}"
        is Condition.VarCompare -> "${c.name} ${op(c.op)} ${c.value}"
        is Condition.VarContains -> "${c.name} ∋ ${text(c.needle, w)}"
        is Condition.NodeExists -> "${selector(c.selector, w)} ${w.exists}"
        is Condition.AppInstalled -> "${c.packageName} ${w.installed}"
        is Condition.Not -> "${w.not} (${condition(c.inner, w)})"
        is Condition.All -> c.conditions.joinToString(" ${w.and} ") { "(${condition(it, w)})" }
        is Condition.Any -> c.conditions.joinToString(" ${w.or} ") { "(${condition(it, w)})" }
    }

    fun text(t: TextValue, w: Words): String = when (t) {
        is TextValue.Literal -> t.text
        is TextValue.Var -> "{{${t.name}}}"
        is TextValue.Secure -> w.secret
        is TextValue.Template -> t.template
    }

    fun value(v: VariableValue, w: Words): String = when (v) {
        is VariableValue.Str -> "“${v.value}”"
        is VariableValue.Int64 -> v.value.toString()
        is VariableValue.Bool -> v.value.toString()
        is VariableValue.Secure -> w.secret
    }

    fun duration(d: Duration): String = d.toString()

    private fun valueOrExpr(a: ActionParameters.SetVariable, w: Words): String = when (val e = a.expression) {
        null -> a.value?.let { value(it, w) } ?: "?"
        is Expression.Increment -> "${a.name} + ${e.by}"
        is Expression.Concat -> e.parts.joinToString(" + ") { text(it, w) }
        Expression.Now -> w.currentTime
        is Expression.NodeText -> "${w.textOf} ${selector(e.selector, w)}"
    }

    private fun op(o: com.macroandroid.automation.model.CompareOp) = when (o) {
        com.macroandroid.automation.model.CompareOp.LT -> "<"
        com.macroandroid.automation.model.CompareOp.LE -> "≤"
        com.macroandroid.automation.model.CompareOp.EQ -> "="
        com.macroandroid.automation.model.CompareOp.NE -> "≠"
        com.macroandroid.automation.model.CompareOp.GE -> "≥"
        com.macroandroid.automation.model.CompareOp.GT -> ">"
    }
}
