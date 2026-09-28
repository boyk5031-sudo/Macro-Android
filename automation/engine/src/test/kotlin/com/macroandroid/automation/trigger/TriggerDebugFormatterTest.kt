package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import com.macroandroid.core.common.error.ErrorCode
import org.junit.Test

class TriggerDebugFormatterTest {

    private val available = InjectionCapability(available = true, maxSimultaneousContacts = 10)

    @Test
    fun `formats pointer id, index, coordinates, action and trigger state`() {
        val entry = TriggerDebugFormatter.Entry(
            name = "Fire",
            state = TriggerState.EXECUTING,
            lastAction = PointerAction.POINTER_DOWN,
            lastActionIndex = 1,
            pointers = listOf(
                TrackedPointer(3, index = 1, xPx = 742.6f, yPx = 1260.2f, phase = PointerPhase.MOVING, downAtMs = 0, activating = true),
                TrackedPointer(5, index = 0, xPx = 10f, yPx = 20f, phase = PointerPhase.DOWN, downAtMs = 0, activating = false),
            ),
        )
        val lines = TriggerDebugFormatter.lines(listOf(entry), available)
        assertThat(lines).containsExactly(
            "Trigger \"Fire\"  STATE=EXECUTING  last=ACTION_POINTER_DOWN idx=1",
            "  Pointer ID=3 idx=1 X=742 Y=1260 STATE=MOVING *",
            "  Pointer ID=5 idx=0 X=10 Y=20 STATE=DOWN",
            "Injection: dispatchGesture (cancels / is cancelled by a concurrent user touch)",
        ).inOrder()
    }

    @Test
    fun `move and cancel carry no action index, idle trigger lists no pointer`() {
        val entry = TriggerDebugFormatter.Entry("Jump", TriggerState.IDLE, PointerAction.CANCEL, 0, emptyList())
        val lines = TriggerDebugFormatter.lines(listOf(entry), available)
        assertThat(lines[0]).isEqualTo("Trigger \"Jump\"  STATE=IDLE  last=ACTION_CANCEL")
        assertThat(lines[1]).isEqualTo("  no pointer in area")
    }

    @Test
    fun `no trigger and unavailable injection are stated plainly`() {
        val lines = TriggerDebugFormatter.lines(
            emptyList(),
            InjectionCapability(available = false, maxSimultaneousContacts = 0, reason = ErrorCode.A11Y_SERVICE_DISCONNECTED),
        )
        assertThat(lines).containsExactly(
            "Trigger debug: no active trigger area",
            "Injection: unavailable (A11Y_SERVICE_DISCONNECTED)",
        ).inOrder()
    }

    @Test
    fun `every masked action has a MotionEvent name`() {
        assertThat(PointerAction.entries.map(TriggerDebugFormatter::actionName)).containsExactly(
            "ACTION_DOWN",
            "ACTION_POINTER_DOWN",
            "ACTION_MOVE",
            "ACTION_POINTER_UP",
            "ACTION_UP",
            "ACTION_CANCEL",
        ).inOrder()
    }
}
