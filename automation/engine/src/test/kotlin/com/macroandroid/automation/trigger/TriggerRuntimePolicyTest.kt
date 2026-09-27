package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.automation.testing.TriggerFixtures.LANDSCAPE
import com.macroandroid.automation.testing.TriggerFixtures.PORTRAIT
import com.macroandroid.core.common.error.ErrorCode
import org.junit.Test

class TriggerRuntimePolicyTest {
    private val game = TriggerFixtures.config(id = "game", packageName = "com.example.game")
    private val manual = TriggerFixtures.config(id = "manual")

    private fun input(
        foreground: String? = "com.example.game",
        armed: String? = null,
        access: Boolean = true,
        interactive: Boolean = true,
        geometry: com.macroandroid.core.common.display.DisplayGeometry? = PORTRAIT,
        configs: List<TriggerConfiguration> = listOf(game, manual),
        editing: String? = null,
    ) = RuntimeInput(
        configs,
        foreground,
        "com.macroandroid",
        armed?.let(::TriggerId),
        access,
        interactive,
        geometry,
        editingId = editing?.let(::TriggerId),
    )

    @Test
    fun `edit mode replaces gameplay overlays and follows the package binding`() {
        val editing = TriggerRuntimePolicy.decide(input(editing = "game"))
        assertThat(editing.editing?.id?.value).isEqualTo("game")
        assertThat(editing.visible).isEmpty()
        assertThat(editing.blockedReason).isNull()
        // Bound configuration over another app: nothing (neither editor nor gameplay overlay).
        assertThat(TriggerRuntimePolicy.decide(input(foreground = "com.other", editing = "game"))).isEqualTo(RuntimeDecision.IDLE)
        // Unbound configuration can be edited over any app but ours.
        assertThat(TriggerRuntimePolicy.decide(input(foreground = "com.other", editing = "manual")).editing?.id?.value)
            .isEqualTo("manual")
        assertThat(TriggerRuntimePolicy.decide(input(foreground = "com.macroandroid", editing = "manual")))
            .isEqualTo(RuntimeDecision.IDLE)
    }

    @Test
    fun `edit mode obeys access, screen, display and orientation rules and notices deletion`() {
        assertThat(TriggerRuntimePolicy.decide(input(editing = "game", access = false)).blockedReason)
            .isEqualTo(ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        assertThat(TriggerRuntimePolicy.decide(input(editing = "game", interactive = false)).blockedReason)
            .isEqualTo(ErrorCode.PRECONDITION_SCREEN_OFF)
        assertThat(TriggerRuntimePolicy.decide(input(editing = "game", geometry = null)).blockedReason)
            .isEqualTo(ErrorCode.DISPLAY_UNAVAILABLE)
        assertThat(TriggerRuntimePolicy.decide(input(editing = "game", geometry = LANDSCAPE)).blockedReason)
            .isEqualTo(ErrorCode.DISPLAY_ORIENTATION_MISMATCH)
        assertThat(TriggerRuntimePolicy.decide(input(editing = "game", configs = listOf(manual))).blockedReason)
            .isEqualTo(ErrorCode.TRIGGER_NOT_FOUND)
    }

    @Test
    fun `game start shows its trigger and game close hides it`() {
        assertThat(TriggerRuntimePolicy.decide(input()).visible.map { it.id.value }).containsExactly("game")
        assertThat(TriggerRuntimePolicy.decide(input(foreground = "com.other"))).isEqualTo(RuntimeDecision.IDLE)
        assertThat(TriggerRuntimePolicy.decide(input(foreground = null))).isEqualTo(RuntimeDecision.IDLE)
    }

    @Test
    fun `one game's trigger never appears over another game`() {
        val other = TriggerFixtures.config(id = "other", packageName = "com.example.other")
        val decision = TriggerRuntimePolicy.decide(input(configs = listOf(game, other)))
        assertThat(decision.visible.map { it.id.value }).containsExactly("game")
    }

    @Test
    fun `manually armed trigger shows over any app except our own`() {
        assertThat(TriggerRuntimePolicy.decide(input(foreground = "com.other", armed = "manual")).visible.map { it.id.value })
            .containsExactly("manual")
        assertThat(TriggerRuntimePolicy.decide(input(foreground = "com.macroandroid", armed = "manual")))
            .isEqualTo(RuntimeDecision.IDLE)
        // A package-bound configuration cannot be armed manually.
        assertThat(TriggerRuntimePolicy.decide(input(foreground = "com.other", armed = "game"))).isEqualTo(RuntimeDecision.IDLE)
    }

    @Test
    fun `disabled configuration is ignored`() {
        val off = game.copy(enabled = false)
        assertThat(TriggerRuntimePolicy.decide(input(configs = listOf(off)))).isEqualTo(RuntimeDecision.IDLE)
    }

    @Test
    fun `revoked access, screen off and unknown display block with a reason`() {
        assertThat(TriggerRuntimePolicy.decide(input(access = false)).blockedReason).isEqualTo(ErrorCode.GESTURE_DISPATCH_UNAVAILABLE)
        assertThat(TriggerRuntimePolicy.decide(input(interactive = false)).blockedReason).isEqualTo(ErrorCode.PRECONDITION_SCREEN_OFF)
        assertThat(TriggerRuntimePolicy.decide(input(geometry = null)).blockedReason).isEqualTo(ErrorCode.DISPLAY_UNAVAILABLE)
        assertThat(TriggerRuntimePolicy.decide(input(access = false)).visible).isEmpty()
    }

    @Test
    fun `rotation suspends and restores`() {
        assertThat(TriggerRuntimePolicy.decide(input(geometry = LANDSCAPE)).blockedReason)
            .isEqualTo(ErrorCode.DISPLAY_ORIENTATION_MISMATCH)
        assertThat(TriggerRuntimePolicy.decide(input(geometry = PORTRAIT)).blockedReason).isNull()
    }

    @Test
    fun `deleting the active configuration hides it`() {
        assertThat(TriggerRuntimePolicy.decide(input(configs = emptyList()))).isEqualTo(RuntimeDecision.IDLE)
    }
}
