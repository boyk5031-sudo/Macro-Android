package com.macroandroid.automation.engine

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroStep
import kotlin.time.Duration

/** Static time budget (doc 08 §2.2) used for FGS type selection and previews. */
object MacroBudget {
    fun staticTimeBudget(macro: Macro): Duration {
        val policy = macro.executionPolicy
        fun stepBudget(step: MacroStep): Duration {
            val attempts = (step.retry ?: policy.defaultRetry).maxAttempts
            val retry = step.retry ?: policy.defaultRetry
            val backoff = (2..attempts).fold(Duration.ZERO) { acc, a -> acc + retry.delayBefore(a) }
            return when (val a = step.action) {
                is ActionParameters.If -> maxOf(listBudget(a.then), listBudget(a.`else`))
                is ActionParameters.Repeat -> (listBudget(a.body) + a.delayBetween) * a.iterationBound.coerceAtLeast(1)
                is ActionParameters.Parallel -> a.children.maxOfOrNull(::stepBudget) ?: Duration.ZERO
                is ActionParameters.Wait -> a.duration * attempts + backoff
                else -> (step.timeout ?: a.defaultTimeout.takeIf { it > Duration.ZERO } ?: policy.defaultStepTimeout) *
                    attempts + backoff
            }
        }
        fun listBudget(steps: List<MacroStep>): Duration =
            steps.filter { it.enabled }.fold(Duration.ZERO) { acc, s -> acc + stepBudget(s) }
        return listBudget(macro.steps).coerceAtMost(policy.totalTimeout)
    }

    /** Effective timeout for a leaf step attempt. */
    fun stepTimeout(macro: Macro, step: MacroStep): Duration =
        step.timeout ?: step.action.defaultTimeout.takeIf { it > Duration.ZERO } ?: macro.executionPolicy.defaultStepTimeout
}
