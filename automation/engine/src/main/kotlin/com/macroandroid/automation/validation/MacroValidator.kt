package com.macroandroid.automation.validation

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.ConcurrencyClass
import com.macroandroid.automation.model.Expression
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.RetryPolicy
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.model.TextMatch
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.util.Identifiers
import kotlin.time.Duration
import kotlin.time.Instant

enum class Severity { ERROR, WARNING }

data class ValidationIssue(
    val code: ErrorCode,
    val severity: Severity,
    /** Step the issue belongs to; `null` for macro-level issues. */
    val stepId: StepId? = null,
    /** Field name inside the step/macro for inline editor errors. */
    val field: String? = null,
    val detail: String? = null,
)

data class ValidationResult(val issues: List<ValidationIssue>) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()

    fun has(code: ErrorCode): Boolean = issues.any { it.code == code }

    companion object {
        val OK = ValidationResult(emptyList())
    }
}

/**
 * Environment facts the validator needs that only the platform knows. Pure-Kotlin so tests can fake it.
 */
interface ValidationEnvironment {
    /** Names (normalised) of other macros in the same profile, for `NAME_DUPLICATE`. */
    fun otherMacroNames(profile: String, excludingId: String): Set<String> = emptySet()
    fun isPackageInstalled(packageName: String): Boolean? = null
    fun isAccessibilityConsentGranted(): Boolean = true
    fun isSecureValueAvailable(id: String): Boolean = true
    fun now(): Instant? = null

    object Empty : ValidationEnvironment
}

/**
 * Pure-Kotlin implementation of the rule table in docs/phase-1/08-macro-schema.md §6.
 * Errors block save/import/run; warnings are shown in the editor.
 */
class MacroValidator(private val env: ValidationEnvironment = ValidationEnvironment.Empty) {

    fun validate(macro: Macro): ValidationResult {
        val ctx = Context()
        validateHeader(macro, ctx)
        validatePolicy(macro, ctx)
        validateVariables(macro, ctx)
        if (macro.steps.isEmpty() || macro.steps.none { it.enabled }) {
            ctx.error(ErrorCode.NO_STEPS)
        }
        if (macro.steps.size > MacroLimits.TOP_LEVEL_STEPS_MAX) {
            ctx.error(ErrorCode.STEP_LIMIT, detail = "topLevel=${macro.steps.size}")
        }
        if (macro.expandedLeafCount > MacroLimits.EXPANDED_LEAVES_MAX) {
            ctx.error(ErrorCode.STEP_LIMIT, detail = "expanded=${macro.expandedLeafCount}")
        }
        if (macro.nestingDepth > MacroLimits.NESTING_DEPTH_MAX) {
            ctx.error(ErrorCode.NESTING_LIMIT, detail = "depth=${macro.nestingDepth}")
        }
        validateLabelsAndJumps(macro, ctx)
        val definedVars = macro.variables.keys.toMutableSet()
        validateSteps(macro.steps, ctx, definedVars, insideParallel = false)
        validateAccessibilityHints(macro, ctx)
        return ValidationResult(ctx.issues)
    }

    fun validate(schedule: ScheduleSpec, macro: Macro?): ValidationResult {
        val ctx = Context()
        when (val k = schedule.kind) {
            is ScheduleKind.OneTime -> env.now()?.let { now ->
                if (k.at < now) ctx.error(ErrorCode.SCHEDULE_TIME_IN_PAST, field = "at")
            }
            is ScheduleKind.Interval -> if (k.minutes < MacroLimits.SCHEDULE_INTERVAL_MIN_MINUTES ||
                k.minutes > MacroLimits.SCHEDULE_INTERVAL_MAX_MINUTES
            ) {
                ctx.error(ErrorCode.SCHEDULE_INTERVAL_TOO_SHORT, field = "minutes", detail = "${k.minutes}")
            }
            is ScheduleKind.Daily -> if (k.daysOfWeek.isEmpty()) {
                ctx.error(ErrorCode.LIMIT_EXCEEDED, field = "daysOfWeek", detail = "empty")
            }
        }
        if (schedule.requiresDeviceIdle && macro != null && macro.concurrencyClass == ConcurrencyClass.UI) {
            ctx.error(ErrorCode.REQUIRES_DEVICE_IDLE_WITH_UI, field = "requiresDeviceIdle")
        }
        return ValidationResult(ctx.issues)
    }

    // ---- header -----------------------------------------------------------------------------

    private fun validateHeader(macro: Macro, ctx: Context) {
        val name = macro.name
        if (name.isBlank() || name.length > MacroLimits.NAME_MAX || CONTROL.containsMatchIn(name)) {
            ctx.error(ErrorCode.NAME_INVALID, field = "name")
        } else if (normalise(name) in env.otherMacroNames(macro.profile, macro.id.value)) {
            ctx.error(ErrorCode.NAME_DUPLICATE, field = "name")
        }
        if (macro.description.length > MacroLimits.DESCRIPTION_MAX) {
            ctx.error(ErrorCode.TEXT_TOO_LONG, field = "description")
        }
        if (macro.profile.isBlank() || macro.profile.length > MacroLimits.PROFILE_MAX) {
            ctx.error(ErrorCode.NAME_INVALID, field = "profile")
        }
        if (macro.tags.size > MacroLimits.TAGS_MAX) ctx.error(ErrorCode.TAG_INVALID, field = "tags", detail = "count")
        macro.tags.forEach { tag ->
            if (!Identifiers.isValidTag(tag)) ctx.error(ErrorCode.TAG_INVALID, field = "tags", detail = tag)
        }
        if (macro.tags.map(Identifiers::normaliseTag).toSet().size != macro.tags.size) {
            ctx.error(ErrorCode.TAG_INVALID, field = "tags", detail = "duplicate")
        }
    }

    private fun validatePolicy(macro: Macro, ctx: Context) {
        val p = macro.executionPolicy
        checkRange(ctx, null, "totalTimeout", p.totalTimeout, Duration.ZERO, MacroLimits.TOTAL_TIMEOUT_MAX)
        checkRange(
            ctx, null, "defaultStepTimeout", p.defaultStepTimeout,
            MacroLimits.STEP_TIMEOUT_MIN, MacroLimits.STEP_TIMEOUT_MAX,
        )
        checkRange(ctx, null, "lockAcquireTimeout", p.lockAcquireTimeout, Duration.ZERO, MacroLimits.LOCK_ACQUIRE_TIMEOUT_MAX)
        checkRange(ctx, null, "blockedTimeout", p.blockedTimeout, Duration.ZERO, MacroLimits.BLOCKED_TIMEOUT_MAX)
        validateRetry(p.defaultRetry, ctx, null)
    }

    private fun validateVariables(macro: Macro, ctx: Context) {
        if (macro.variables.size > MacroLimits.VARIABLES_MAX) ctx.error(ErrorCode.VARIABLE_LIMIT, field = "variables")
        macro.variables.forEach { (name, value) ->
            if (!Identifiers.isValidVariableName(name)) {
                ctx.error(ErrorCode.VARIABLE_NAME_INVALID, field = "variables", detail = name)
            }
            checkVariableValue(value, ctx, null, "variables.$name")
        }
    }

    private fun validateRetry(retry: RetryPolicy, ctx: Context, stepId: StepId?) {
        if (retry.maxAttempts !in 1..MacroLimits.RETRY_ATTEMPTS_MAX) ctx.error(ErrorCode.RETRY_RANGE, stepId, "retry.maxAttempts")
        if (retry.initialDelay < Duration.ZERO || retry.initialDelay > MacroLimits.RETRY_INITIAL_DELAY_MAX) {
            ctx.error(ErrorCode.RETRY_RANGE, stepId, "retry.initialDelay")
        }
        if (retry.maxDelay < Duration.ZERO || retry.maxDelay > MacroLimits.RETRY_MAX_DELAY_MAX) {
            ctx.error(ErrorCode.RETRY_RANGE, stepId, "retry.maxDelay")
        }
        if (retry.retryOn.any { !it.defaultRetryable }) ctx.error(ErrorCode.RETRY_RANGE, stepId, "retry.retryOn")
    }

    // ---- labels / jumps ---------------------------------------------------------------------

    private fun validateLabelsAndJumps(macro: Macro, ctx: Context) {
        val all = macro.allSteps()
        val labelIndex = HashMap<String, Int>()
        all.forEachIndexed { index, step ->
            val label = step.label ?: return@forEachIndexed
            if (label.isBlank() || label.length > MacroLimits.LABEL_MAX) {
                ctx.error(ErrorCode.NAME_INVALID, step.id, "label")
            }
            if (labelIndex.put(label, index) != null) ctx.error(ErrorCode.LABEL_DUPLICATE, step.id, "label", label)
        }
        all.forEachIndexed { index, step ->
            val jump = step.onFailure as? com.macroandroid.automation.model.FailureBehavior.JumpToLabel
                ?: return@forEachIndexed
            val target = labelIndex[jump.label]
            when {
                target == null -> ctx.error(ErrorCode.JUMP_TARGET_MISSING, step.id, "onFailure", jump.label)
                target <= index -> ctx.error(ErrorCode.JUMP_BACKWARD, step.id, "onFailure", jump.label)
            }
        }
    }

    // ---- steps ------------------------------------------------------------------------------

    private fun validateSteps(
        steps: List<MacroStep>,
        ctx: Context,
        definedVars: MutableSet<String>,
        insideParallel: Boolean,
    ) {
        steps.forEach { step -> validateStep(step, ctx, definedVars, insideParallel) }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun validateStep(step: MacroStep, ctx: Context, definedVars: MutableSet<String>, insideParallel: Boolean) {
        val id = step.id
        if (!Identifiers.isUuid(id.value)) ctx.error(ErrorCode.NAME_INVALID, id, "id")
        if (step.continueOnCancel) ctx.error(ErrorCode.CONTINUE_ON_CANCEL_RESERVED, id, "continueOnCancel")
        step.timeout?.let { checkRange(ctx, id, "timeout", it, MacroLimits.STEP_TIMEOUT_MIN, MacroLimits.STEP_TIMEOUT_MAX) }
        step.retry?.let { validateRetry(it, ctx, id) }
        if (insideParallel && step.action.concurrencyClass != ConcurrencyClass.BACKGROUND_SAFE) {
            ctx.error(ErrorCode.PARALLEL_CONTAINS_UI_STEP, id)
        }
        when (val a = step.action) {
            is ActionParameters.LaunchApp -> {
                checkPackage(a.packageName, ctx, id, "packageName")
                checkRange(ctx, id, "windowWait", a.windowWait, Duration.ZERO, MacroLimits.STEP_TIMEOUT_MAX)
                if (env.isPackageInstalled(a.packageName) == false) {
                    ctx.warn(ErrorCode.TARGET_APP_NOT_VISIBLE, id, "packageName", a.packageName)
                }
            }
            is ActionParameters.OpenUrl -> {
                checkText(a.url, ctx, id, "url", MacroLimits.URL_MAX, definedVars)
                when (val u = a.url) {
                    is TextValue.Literal -> if (!HTTP_URL.matches(u.text)) {
                        ctx.error(ErrorCode.URL_SCHEME_NOT_ALLOWED, id, "url")
                    }
                    else -> ctx.warn(ErrorCode.URL_RUNTIME_CHECK, id, "url")
                }
                a.preferPackage?.let { checkPackage(it, ctx, id, "preferPackage") }
            }
            is ActionParameters.Wait -> checkRange(ctx, id, "duration", a.duration, MacroLimits.WAIT_MIN, MacroLimits.WAIT_MAX)
            is ActionParameters.GlobalAction -> Unit
            is ActionParameters.ClickNode -> {
                checkSelector(a.selector, ctx, id, "selector")
                if (!a.requireVisible) ctx.error(ErrorCode.ACTION_NOT_SUPPORTED, id, "requireVisible")
            }
            is ActionParameters.ScrollNode -> {
                a.selector?.let { checkSelector(it, ctx, id, "selector") }
                if (a.times !in 1..MacroLimits.SCROLL_TIMES_MAX) ctx.error(ErrorCode.LIMIT_EXCEEDED, id, "times")
            }
            is ActionParameters.EnterText -> {
                a.selector?.let { checkSelector(it, ctx, id, "selector") }
                checkText(a.text, ctx, id, "text", MacroLimits.LITERAL_MAX, definedVars)
                if (a.text is TextValue.Secure && !a.sensitive) ctx.error(ErrorCode.SENSITIVE_FLAG_REQUIRED, id, "sensitive")
                if (a.text is TextValue.Var && a.text.name in ctx.secureVars && !a.sensitive) {
                    ctx.error(ErrorCode.SENSITIVE_FLAG_REQUIRED, id, "sensitive")
                }
            }
            is ActionParameters.WaitForNode -> checkSelector(a.selector, ctx, id, "selector")
            is ActionParameters.SendNotification -> {
                checkText(a.title, ctx, id, "title", MacroLimits.NOTIFICATION_TITLE_MAX, definedVars)
                checkText(a.text, ctx, id, "text", MacroLimits.NOTIFICATION_TEXT_MAX, definedVars)
            }
            is ActionParameters.SetVariable -> {
                if (!Identifiers.isValidVariableName(a.name)) ctx.error(ErrorCode.VARIABLE_NAME_INVALID, id, "name", a.name)
                if ((a.value == null) == (a.expression == null)) {
                    ctx.error(ErrorCode.ACTION_NOT_SUPPORTED, id, "value", "exactly one of value/expression")
                }
                a.value?.let {
                    checkVariableValue(it, ctx, id, "value")
                    if (it is VariableValue.Secure) ctx.secureVars += a.name
                }
                when (val e = a.expression) {
                    is Expression.Concat -> {
                        if (e.parts.isEmpty() || e.parts.size > MacroLimits.CONCAT_PARTS_MAX) {
                            ctx.error(ErrorCode.LIMIT_EXCEEDED, id, "expression.parts")
                        }
                        e.parts.forEach { checkText(it, ctx, id, "expression.parts", MacroLimits.LITERAL_MAX, definedVars) }
                        if (e.parts.any { it is TextValue.Secure || (it is TextValue.Var && it.name in ctx.secureVars) }) {
                            ctx.secureVars += a.name
                        }
                    }
                    is Expression.NodeText -> checkSelector(e.selector, ctx, id, "expression.selector")
                    is Expression.Increment -> if (a.name !in definedVars) ctx.warn(ErrorCode.VARIABLE_UNDEFINED, id, "name", a.name)
                    Expression.Now, null -> Unit
                }
                definedVars += a.name
            }
            is ActionParameters.If -> {
                checkCondition(a.condition, ctx, id, definedVars, depth = 0)
                val thenVars = definedVars.toMutableSet()
                val elseVars = definedVars.toMutableSet()
                validateSteps(a.then, ctx, thenVars, insideParallel)
                validateSteps(a.`else`, ctx, elseVars, insideParallel)
                definedVars += thenVars intersect elseVars
            }
            is ActionParameters.Repeat -> {
                val hasCount = a.count != null
                val hasWhile = a.whileCondition != null
                if (hasCount == hasWhile) ctx.error(ErrorCode.REPEAT_BOUNDS, id, "count", "exactly one of count/whileCondition")
                a.count?.let { if (it !in 1..MacroLimits.REPEAT_MAX) ctx.error(ErrorCode.REPEAT_BOUNDS, id, "count") }
                if (hasWhile) {
                    val max = a.maxIterations
                    if (max == null || max !in 1..MacroLimits.REPEAT_MAX) ctx.error(ErrorCode.REPEAT_BOUNDS, id, "maxIterations")
                    checkCondition(a.whileCondition, ctx, id, definedVars, depth = 0)
                }
                checkRange(ctx, id, "delayBetween", a.delayBetween, Duration.ZERO, MacroLimits.WAIT_MAX)
                if (a.body.isEmpty()) ctx.error(ErrorCode.NO_STEPS, id, "body")
                validateSteps(a.body, ctx, definedVars, insideParallel)
            }
            is ActionParameters.Parallel -> {
                if (a.children.size !in MacroLimits.PARALLEL_MIN..MacroLimits.PARALLEL_MAX) {
                    ctx.error(ErrorCode.PARALLEL_SIZE, id, "children", "${a.children.size}")
                }
                validateSteps(a.children, ctx, definedVars, insideParallel = true)
            }
            is ActionParameters.Log -> checkText(a.message, ctx, id, "message", MacroLimits.LOG_MESSAGE_MAX, definedVars)
            is ActionParameters.Stop -> a.message?.let {
                if (it.length > MacroLimits.LOG_MESSAGE_MAX) ctx.error(ErrorCode.TEXT_TOO_LONG, id, "message")
            }
        }
    }

    private fun validateAccessibilityHints(macro: Macro, ctx: Context) {
        if (!macro.requiresAccessibility) return
        if (!env.isAccessibilityConsentGranted()) ctx.warn(ErrorCode.A11Y_CONSENT_MISSING)
        val firstUi = macro.allSteps().firstOrNull { it.enabled && it.action.concurrencyClass == ConcurrencyClass.UI }
        if (firstUi != null && firstUi.action.needsAccessibility &&
            firstUi.action !is ActionParameters.WaitForNode
        ) {
            ctx.warn(ErrorCode.A11Y_STEP_WITHOUT_LAUNCH, firstUi.id)
        }
    }

    // ---- leaf checks ------------------------------------------------------------------------

    private fun checkCondition(c: Condition, ctx: Context, id: StepId, definedVars: Set<String>, depth: Int) {
        if (depth > MacroLimits.NESTING_DEPTH_MAX) {
            ctx.error(ErrorCode.NESTING_LIMIT, id, "condition")
            return
        }
        when (c) {
            is Condition.VarEquals -> {
                checkVarRef(c.name, ctx, id, definedVars)
                checkVariableValue(c.value, ctx, id, "condition.value")
            }
            is Condition.VarCompare -> checkVarRef(c.name, ctx, id, definedVars)
            is Condition.VarContains -> {
                checkVarRef(c.name, ctx, id, definedVars)
                checkText(c.needle, ctx, id, "condition.needle", MacroLimits.LITERAL_MAX, definedVars)
            }
            is Condition.NodeExists -> checkSelector(c.selector, ctx, id, "condition.selector")
            is Condition.AppInstalled -> checkPackage(c.packageName, ctx, id, "condition.packageName")
            is Condition.Not -> checkCondition(c.inner, ctx, id, definedVars, depth + 1)
            is Condition.All -> {
                if (c.conditions.isEmpty() || c.conditions.size > MacroLimits.CONDITION_LIST_MAX) {
                    ctx.error(ErrorCode.LIMIT_EXCEEDED, id, "condition.all")
                }
                c.conditions.forEach { checkCondition(it, ctx, id, definedVars, depth + 1) }
            }
            is Condition.Any -> {
                if (c.conditions.isEmpty() || c.conditions.size > MacroLimits.CONDITION_LIST_MAX) {
                    ctx.error(ErrorCode.LIMIT_EXCEEDED, id, "condition.any")
                }
                c.conditions.forEach { checkCondition(it, ctx, id, definedVars, depth + 1) }
            }
        }
    }

    private fun checkVarRef(name: String, ctx: Context, id: StepId, definedVars: Set<String>) {
        if (!Identifiers.isValidVariableName(name)) {
            ctx.error(ErrorCode.VARIABLE_NAME_INVALID, id, "condition.name", name)
        } else if (name !in definedVars) {
            ctx.warn(ErrorCode.VARIABLE_UNDEFINED, id, "condition.name", name)
        }
    }

    private fun checkText(v: TextValue, ctx: Context, id: StepId, field: String, max: Int, definedVars: Set<String>) {
        when (v) {
            is TextValue.Literal -> if (v.text.length > max) ctx.error(ErrorCode.TEXT_TOO_LONG, id, field)
            is TextValue.Var -> checkVarRef(v.name, ctx, id, definedVars)
            is TextValue.Secure -> checkSecureRef(v.ref.id, v.ref.redacted, ctx, id, field)
            is TextValue.Template -> {
                if (v.template.length > max) ctx.error(ErrorCode.TEXT_TOO_LONG, id, field)
                v.referencedVariables().forEach { name ->
                    if (name in ctx.secureVars) {
                        ctx.error(ErrorCode.SECURE_IN_TEMPLATE, id, field, name)
                    } else if (name !in definedVars) {
                        ctx.warn(ErrorCode.VARIABLE_UNDEFINED, id, field, name)
                    }
                }
            }
        }
    }

    private fun checkVariableValue(v: VariableValue, ctx: Context, id: StepId?, field: String) {
        when (v) {
            is VariableValue.Str -> if (v.value.length > MacroLimits.LITERAL_MAX) ctx.error(ErrorCode.TEXT_TOO_LONG, id, field)
            is VariableValue.Secure -> {
                checkSecureRef(v.ref.id, v.ref.redacted, ctx, id, field)
                if (id == null) field.removePrefix("variables.").let { ctx.secureVars += it }
            }
            is VariableValue.Int64, is VariableValue.Bool -> Unit
        }
    }

    private fun checkSecureRef(refId: String, redacted: Boolean, ctx: Context, id: StepId?, field: String) {
        if (redacted) {
            ctx.error(ErrorCode.SECURE_VALUE_REDACTED, id, field)
        } else if (!env.isSecureValueAvailable(refId)) {
            ctx.error(ErrorCode.SECURE_VALUE_UNAVAILABLE, id, field)
        }
    }

    private fun checkSelector(s: com.macroandroid.automation.model.NodeSelector, ctx: Context, id: StepId, field: String) {
        if (s.isEmpty) ctx.error(ErrorCode.SELECTOR_EMPTY, id, field)
        if (s.index !in 0..MacroLimits.SELECTOR_INDEX_MAX) ctx.error(ErrorCode.LIMIT_EXCEEDED, id, "$field.index")
        s.packageName?.let { checkPackage(it, ctx, id, "$field.packageName") }
        if (s.textMatch == TextMatch.REGEX) {
            listOfNotNull(s.text, s.contentDescription).forEach { pattern ->
                if (pattern.length > MacroLimits.REGEX_MAX || runCatching { Regex(pattern) }.isFailure) {
                    ctx.error(ErrorCode.REGEX_INVALID, id, field)
                }
            }
        }
    }

    private fun checkPackage(name: String, ctx: Context, id: StepId?, field: String) {
        if (!Identifiers.isValidPackageName(name)) ctx.error(ErrorCode.PACKAGE_NAME_INVALID, id, field, name)
    }

    private fun checkRange(ctx: Context, id: StepId?, field: String, value: Duration, min: Duration, max: Duration) {
        if (value < min || value > max) ctx.error(ErrorCode.TIMEOUT_RANGE, id, field, value.toString())
    }

    private class Context {
        val issues = ArrayList<ValidationIssue>()

        /** Names of variables known to hold secrets (initial secure vars + SetVariable from secure sources). */
        val secureVars = HashSet<String>()

        fun error(code: ErrorCode, stepId: StepId? = null, field: String? = null, detail: String? = null) {
            issues += ValidationIssue(code, Severity.ERROR, stepId, field, detail)
        }

        fun warn(code: ErrorCode, stepId: StepId? = null, field: String? = null, detail: String? = null) {
            issues += ValidationIssue(code, Severity.WARNING, stepId, field, detail)
        }
    }

    private companion object {
        val CONTROL = Regex("[\\p{Cntrl}]")
        val HTTP_URL = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE)
        fun normalise(name: String) = name.trim().lowercase()
    }
}

fun Macro.normalisedName(): String = name.trim().lowercase()
