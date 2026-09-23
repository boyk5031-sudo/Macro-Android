package com.macroandroid.automation.validation

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.Expression
import com.macroandroid.automation.model.FailureBehavior
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.RetryPolicy
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.automation.model.SecureValueRef
import com.macroandroid.automation.model.TextMatch
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.automation.testing.MacroFixtures
import com.macroandroid.automation.testing.MacroFixtures.literal
import com.macroandroid.automation.testing.MacroFixtures.log
import com.macroandroid.automation.testing.MacroFixtures.macro
import com.macroandroid.automation.testing.MacroFixtures.step
import com.macroandroid.core.common.error.ErrorCode
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MacroValidatorTest {

    private val validator = MacroValidator()
    private val sel = NodeSelector(text = "OK")

    @Test
    fun `valid macro passes`() {
        val r = validator.validate(macro(log(1, "hello")))
        assertThat(r.isValid).isTrue()
        assertThat(r.issues).isEmpty()
    }

    @Test
    fun `header rules`() {
        assertThat(validator.validate(macro(log(1, "x"), name = "")).has(ErrorCode.NAME_INVALID)).isTrue()
        assertThat(validator.validate(macro(log(1, "x"), name = "a".repeat(81))).has(ErrorCode.NAME_INVALID)).isTrue()
        assertThat(validator.validate(macro(log(1, "x"), name = "bad\u0007")).has(ErrorCode.NAME_INVALID)).isTrue()
        val env = object : ValidationEnvironment {
            override fun otherMacroNames(profile: String, excludingId: String) = setOf("test macro")
        }
        assertThat(MacroValidator(env).validate(macro(log(1, "x"))).has(ErrorCode.NAME_DUPLICATE)).isTrue()
    }

    @Test
    fun `no steps and all disabled`() {
        assertThat(validator.validate(macro()).has(ErrorCode.NO_STEPS)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.Wait(1.seconds), enabled = false))).has(ErrorCode.NO_STEPS)).isTrue()
    }

    @Test
    fun `limits on steps nesting and repeats`() {
        val many = (1..101).map { log(it, "x") }.toTypedArray()
        assertThat(validator.validate(macro(*many)).has(ErrorCode.STEP_LIMIT)).isTrue()

        val big = step(1, ActionParameters.Repeat(count = 100, body = (2..4).map { log(it, "x") }))
        assertThat(validator.validate(macro(big)).has(ErrorCode.STEP_LIMIT)).isTrue()

        fun nest(depth: Int): ActionParameters =
            if (depth == 0) ActionParameters.Log(message = literal("x"))
            else ActionParameters.If(Condition.AppInstalled("a.b"), then = listOf(step(depth + 10, nest(depth - 1))))
        assertThat(validator.validate(macro(step(1, nest(4)))).has(ErrorCode.NESTING_LIMIT)).isFalse()
        assertThat(validator.validate(macro(step(1, nest(5)))).has(ErrorCode.NESTING_LIMIT)).isTrue()

        assertThat(validator.validate(macro(step(1, ActionParameters.Repeat(count = 0, body = listOf(log(2, "x")))))).has(ErrorCode.REPEAT_BOUNDS)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.Repeat(body = listOf(log(2, "x")))))).has(ErrorCode.REPEAT_BOUNDS)).isTrue()
        assertThat(
            validator.validate(
                macro(step(1, ActionParameters.Repeat(whileCondition = Condition.AppInstalled("a.b"), body = listOf(log(2, "x"))))),
            ).has(ErrorCode.REPEAT_BOUNDS),
        ).isTrue()
    }

    @Test
    fun `parallel rules`() {
        val one = step(1, ActionParameters.Parallel(children = listOf(log(2, "x"))))
        assertThat(validator.validate(macro(one)).has(ErrorCode.PARALLEL_SIZE)).isTrue()
        val ui = step(1, ActionParameters.Parallel(children = listOf(log(2, "x"), step(3, ActionParameters.ClickNode(sel)))))
        assertThat(validator.validate(macro(ui)).has(ErrorCode.PARALLEL_CONTAINS_UI_STEP)).isTrue()
    }

    @Test
    fun `timeouts and retries`() {
        assertThat(validator.validate(macro(step(1, ActionParameters.Wait(1.hours)))).has(ErrorCode.TIMEOUT_RANGE)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.Wait(1.milliseconds)))).has(ErrorCode.TIMEOUT_RANGE)).isTrue()
        assertThat(validator.validate(macro(log(1, "x").copy(timeout = 11.seconds * 60))).has(ErrorCode.TIMEOUT_RANGE)).isTrue()
        assertThat(validator.validate(macro(log(1, "x").copy(retry = RetryPolicy(maxAttempts = 6)))).has(ErrorCode.RETRY_RANGE)).isTrue()
        assertThat(
            validator.validate(
                macro(log(1, "x").copy(retry = RetryPolicy(retryOn = setOf(com.macroandroid.core.common.error.ErrorCategory.POLICY)))),
            ).has(ErrorCode.RETRY_RANGE),
        ).isTrue()
    }

    @Test
    fun `package names urls selectors regex`() {
        assertThat(validator.validate(macro(step(1, ActionParameters.LaunchApp("nodots")))).has(ErrorCode.PACKAGE_NAME_INVALID)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.OpenUrl(literal("intent://x"))))).has(ErrorCode.URL_SCHEME_NOT_ALLOWED)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.OpenUrl(literal("https://example.com"))))).isValid).isTrue()
        val varUrl = validator.validate(macro(step(1, ActionParameters.OpenUrl(TextValue.Var("u"))), variables = mapOf("u" to VariableValue.Str("https://a"))))
        assertThat(varUrl.isValid).isTrue()
        assertThat(varUrl.has(ErrorCode.URL_RUNTIME_CHECK)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.ClickNode(NodeSelector(index = 1))))).has(ErrorCode.SELECTOR_EMPTY)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.ClickNode(NodeSelector(text = "(", textMatch = TextMatch.REGEX))))).has(ErrorCode.REGEX_INVALID)).isTrue()
        assertThat(validator.validate(macro(step(1, ActionParameters.ClickNode(sel, requireVisible = false)))).has(ErrorCode.ACTION_NOT_SUPPORTED)).isTrue()
    }

    @Test
    fun `labels and jumps`() {
        val dup = macro(log(1, "a", ).copy(label = "L"), log(2, "b").copy(label = "L"))
        assertThat(validator.validate(dup).has(ErrorCode.LABEL_DUPLICATE)).isTrue()
        val missing = macro(log(1, "a").copy(onFailure = FailureBehavior.JumpToLabel("nope")))
        assertThat(validator.validate(missing).has(ErrorCode.JUMP_TARGET_MISSING)).isTrue()
        val backward = macro(log(1, "a").copy(label = "L"), log(2, "b").copy(onFailure = FailureBehavior.JumpToLabel("L")))
        assertThat(validator.validate(backward).has(ErrorCode.JUMP_BACKWARD)).isTrue()
        val forward = macro(log(1, "a").copy(onFailure = FailureBehavior.JumpToLabel("L")), log(2, "b").copy(label = "L"))
        assertThat(validator.validate(forward).isValid).isTrue()
    }

    @Test
    fun `variables and secrets`() {
        val badName = macro(step(1, ActionParameters.SetVariable("1bad", VariableValue.Str("x"))))
        assertThat(validator.validate(badName).has(ErrorCode.VARIABLE_NAME_INVALID)).isTrue()

        val undefined = macro(step(1, ActionParameters.Log(message = TextValue.Var("nope"))))
        val r = validator.validate(undefined)
        assertThat(r.isValid).isTrue()
        assertThat(r.has(ErrorCode.VARIABLE_UNDEFINED)).isTrue()

        val secureInTemplate = macro(
            step(1, ActionParameters.Log(message = TextValue.Template("pw={{pw}}"))),
            variables = mapOf("pw" to VariableValue.Secure(SecureValueRef("s1"))),
        )
        assertThat(validator.validate(secureInTemplate).has(ErrorCode.SECURE_IN_TEMPLATE)).isTrue()

        val unflagged = macro(step(1, ActionParameters.EnterText(text = TextValue.Secure(SecureValueRef("s1")))))
        assertThat(validator.validate(unflagged).has(ErrorCode.SENSITIVE_FLAG_REQUIRED)).isTrue()

        val redacted = macro(step(1, ActionParameters.EnterText(text = TextValue.Secure(SecureValueRef("s1", redacted = true)), sensitive = true)))
        assertThat(validator.validate(redacted).has(ErrorCode.SECURE_VALUE_REDACTED)).isTrue()

        val env = object : ValidationEnvironment {
            override fun isSecureValueAvailable(id: String) = false
        }
        val unavailable = macro(step(1, ActionParameters.EnterText(text = TextValue.Secure(SecureValueRef("s1")), sensitive = true)))
        assertThat(MacroValidator(env).validate(unavailable).has(ErrorCode.SECURE_VALUE_UNAVAILABLE)).isTrue()

        val both = macro(step(1, ActionParameters.SetVariable("a", VariableValue.Str("x"), Expression.Now)))
        assertThat(validator.validate(both).has(ErrorCode.ACTION_NOT_SUPPORTED)).isTrue()
    }

    @Test
    fun `accessibility warnings`() {
        val env = object : ValidationEnvironment {
            override fun isAccessibilityConsentGranted() = false
        }
        val r = MacroValidator(env).validate(macro(step(1, ActionParameters.ClickNode(sel))))
        assertThat(r.isValid).isTrue()
        assertThat(r.has(ErrorCode.A11Y_CONSENT_MISSING)).isTrue()
        assertThat(r.has(ErrorCode.A11Y_STEP_WITHOUT_LAUNCH)).isTrue()

        val ok = validator.validate(macro(step(1, ActionParameters.LaunchApp("a.b")), step(2, ActionParameters.ClickNode(sel))))
        assertThat(ok.has(ErrorCode.A11Y_STEP_WITHOUT_LAUNCH)).isFalse()
    }

    @Test
    fun `reserved continueOnCancel`() {
        val r = validator.validate(macro(log(1, "x").copy(continueOnCancel = true)))
        assertThat(r.has(ErrorCode.CONTINUE_ON_CANCEL_RESERVED)).isTrue()
    }

    @Test
    fun `schedule rules`() {
        val m = macro(step(1, ActionParameters.ClickNode(sel)))
        val idle = ScheduleSpec(ScheduleId("s"), m.id, kind = ScheduleKind.Interval(15), requiresDeviceIdle = true)
        assertThat(validator.validate(idle, m).has(ErrorCode.REQUIRES_DEVICE_IDLE_WITH_UI)).isTrue()
        val short = ScheduleSpec(ScheduleId("s"), m.id, kind = ScheduleKind.Interval(10))
        assertThat(validator.validate(short, m).has(ErrorCode.SCHEDULE_INTERVAL_TOO_SHORT)).isTrue()
        val env = object : ValidationEnvironment {
            override fun now() = MacroFixtures.T0
        }
        val past = ScheduleSpec(ScheduleId("s"), m.id, kind = ScheduleKind.OneTime(MacroFixtures.T0 - 1.seconds))
        assertThat(MacroValidator(env).validate(past, m).has(ErrorCode.SCHEDULE_TIME_IN_PAST)).isTrue()
    }
}
