package com.macroandroid.automation.android.gate

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.macroandroid.automation.android.accessibility.AccessibilityServiceRegistry
import com.macroandroid.automation.port.GateResult
import com.macroandroid.automation.port.PreconditionGate
import com.macroandroid.core.common.error.ErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Supplies the a11y consent flag from DataStore without making automation:android depend on core:datastore. */
fun interface ConsentProvider {
    suspend fun isAccessibilityConsentGranted(): Boolean
}

/**
 * Runtime gates for UI segments (doc 06 §4): screen interactive, keyguard unlocked, consent, service bound.
 * Ordered from cheapest/most-actionable to least so the BLOCKED reason is the first thing the user must fix.
 */
@Singleton
class AndroidPreconditionGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AccessibilityServiceRegistry,
    private val consent: ConsentProvider,
) : PreconditionGate {

    private val power by lazy { context.getSystemService<PowerManager>() }
    private val keyguard by lazy { context.getSystemService<KeyguardManager>() }

    override suspend fun checkUiSegment(needsAccessibility: Boolean): GateResult {
        if (power?.isInteractive == false) return GateResult.Blocked(ErrorCode.PRECONDITION_SCREEN_OFF)
        if (keyguard?.isKeyguardLocked == true) return GateResult.Blocked(ErrorCode.PRECONDITION_SCREEN_LOCKED)
        if (needsAccessibility) {
            if (!consent.isAccessibilityConsentGranted()) return GateResult.Blocked(ErrorCode.A11Y_CONSENT_MISSING)
            if (!AccessibilityServiceRegistry.isEnabledInSettings(context)) {
                return GateResult.Blocked(ErrorCode.A11Y_SERVICE_NOT_ENABLED)
            }
            if (!registry.isConnected) return GateResult.Blocked(ErrorCode.A11Y_SERVICE_DISCONNECTED)
        }
        return GateResult.Pass
    }

    override suspend fun awaitPass(needsAccessibility: Boolean, timeout: Duration): GateResult {
        val passed = withTimeoutOrNull(timeout) {
            while (checkUiSegment(needsAccessibility) !is GateResult.Pass) delay(POLL)
            true
        } ?: false
        return if (passed) GateResult.Pass else checkUiSegment(needsAccessibility)
    }

    private companion object {
        val POLL = 1.seconds
    }
}
