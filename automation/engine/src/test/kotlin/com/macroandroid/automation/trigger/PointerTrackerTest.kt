package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Drives the tracker with the event shapes Android produces for the trigger window's own stream. Pointer IDs are
 * deliberately non-sequential (the game usually already owns id 0) so any confusion of id and index shows up.
 */
class PointerTrackerTest {

    private val tracker = PointerTracker()

    private fun event(action: PointerAction, actionIndex: Int, at: Long, vararg pointers: Triple<Int, Float, Float>) =
        PointerEvent(
            action = action,
            actionIndex = actionIndex,
            pointers = pointers.mapIndexed { index, (id, x, y) -> PointerSample(id, index, x, y) },
            atMs = at,
        )

    @Test
    fun `first pointer down presses, its up releases, with the stable id`() {
        val pressed = tracker.onEvent(event(PointerAction.DOWN, 0, 10, Triple(3, 100f, 200f)))
        assertThat(pressed).isInstanceOf(PointerTransition.Pressed::class.java)
        val p = (pressed as PointerTransition.Pressed).pointer
        assertThat(p.id).isEqualTo(3)
        assertThat(p.index).isEqualTo(0)
        assertThat(p.xPx).isEqualTo(100f)
        assertThat(p.activating).isTrue()
        assertThat(tracker.isPressed).isTrue()
        assertThat(tracker.activatingPointerId).isEqualTo(3)

        assertThat(tracker.onEvent(event(PointerAction.MOVE, 0, 20, Triple(3, 110f, 210f)))).isEqualTo(PointerTransition.None)
        assertThat(tracker.pointers.single().phase).isEqualTo(PointerPhase.MOVING)
        assertThat(tracker.pointers.single().xPx).isEqualTo(110f)

        val released = tracker.onEvent(event(PointerAction.UP, 0, 30, Triple(3, 110f, 210f)))
        assertThat(released).isEqualTo(
            PointerTransition.Released(p.copy(index = 0, xPx = 110f, yPx = 210f, phase = PointerPhase.UP), cancelled = false),
        )
        assertThat(tracker.isPressed).isFalse()
        assertThat(tracker.pointers).isEmpty()
    }

    @Test
    fun `a second finger inside the area is tracked but is not a second press`() {
        tracker.onEvent(event(PointerAction.DOWN, 0, 0, Triple(2, 10f, 10f)))
        val second = tracker.onEvent(event(PointerAction.POINTER_DOWN, 1, 5, Triple(2, 10f, 10f), Triple(5, 40f, 40f)))
        assertThat(second).isEqualTo(PointerTransition.None)
        assertThat(tracker.pointers.map { it.id }).containsExactly(2, 5).inOrder()
        assertThat(tracker.pointers.map { it.activating }).containsExactly(true, false).inOrder()
        assertThat(tracker.activatingPointerId).isEqualTo(2)
    }

    @Test
    fun `lifting a non-activating pointer releases nothing and indices are re-read by id`() {
        tracker.onEvent(event(PointerAction.DOWN, 0, 0, Triple(2, 10f, 10f)))
        tracker.onEvent(event(PointerAction.POINTER_DOWN, 0, 5, Triple(5, 40f, 40f), Triple(2, 10f, 10f))) // id 5 now at index 0
        assertThat(tracker.activatingPointerId).isEqualTo(2)
        assertThat(tracker.pointers.first { it.id == 2 }.index).isEqualTo(1)

        val up = tracker.onEvent(event(PointerAction.POINTER_UP, 0, 9, Triple(5, 40f, 40f), Triple(2, 10f, 10f)))
        assertThat(up).isEqualTo(PointerTransition.None)
        assertThat(tracker.isPressed).isTrue()
        assertThat(tracker.pointers.map { it.id }).containsExactly(2)

        // After the lift the remaining finger is index 0 again; the release must still be attributed to id 2.
        val released = tracker.onEvent(event(PointerAction.UP, 0, 12, Triple(2, 12f, 12f)))
        assertThat((released as PointerTransition.Released).pointer.id).isEqualTo(2)
        assertThat(released.cancelled).isFalse()
    }

    @Test
    fun `activating pointer lifting first releases even though another finger stays down`() {
        tracker.onEvent(event(PointerAction.DOWN, 0, 0, Triple(2, 10f, 10f)))
        tracker.onEvent(event(PointerAction.POINTER_DOWN, 1, 5, Triple(2, 10f, 10f), Triple(5, 40f, 40f)))
        val released = tracker.onEvent(event(PointerAction.POINTER_UP, 0, 9, Triple(2, 10f, 10f), Triple(5, 40f, 40f)))
        assertThat(released).isInstanceOf(PointerTransition.Released::class.java)
        assertThat((released as PointerTransition.Released).pointer.id).isEqualTo(2)
        assertThat(tracker.isPressed).isFalse()
        assertThat(tracker.pointers.map { it.id }).containsExactly(5) // resting finger stays tracked, does not press

        // The next finger that goes down presses again (cooldown is the controller's business).
        val again = tracker.onEvent(event(PointerAction.POINTER_DOWN, 1, 20, Triple(5, 40f, 40f), Triple(7, 50f, 50f)))
        assertThat((again as PointerTransition.Pressed).pointer.id).isEqualTo(7)
    }

    @Test
    fun `cancel releases the activating pointer as cancelled and clears everything`() {
        tracker.onEvent(event(PointerAction.DOWN, 0, 0, Triple(2, 10f, 10f)))
        tracker.onEvent(event(PointerAction.POINTER_DOWN, 1, 5, Triple(2, 10f, 10f), Triple(5, 40f, 40f)))
        val cancelled = tracker.onEvent(event(PointerAction.CANCEL, 0, 8, Triple(2, 10f, 10f), Triple(5, 40f, 40f)))
        assertThat(cancelled).isInstanceOf(PointerTransition.Released::class.java)
        assertThat((cancelled as PointerTransition.Released).cancelled).isTrue()
        assertThat(cancelled.pointer.phase).isEqualTo(PointerPhase.CANCELLED)
        assertThat(tracker.pointers).isEmpty()
        assertThat(tracker.isPressed).isFalse()
    }

    @Test
    fun `cancel or reset without a press is a no-op and a new DOWN discards stale state`() {
        assertThat(tracker.onEvent(event(PointerAction.CANCEL, 0, 0))).isEqualTo(PointerTransition.None)
        assertThat(tracker.reset()).isEqualTo(PointerTransition.None)
        tracker.onEvent(event(PointerAction.DOWN, 0, 0, Triple(2, 10f, 10f)))
        // No UP ever arrived (e.g. the window was re-added); the next DOWN must still press.
        val pressed = tracker.onEvent(event(PointerAction.DOWN, 0, 50, Triple(4, 20f, 20f)))
        assertThat((pressed as PointerTransition.Pressed).pointer.id).isEqualTo(4)
        assertThat(tracker.pointers.map { it.id }).containsExactly(4)
    }

    @Test
    fun `reset while pressed reports the release the owner still has to deliver`() {
        tracker.onEvent(event(PointerAction.DOWN, 0, 0, Triple(9, 10f, 10f)))
        val out = tracker.reset()
        assertThat((out as PointerTransition.Released).pointer.id).isEqualTo(9)
        assertThat(out.cancelled).isTrue()
        assertThat(tracker.lastActionSeen).isNull()
    }

    @Test
    fun `action index outside the pointer list never crashes`() {
        assertThat(tracker.onEvent(event(PointerAction.DOWN, 3, 0, Triple(2, 10f, 10f)))).isEqualTo(PointerTransition.None)
        assertThat(tracker.isPressed).isFalse()
    }
}
