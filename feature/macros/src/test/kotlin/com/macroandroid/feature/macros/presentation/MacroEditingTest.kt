package com.macroandroid.feature.macros.presentation

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.model.TextValue
import org.junit.Test

class MacroEditingTest {
    private fun log(n: Int) = MacroStep(id = StepId("s$n"), action = ActionParameters.Log(message = TextValue.Literal("m$n")))

    private val tree = listOf(
        log(1),
        MacroStep(
            id = StepId("if"),
            action = ActionParameters.If(Condition.AppInstalled("a.b"), then = listOf(log(2)), `else` = listOf(log(3))),
        ),
        log(4),
    )

    @Test
    fun `insert into else branch`() {
        val path = listOf(MacroEditing.PathSegment(StepId("if"), 1))
        val out = MacroEditing.insert(tree, path, 99, log(5))
        val ifStep = out[1].action as ActionParameters.If
        assertThat(ifStep.`else`.map { it.id.value }).containsExactly("s3", "s5").inOrder()
        assertThat(ifStep.then.map { it.id.value }).containsExactly("s2")
    }

    @Test
    fun `move within top level and clamp at edges`() {
        val moved = MacroEditing.move(tree, emptyList(), StepId("s4"), -1)
        assertThat(moved.map { it.id.value }).containsExactly("s1", "s4", "if").inOrder()
        assertThat(MacroEditing.move(tree, emptyList(), StepId("s1"), -1)).isEqualTo(tree)
    }

    @Test
    fun `duplicate assigns fresh ids recursively`() {
        val out = MacroEditing.duplicate(tree, emptyList(), StepId("if"))
        assertThat(out).hasSize(4)
        val copy = out[2]
        assertThat(copy.id.value).isNotEqualTo("if")
        val inner = (copy.action as ActionParameters.If).then.single()
        assertThat(inner.id.value).isNotEqualTo("s2")
    }

    @Test
    fun `find returns path of nested step`() {
        val (step, path) = MacroEditing.find(tree, StepId("s3"))!!
        assertThat(step.id.value).isEqualTo("s3")
        assertThat(path).containsExactly(MacroEditing.PathSegment(StepId("if"), 1))
    }

    @Test
    fun `flatten yields rows with depth and else marker`() {
        val rows = MacroEditing.flatten(tree)
        assertThat(rows.map { it.step.id.value to it.depth }).containsExactly(
            "s1" to 0, "if" to 0, "if" to 1, "s2" to 1, "if" to 1, "s3" to 1, "s4" to 0,
        ).inOrder()
        assertThat(rows.filter { it.groupLabel != null }.map { it.groupLabel }).containsExactly(0, 1).inOrder()
    }
}
