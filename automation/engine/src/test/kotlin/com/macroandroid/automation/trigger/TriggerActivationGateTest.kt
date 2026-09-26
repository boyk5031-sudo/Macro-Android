package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TriggerActivationGateTest {

    @Test
    fun `one press activates once and a second press after cooldown activates again`() {
        val gate = TriggerActivationGate(cooldownMs = 100)
        assertThat(gate.onDown(0)).isEqualTo(GateDecision.Activate)
        gate.onUp()
        assertThat(gate.onDown(100)).isEqualTo(GateDecision.Activate)
    }

    @Test
    fun `duplicate down events of one physical gesture do not re-activate`() {
        val gate = TriggerActivationGate(cooldownMs = 100)
        assertThat(gate.onDown(0)).isEqualTo(GateDecision.Activate)
        assertThat(gate.onDown(5)).isEqualTo(GateDecision.Reject(GateRejection.ALREADY_HELD))
        assertThat(gate.onDown(50)).isEqualTo(GateDecision.Reject(GateRejection.ALREADY_HELD))
    }

    @Test
    fun `cooldown rejects a new press that comes too soon`() {
        val gate = TriggerActivationGate(cooldownMs = 100)
        gate.onDown(0)
        gate.onUp()
        assertThat(gate.onDown(40)).isEqualTo(GateDecision.Reject(GateRejection.COOLDOWN))
        gate.onUp()
        assertThat(gate.onDown(99)).isEqualTo(GateDecision.Reject(GateRejection.COOLDOWN))
        gate.onUp()
        assertThat(gate.onDown(100)).isEqualTo(GateDecision.Activate)
    }

    @Test
    fun `cancel counts as release and reset clears cooldown`() {
        val gate = TriggerActivationGate(cooldownMs = 1_000)
        gate.onDown(0)
        gate.onUp()
        gate.reset()
        assertThat(gate.isHeld).isFalse()
        assertThat(gate.onDown(1)).isEqualTo(GateDecision.Activate)
    }

    @Test
    fun `zero cooldown still requires a release between activations`() {
        val gate = TriggerActivationGate(cooldownMs = 0)
        assertThat(gate.onDown(0)).isEqualTo(GateDecision.Activate)
        assertThat(gate.onDown(1)).isEqualTo(GateDecision.Reject(GateRejection.ALREADY_HELD))
        gate.onUp()
        assertThat(gate.onDown(1)).isEqualTo(GateDecision.Activate)
    }
}
