package com.macroandroid.feature.macros.presentation

import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.StepId

/**
 * Pure tree operations on nested step lists. A [StepPath] addresses a step by the ids of its container ancestors
 * and the child-group index inside each container (`then`=0/`else`=1 for If, `body`=0 for Repeat/Parallel).
 */
/** Empty list = top level. */
typealias StepPath = List<MacroEditing.PathSegment>

object MacroEditing {

    data class PathSegment(val containerId: StepId, val group: Int)

    fun freshIds(steps: List<MacroStep>): List<MacroStep> = steps.map { s ->
        s.copy(id = StepId.random(), action = mapGroups(s.action) { freshIds(it) })
    }

    fun groupsOf(action: ActionParameters): List<List<MacroStep>> = action.childGroups

    fun withGroups(action: ActionParameters, groups: List<List<MacroStep>>): ActionParameters = when (action) {
        is ActionParameters.If -> action.copy(then = groups[0], `else` = groups.getOrElse(1) { emptyList() })
        is ActionParameters.Repeat -> action.copy(body = groups[0])
        is ActionParameters.Parallel -> action.copy(children = groups[0])
        else -> action
    }

    private fun mapGroups(action: ActionParameters, f: (List<MacroStep>) -> List<MacroStep>): ActionParameters =
        if (action.isContainer) withGroups(action, groupsOf(action).map(f)) else action

    /** Applies [f] to the step list addressed by [path]. */
    fun updateList(steps: List<MacroStep>, path: StepPath, f: (List<MacroStep>) -> List<MacroStep>): List<MacroStep> {
        if (path.isEmpty()) return f(steps)
        val head = path.first()
        return steps.map { s ->
            if (s.id != head.containerId) {
                s
            } else {
                val groups = groupsOf(s.action).toMutableList()
                val idx = head.group.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
                if (groups.isEmpty()) return@map s
                groups[idx] = updateList(groups[idx], path.drop(1), f)
                s.copy(action = withGroups(s.action, groups))
            }
        }
    }

    fun insert(steps: List<MacroStep>, path: StepPath, index: Int, step: MacroStep): List<MacroStep> =
        updateList(steps, path) { list -> list.toMutableList().apply { add(index.coerceIn(0, size), step) } }

    fun replace(steps: List<MacroStep>, path: StepPath, step: MacroStep): List<MacroStep> =
        updateList(steps, path) { list -> list.map { if (it.id == step.id) step else it } }

    fun remove(steps: List<MacroStep>, path: StepPath, id: StepId): List<MacroStep> =
        updateList(steps, path) { list -> list.filterNot { it.id == id } }

    fun move(steps: List<MacroStep>, path: StepPath, id: StepId, delta: Int): List<MacroStep> =
        updateList(steps, path) { list ->
            val from = list.indexOfFirst { it.id == id }
            val to = from + delta
            if (from < 0 || to !in list.indices) {
                list
            } else {
                list.toMutableList().apply { add(to, removeAt(from)) }
            }
        }

    fun duplicate(steps: List<MacroStep>, path: StepPath, id: StepId): List<MacroStep> =
        updateList(steps, path) { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) {
                list
            } else {
                val copy = freshIds(listOf(list[i])).first()
                list.toMutableList().apply { add(i + 1, copy) }
            }
        }

    fun toggleEnabled(steps: List<MacroStep>, path: StepPath, id: StepId): List<MacroStep> =
        updateList(steps, path) { list -> list.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }

    /** Finds the step and its path anywhere in the tree. */
    fun find(steps: List<MacroStep>, id: StepId, path: StepPath = emptyList()): Pair<MacroStep, StepPath>? {
        for (s in steps) {
            if (s.id == id) return s to path
            groupsOf(s.action).forEachIndexed { g, group ->
                find(group, id, path + PathSegment(s.id, g))?.let { return it }
            }
        }
        return null
    }

    /** Depth of [path] (0 = top level); the validator caps nesting at MacroLimits.NESTING_DEPTH_MAX. */
    fun depth(path: StepPath): Int = path.size

    /** Flattened rows for the editor list: each step with its indentation depth and path. */
    data class Row(val step: MacroStep, val depth: Int, val path: StepPath, val groupLabel: Int?)

    fun flatten(steps: List<MacroStep>, path: StepPath = emptyList(), depth: Int = 0): List<Row> = buildList {
        for (s in steps) {
            add(Row(s, depth, path, null))
            groupsOf(s.action).forEachIndexed { g, group ->
                val childPath = path + PathSegment(s.id, g)
                if (g > 0 || (s.action is ActionParameters.If)) add(Row(s, depth + 1, childPath, g))
                addAll(flatten(group, childPath, depth + 1))
            }
        }
    }
}
