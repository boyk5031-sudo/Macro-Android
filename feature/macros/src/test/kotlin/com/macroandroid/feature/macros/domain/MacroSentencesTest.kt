package com.macroandroid.feature.macros.domain

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.SecureValueRef
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.model.TextValue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MacroSentencesTest {
    private var n = 0
    private fun step(a: ActionParameters, enabled: Boolean = true) = MacroStep(StepId("s${n++}"), action = a, enabled = enabled)
    private fun macro(vararg steps: MacroStep) = Macro(
        id = MacroId("m"), name = "M", steps = steps.toList(),
        createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `numbers steps continuously and indents nested branches`() {
        val m = macro(
            step(ActionParameters.LaunchApp("com.example")),
            step(
                ActionParameters.If(
                    Condition.AppInstalled("a.b"),
                    then = listOf(step(ActionParameters.Wait(2.seconds))),
                    `else` = listOf(step(ActionParameters.Stop(success = false, message = "no app"))),
                ),
            ),
            step(ActionParameters.Log(message = TextValue.Literal("done")), enabled = false),
        )
        val lines = MacroSentences.describe(m)
        assertThat(lines).containsExactly(
            "1. Launch com.example.",
            "2. If a.b is installed, then:",
            "    3. Wait 2s.",
            "    otherwise:",
            "    4. Stop with failure: no app.",
            "5. Log “done”. (disabled)",
        ).inOrder()
    }

    @Test
    fun `secure text is never rendered`() {
        val s = MacroSentences.sentence(
            ActionParameters.EnterText(selector = NodeSelector(viewId = "pwd"), text = TextValue.Secure(SecureValueRef("id")), sensitive = true),
        )
        assertThat(s).isEqualTo("Enter a secret value into #pwd.")
        assertThat(s).doesNotContain("id")
    }

    @Test
    fun `selector falls back to generic noun and shows index`() {
        val w = MacroSentences.Words()
        assertThat(MacroSentences.selector(NodeSelector(), w)).isEqualTo("the element")
        assertThat(MacroSentences.selector(NodeSelector(text = "OK", index = 1), w)).isEqualTo("“OK” (#2)")
    }
}
