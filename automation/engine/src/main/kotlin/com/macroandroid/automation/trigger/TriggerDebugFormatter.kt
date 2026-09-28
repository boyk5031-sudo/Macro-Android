package com.macroandroid.automation.trigger

/**
 * Text for the debug read-out (§ Debugging). Pure so the exact wording is unit-tested; the Android HUD only draws
 * the lines. Format, per trigger:
 *
 * ```
 * Trigger "Fire"  STATE=EXECUTING  last=ACTION_POINTER_DOWN idx=1
 *   Pointer ID=3 idx=1 X=742 Y=1260 STATE=MOVING *
 * Injection: dispatchGesture (cancels / is cancelled by a concurrent user touch)
 * ```
 * `*` marks the activating pointer. Only pointers of the trigger windows appear – the game's fingers never enter
 * this process.
 */
object TriggerDebugFormatter {

    data class Entry(
        val name: String,
        val state: TriggerState,
        val lastAction: PointerAction?,
        val lastActionIndex: Int?,
        val pointers: List<TrackedPointer>,
    )

    fun lines(entries: List<Entry>, capability: InjectionCapability): List<String> = buildList {
        if (entries.isEmpty()) add("Trigger debug: no active trigger area")
        entries.forEach { e ->
            add(
                buildString {
                    append("Trigger \"").append(e.name.ifBlank { "?" }).append("\"  STATE=").append(e.state)
                    val action = e.lastAction
                    if (action != null) {
                        append("  last=").append(actionName(action))
                        if (action != PointerAction.MOVE && action != PointerAction.CANCEL && e.lastActionIndex != null) {
                            append(" idx=").append(e.lastActionIndex)
                        }
                    }
                },
            )
            if (e.pointers.isEmpty()) {
                add("  no pointer in area")
            } else {
                e.pointers.forEach { p ->
                    add(
                        buildString {
                            append("  Pointer ID=").append(p.id).append(" idx=").append(p.index)
                            append(" X=").append(p.xPx.toInt()).append(" Y=").append(p.yPx.toInt())
                            append(" STATE=").append(p.phase)
                            if (p.activating) append(" *")
                        },
                    )
                }
            }
        }
        add(
            when {
                !capability.available -> "Injection: unavailable (${capability.reason})"
                capability.coexistsWithUserTouch -> "Injection: independent of user touches"
                else -> "Injection: dispatchGesture (cancels / is cancelled by a concurrent user touch)"
            },
        )
    }

    fun actionName(action: PointerAction): String = when (action) {
        PointerAction.DOWN -> "ACTION_DOWN"
        PointerAction.POINTER_DOWN -> "ACTION_POINTER_DOWN"
        PointerAction.MOVE -> "ACTION_MOVE"
        PointerAction.POINTER_UP -> "ACTION_POINTER_UP"
        PointerAction.UP -> "ACTION_UP"
        PointerAction.CANCEL -> "ACTION_CANCEL"
    }
}
