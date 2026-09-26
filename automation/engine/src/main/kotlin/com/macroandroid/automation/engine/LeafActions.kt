package com.macroandroid.automation.engine

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.CompareOp
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.Expression
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.NodeState
import com.macroandroid.automation.model.RuntimeValue
import com.macroandroid.automation.port.UiNode
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.LogLevel
import com.macroandroid.core.common.logging.Redactor
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementations of every leaf action in schema v1. Each is a small suspend function over the ports;
 * they return [ActionResult] and never throw (except cancellation).
 */
object LeafActions {

    private val POLL_INTERVAL = 250.milliseconds
    private val URL_PATTERN = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE)

    @Suppress("CyclomaticComplexMethod")
    suspend fun execute(ctx: ActionContext): ActionResult = when (val a = ctx.action) {
        is ActionParameters.LaunchApp -> launchApp(ctx, a)
        is ActionParameters.OpenUrl -> openUrl(ctx, a)
        is ActionParameters.Wait -> {
            delay(a.duration)
            ActionResult.Success("waited ${a.duration}")
        }
        is ActionParameters.GlobalAction -> ctx.ports.accessibility.performGlobalAction(a.action).toResult(a.action.name)
        is ActionParameters.ClickNode -> clickNode(ctx, a)
        is ActionParameters.ScrollNode -> scrollNode(ctx, a)
        is ActionParameters.EnterText -> enterText(ctx, a)
        is ActionParameters.WaitForNode -> waitForNode(ctx, a)
        is ActionParameters.SendNotification -> sendNotification(ctx, a)
        is ActionParameters.SetVariable -> setVariable(ctx, a)
        is ActionParameters.Log -> logAction(ctx, a)
        is ActionParameters.Stop -> ActionResult.Stop(a.success, a.message)
        is ActionParameters.If, is ActionParameters.Repeat, is ActionParameters.Parallel ->
            ActionResult.Failure(AppError(ErrorCode.INVARIANT_VIOLATION, "container executed as leaf"))
    }

    // ---- app & url --------------------------------------------------------------------------

    private suspend fun launchApp(ctx: ActionContext, a: ActionParameters.LaunchApp): ActionResult {
        val launcher = ctx.ports.launcher
        if (!launcher.isInstalled(a.packageName)) {
            return ActionResult.Failure(AppError(ErrorCode.APP_NOT_INSTALLED, a.packageName))
        }
        when (val r = launcher.launch(a.packageName)) {
            is AppResult.Err -> return ActionResult.Failure(r.error)
            is AppResult.Ok -> Unit
        }
        if (a.waitForWindow) {
            val ok = launcher.awaitWindow(a.packageName, a.windowWait.coerceAtMost(ctx.timeout - 500.milliseconds))
            if (!ok) return ActionResult.Failure(AppError(ErrorCode.APP_WINDOW_TIMEOUT, a.packageName))
        }
        return ActionResult.Success("launched ${a.packageName}")
    }

    private suspend fun openUrl(ctx: ActionContext, a: ActionParameters.OpenUrl): ActionResult {
        val (url, secret) = when (val r = ctx.variables.resolveString(a.url, ctx.secureValues)) {
            is AppResult.Err -> return ActionResult.Failure(r.error)
            is AppResult.Ok -> r.value
        }
        if (secret) return ActionResult.Failure(AppError(ErrorCode.URL_SCHEME_NOT_ALLOWED, "secure value as URL"))
        if (url.length > com.macroandroid.automation.model.MacroLimits.URL_MAX || !URL_PATTERN.matches(url)) {
            return ActionResult.Failure(AppError(ErrorCode.URL_SCHEME_NOT_ALLOWED, Redactor.redactUrl(url)))
        }
        return ctx.ports.launcher.openUrl(url, a.preferPackage).toResult("opened ${Redactor.redactUrl(url)}")
    }

    // ---- accessibility ----------------------------------------------------------------------

    private suspend fun clickNode(ctx: ActionContext, a: ActionParameters.ClickNode): ActionResult {
        val node = when (val r = findActionable(ctx, a.selector, requireVisible = true)) {
            is AppResult.Err -> return ActionResult.Failure(r.error)
            is AppResult.Ok -> r.value
        }
        if (a.longClick && !node.isLongClickable) return ActionResult.Failure(AppError(ErrorCode.NODE_NOT_CLICKABLE, "longClick"))
        if (!a.longClick && !node.isClickable) return ActionResult.Failure(AppError(ErrorCode.NODE_NOT_CLICKABLE))
        return ctx.ports.accessibility.click(node, a.longClick).toResult("clicked ${a.selector.describe()}")
    }

    private suspend fun scrollNode(ctx: ActionContext, a: ActionParameters.ScrollNode): ActionResult {
        val node: UiNode = if (a.selector == null) {
            ctx.ports.accessibility.firstScrollable(null)
                ?: return ActionResult.Failure(AppError(ErrorCode.NODE_NOT_SCROLLABLE, "no scrollable node"))
        } else {
            when (val r = findActionable(ctx, a.selector, requireVisible = true)) {
                is AppResult.Err -> return ActionResult.Failure(r.error)
                is AppResult.Ok -> r.value
            }
        }
        if (!node.isScrollable) return ActionResult.Failure(AppError(ErrorCode.NODE_NOT_SCROLLABLE))
        repeat(a.times) { i ->
            coroutineContext.ensureActive()
            when (val r = ctx.ports.accessibility.scroll(node, a.direction)) {
                is AppResult.Err -> return if (i == 0) ActionResult.Failure(r.error) else ActionResult.Success("scrolled $i")
                is AppResult.Ok -> Unit
            }
            if (i < a.times - 1) delay(POLL_INTERVAL)
        }
        return ActionResult.Success("scrolled ${a.times}× ${a.direction}")
    }

    @Suppress("ReturnCount") // each early return maps to a distinct documented failure code
    private suspend fun enterText(ctx: ActionContext, a: ActionParameters.EnterText): ActionResult {
        val (text, secret) = when (val r = ctx.variables.resolveString(a.text, ctx.secureValues)) {
            is AppResult.Err -> return ActionResult.Failure(r.error)
            is AppResult.Ok -> r.value
        }
        if (secret && !a.sensitive) return ActionResult.Failure(AppError(ErrorCode.SENSITIVE_FLAG_REQUIRED))
        val node: UiNode = if (a.selector == null) {
            ctx.ports.accessibility.focusedEditable()
                ?: return ActionResult.Failure(AppError(ErrorCode.NODE_NOT_EDITABLE, "no focused editable"))
        } else {
            when (val r = findActionable(ctx, a.selector, requireVisible = true)) {
                is AppResult.Err -> return ActionResult.Failure(r.error)
                is AppResult.Ok -> r.value
            }
        }
        if (node.isPassword) return ActionResult.Failure(AppError(ErrorCode.NODE_IS_PASSWORD))
        if (!node.isEditable) return ActionResult.Failure(AppError(ErrorCode.NODE_NOT_EDITABLE))
        val summary = if (a.sensitive || secret) "entered ${Redactor.REDACTED}" else "entered \"${Redactor.clipForLog(text, 40)}\""
        return ctx.ports.accessibility.setText(node, text, a.append).toResult(summary)
    }

    private suspend fun waitForNode(ctx: ActionContext, a: ActionParameters.WaitForNode): ActionResult {
        val deadline = ctx.clock.now() + (ctx.timeout - 300.milliseconds).coerceAtLeast(POLL_INTERVAL)
        while (true) {
            coroutineContext.ensureActive()
            val present = ctx.ports.accessibility.findNodes(a.selector).any { it.isVisible }
            val satisfied = (a.state == NodeState.PRESENT) == present
            if (satisfied) return ActionResult.Success("${a.selector.describe()} is ${a.state.name.lowercase()}")
            if (ctx.clock.now() >= deadline) {
                return ActionResult.Failure(
                    AppError(
                        if (a.state == NodeState.PRESENT) ErrorCode.NODE_NOT_FOUND else ErrorCode.NODE_NOT_VISIBLE,
                        a.selector.describe(),
                    ),
                )
            }
            delay(POLL_INTERVAL)
        }
    }

    /**
     * Finds the node matching [selector] (the gateway already applied `clickableAncestor`), then applies
     * the index, password and visibility rules.
     */
    private suspend fun findActionable(
        ctx: ActionContext,
        selector: NodeSelector,
        requireVisible: Boolean,
    ): AppResult<UiNode> {
        val nodes = ctx.ports.accessibility.findNodes(selector)
        if (nodes.isEmpty()) return AppResult.err(ErrorCode.NODE_NOT_FOUND, selector.describe())
        if (selector.index >= nodes.size) return AppResult.err(ErrorCode.NODE_AMBIGUOUS, "${nodes.size} matches, index ${selector.index}")
        val node = nodes[selector.index]
        if (node.isPassword) return AppResult.err(ErrorCode.NODE_IS_PASSWORD)
        if (requireVisible && !node.isVisible) return AppResult.err(ErrorCode.NODE_NOT_VISIBLE, selector.describe())
        return AppResult.ok(node)
    }

    // ---- background-safe ------------------------------------------------------------------

    private suspend fun sendNotification(ctx: ActionContext, a: ActionParameters.SendNotification): ActionResult {
        val title = when (val r = ctx.variables.resolveString(a.title, ctx.secureValues)) {
            is AppResult.Err -> return ActionResult.Failure(r.error)
            is AppResult.Ok -> if (r.value.second) Redactor.REDACTED else r.value.first
        }
        val text = when (val r = ctx.variables.resolveString(a.text, ctx.secureValues)) {
            is AppResult.Err -> return ActionResult.Failure(r.error)
            is AppResult.Ok -> if (r.value.second) Redactor.REDACTED else r.value.first
        }
        return ctx.ports.notifications.postMacroNotification(ctx.executionId, title, text, a.tapOpensMacro)
            .toResult("notified \"${Redactor.clipForLog(title, 40)}\"")
    }

    private suspend fun setVariable(ctx: ActionContext, a: ActionParameters.SetVariable): ActionResult {
        val value: RuntimeValue = when {
            a.value != null -> when (val r = VariableScope.toRuntime(a.value, ctx.secureValues)) {
                is AppResult.Err -> return ActionResult.Failure(r.error)
                is AppResult.Ok -> r.value
            }
            a.expression != null -> when (val r = evaluate(ctx, a.name, a.expression)) {
                is AppResult.Err -> return ActionResult.Failure(r.error)
                is AppResult.Ok -> r.value
            }
            else -> return ActionResult.Failure(AppError(ErrorCode.INVARIANT_VIOLATION, "setVariable without value"))
        }
        ctx.variables[a.name] = value
        val shown = when (value) {
            is RuntimeValue.Secret -> Redactor.hashToken(value.reveal())
            else -> Redactor.clipForLog(value.asDisplayString(), 40)
        }
        return ActionResult.Success("${a.name} = $shown")
    }

    private suspend fun evaluate(ctx: ActionContext, name: String, e: Expression): AppResult<RuntimeValue> = when (e) {
        is Expression.Increment -> {
            val current = (ctx.variables[name] as? RuntimeValue.Int64)?.value ?: 0L
            AppResult.ok(RuntimeValue.Int64(current + e.by))
        }
        is Expression.Concat -> {
            val sb = StringBuilder()
            var secret = false
            for (part in e.parts) {
                when (val r = ctx.variables.resolveString(part, ctx.secureValues)) {
                    is AppResult.Err -> return r
                    is AppResult.Ok -> { sb.append(r.value.first); secret = secret || r.value.second }
                }
            }
            AppResult.ok(if (secret) RuntimeValue.Secret(sb.toString()) else RuntimeValue.Text(sb.toString()))
        }
        Expression.Now -> AppResult.ok(RuntimeValue.Text(ctx.clock.now().toString()))
        is Expression.NodeText -> {
            val nodes = ctx.ports.accessibility.findNodes(e.selector)
            val node = nodes.getOrNull(e.selector.index)
                ?: return AppResult.err(ErrorCode.NODE_NOT_FOUND, e.selector.describe())
            if (node.isPassword) return AppResult.err(ErrorCode.NODE_IS_PASSWORD)
            // Node text read from another app is treated as sensitive: stored as a secret so it never reaches logs.
            AppResult.ok(RuntimeValue.Secret(ctx.ports.accessibility.nodeText(node).orEmpty()))
        }
    }

    private suspend fun logAction(ctx: ActionContext, a: ActionParameters.Log): ActionResult {
        val (msg, secret) = when (val r = ctx.variables.resolveString(a.message, ctx.secureValues)) {
            is AppResult.Err -> return ActionResult.Failure(r.error)
            is AppResult.Ok -> r.value
        }
        val level = when (a.level) {
            com.macroandroid.automation.model.LogLevelParam.INFO -> LogLevel.INFO
            com.macroandroid.automation.model.LogLevelParam.WARN -> LogLevel.WARN
        }
        ctx.log(level, if (secret) Redactor.REDACTED else Redactor.clipForLog(msg, 500))
        return ActionResult.Success()
    }

    // ---- conditions -------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // exhaustive `when` over the Condition ADT; each branch is a one-liner
    suspend fun evaluate(ctx: ActionContext, c: Condition): AppResult<Boolean> = when (c) {
        is Condition.VarEquals -> {
            val expected = when (val r = VariableScope.toRuntime(c.value, ctx.secureValues)) {
                is AppResult.Err -> return r
                is AppResult.Ok -> r.value
            }
            AppResult.ok(valuesEqual(ctx.variables[c.name], expected))
        }
        is Condition.VarCompare -> {
            val actual = (ctx.variables[c.name] as? RuntimeValue.Int64)?.value
                ?: (ctx.variables[c.name] as? RuntimeValue.Text)?.value?.toLongOrNull()
            AppResult.ok(actual != null && compare(actual, c.op, c.value))
        }
        is Condition.VarContains -> {
            val haystack = ctx.variables[c.name] ?: return AppResult.ok(false)
            when (val r = ctx.variables.resolveString(c.needle, ctx.secureValues)) {
                is AppResult.Err -> r
                is AppResult.Ok -> {
                    val hay = if (haystack is RuntimeValue.Secret) haystack.reveal() else haystack.asDisplayString()
                    AppResult.ok(hay.contains(r.value.first))
                }
            }
        }
        is Condition.NodeExists -> AppResult.ok(ctx.ports.accessibility.findNodes(c.selector).any { it.isVisible })
        is Condition.AppInstalled -> AppResult.ok(ctx.ports.launcher.isInstalled(c.packageName))
        is Condition.Not -> evaluate(ctx, c.inner).let { r -> if (r is AppResult.Ok) AppResult.ok(!r.value) else r }
        is Condition.All -> {
            for (inner in c.conditions) {
                when (val r = evaluate(ctx, inner)) {
                    is AppResult.Err -> return r
                    is AppResult.Ok -> if (!r.value) return AppResult.ok(false)
                }
            }
            AppResult.ok(true)
        }
        is Condition.Any -> {
            for (inner in c.conditions) {
                when (val r = evaluate(ctx, inner)) {
                    is AppResult.Err -> return r
                    is AppResult.Ok -> if (r.value) return AppResult.ok(true)
                }
            }
            AppResult.ok(false)
        }
    }

    private fun valuesEqual(actual: RuntimeValue?, expected: RuntimeValue): Boolean = when {
        actual == null -> false
        actual is RuntimeValue.Secret || expected is RuntimeValue.Secret -> actual == expected
        actual is RuntimeValue.Int64 && expected is RuntimeValue.Text -> actual.value.toString() == expected.value
        actual is RuntimeValue.Text && expected is RuntimeValue.Int64 -> actual.value == expected.value.toString()
        else -> actual == expected
    }

    private fun compare(a: Long, op: CompareOp, b: Long): Boolean = when (op) {
        CompareOp.LT -> a < b
        CompareOp.LE -> a <= b
        CompareOp.EQ -> a == b
        CompareOp.NE -> a != b
        CompareOp.GE -> a >= b
        CompareOp.GT -> a > b
    }

    private fun AppResult<Unit>.toResult(summary: String): ActionResult = when (this) {
        is AppResult.Ok -> ActionResult.Success(summary)
        is AppResult.Err -> ActionResult.Failure(error)
    }
}
