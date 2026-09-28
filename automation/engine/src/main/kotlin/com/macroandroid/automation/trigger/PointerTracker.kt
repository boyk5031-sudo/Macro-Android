package com.macroandroid.automation.trigger

/** Masked `MotionEvent` action, decoupled from Android so the tracker can be unit-tested on the JVM. */
enum class PointerAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

/** Where one pointer is right now. [index] is the position in the event's pointer array – it is NOT stable; [id] is. */
data class PointerSample(val id: Int, val index: Int, val xPx: Float, val yPx: Float)

/**
 * One touch event of the trigger window's own stream. [actionIndex] is `MotionEvent.getActionIndex()`: the index
 * of the pointer that went down/up for DOWN/POINTER_DOWN/POINTER_UP/UP, meaningless for MOVE/CANCEL.
 */
data class PointerEvent(
    val action: PointerAction,
    val actionIndex: Int,
    val pointers: List<PointerSample>,
    val atMs: Long,
) {
    /** The pointer the action refers to, resolved by index and reported with its stable id. */
    val actionPointer: PointerSample? get() = pointers.firstOrNull { it.index == actionIndex }
}

enum class PointerPhase { DOWN, MOVING, UP, CANCELLED }

data class TrackedPointer(
    val id: Int,
    val index: Int,
    val xPx: Float,
    val yPx: Float,
    val phase: PointerPhase,
    val downAtMs: Long,
    /** True for the pointer whose press activated (or tried to activate) the trigger. */
    val activating: Boolean,
)

/** What one event meant for the trigger: at most one press or one release per event, never both. */
sealed interface PointerTransition {
    data class Pressed(val pointer: TrackedPointer) : PointerTransition
    data class Released(val pointer: TrackedPointer, val cancelled: Boolean) : PointerTransition
    data object None : PointerTransition
}

/**
 * Multi-pointer bookkeeping for ONE trigger window (§B "trigger-state detection").
 *
 * Only the pointers that the system routes to the trigger window ever arrive here – with `FLAG_SPLIT_TOUCH` that
 * is exactly the fingers that went down inside the area. The game's own fingers never pass through this class,
 * which is what keeps the two state machines independent.
 *
 * Rules:
 *  * pointers are keyed by their stable **pointer id**, never by index (indices shift when another finger lifts);
 *  * the first pointer down while no press is active becomes the *activating* pointer → [PointerTransition.Pressed];
 *  * extra fingers inside the area are tracked (debug HUD) but are not presses;
 *  * only the activating pointer's POINTER_UP/UP (or a CANCEL) releases the press → [PointerTransition.Released];
 *  * once released, a still-resting finger does not re-press; the next finger that goes down does (subject to the
 *    controller's cooldown).
 *
 * Single-threaded by contract (main thread); tests drive it directly.
 */
class PointerTracker {
    private val byId = LinkedHashMap<Int, TrackedPointer>()
    private var activatingId: Int? = null
    private var lastAction: PointerAction? = null

    /** Snapshot of every pointer currently down in this window, in arrival order. */
    val pointers: List<TrackedPointer> get() = byId.values.toList()
    val activatingPointerId: Int? get() = activatingId
    val isPressed: Boolean get() = activatingId != null
    val lastActionSeen: PointerAction? get() = lastAction

    fun onEvent(event: PointerEvent): PointerTransition {
        lastAction = event.action
        return when (event.action) {
            PointerAction.DOWN -> {
                byId.clear() // a new stream; anything left over is stale
                activatingId = null
                press(event)
            }
            PointerAction.POINTER_DOWN -> press(event)
            PointerAction.MOVE -> {
                updateAll(event, PointerPhase.MOVING)
                PointerTransition.None
            }
            PointerAction.POINTER_UP, PointerAction.UP -> lift(event)
            PointerAction.CANCEL -> cancel()
        }
    }

    /** Forgets everything (window removed, service unbound). Returns the release the owner still has to deliver. */
    fun reset(): PointerTransition {
        val out = cancel()
        lastAction = null
        return out
    }

    private fun press(event: PointerEvent): PointerTransition {
        updateAll(event, phase = null)
        val sample = event.actionPointer ?: return PointerTransition.None
        val activates = activatingId == null
        val tracked = TrackedPointer(
            id = sample.id,
            index = sample.index,
            xPx = sample.xPx,
            yPx = sample.yPx,
            phase = PointerPhase.DOWN,
            downAtMs = event.atMs,
            activating = activates,
        )
        byId[sample.id] = tracked
        if (!activates) return PointerTransition.None
        activatingId = sample.id
        return PointerTransition.Pressed(tracked)
    }

    private fun lift(event: PointerEvent): PointerTransition {
        updateAll(event, phase = null)
        val sample = event.actionPointer
        val lifted = sample?.let { byId.remove(it.id) }
        if (event.action == PointerAction.UP) byId.clear() // last finger of the stream
        if (lifted == null || lifted.id != activatingId) return PointerTransition.None
        activatingId = null
        return PointerTransition.Released(lifted.copy(phase = PointerPhase.UP), cancelled = false)
    }

    private fun cancel(): PointerTransition {
        val active = activatingId?.let(byId::get)
        byId.clear()
        activatingId = null
        return if (active == null) {
            PointerTransition.None
        } else {
            PointerTransition.Released(active.copy(phase = PointerPhase.CANCELLED), cancelled = true)
        }
    }

    /** Refreshes index and position of every known pointer from the event, by id. */
    private fun updateAll(event: PointerEvent, phase: PointerPhase?) {
        event.pointers.forEach { s ->
            val known = byId[s.id] ?: return@forEach
            byId[s.id] = known.copy(index = s.index, xPx = s.xPx, yPx = s.yPx, phase = phase ?: known.phase)
        }
    }
}
