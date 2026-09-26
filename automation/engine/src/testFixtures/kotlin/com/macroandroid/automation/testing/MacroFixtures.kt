package com.macroandroid.automation.testing

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.ExecutionPolicy
import com.macroandroid.automation.model.FailureBehavior
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.RetryPolicy
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import kotlin.time.Duration
import kotlin.time.Instant

/** Builders that keep tests short and deterministic (fixed ids and timestamps). */
object MacroFixtures {
    val T0: Instant = Instant.parse("2026-09-23T10:00:00Z")

    fun stepId(n: Int): StepId = StepId("0a0a0a0a-0000-4000-8000-%012x".format(n))
    fun macroId(n: Int = 1): MacroId = MacroId("6f1d3a2e-2c7b-4a4e-9a55-%012x".format(n))

    fun step(
        n: Int,
        action: ActionParameters,
        label: String? = null,
        timeout: Duration? = null,
        retry: RetryPolicy? = null,
        onFailure: FailureBehavior = FailureBehavior.AbortMacro,
        enabled: Boolean = true,
    ) = MacroStep(stepId(n), label, enabled, action, timeout, retry, onFailure)

    fun macro(
        vararg steps: MacroStep,
        id: MacroId = macroId(),
        name: String = "Test macro",
        enabled: Boolean = true,
        policy: ExecutionPolicy = ExecutionPolicy(),
        variables: Map<String, VariableValue> = emptyMap(),
    ) = Macro(
        id = id, name = name, enabled = enabled, executionPolicy = policy, variables = variables,
        steps = steps.toList(), createdAt = T0, updatedAt = T0,
    )

    fun literal(text: String) = TextValue.Literal(text)
    fun log(n: Int, text: String) = step(n, ActionParameters.Log(message = literal(text)))
}
