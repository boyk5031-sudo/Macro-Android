package com.macroandroid.automation.android.consent

import android.content.Context
import com.macroandroid.automation.android.accessibility.AccessibilityServiceListener
import com.macroandroid.automation.android.accessibility.AccessibilityServiceRegistry
import com.macroandroid.automation.android.gate.ConsentProvider
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.contract.ConsentContract
import com.macroandroid.core.common.coroutines.ApplicationScope
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.datastore.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Single source of truth for accessibility consent (doc 06 §4, ADR-0004):
 * consent lives in DataStore; enabled-in-Settings and connected come from the registry.
 * Revoking consent cancels all running executions and is audited.
 */
@Singleton
class AccessibilityConsent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val registry: AccessibilityServiceRegistry,
    private val executions: ExecutionRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) : ConsentProvider, ConsentContract, AccessibilityServiceListener, AuditContract {

    private val enabledInSettings = MutableStateFlow(AccessibilityServiceRegistry.isEnabledInSettings(context))

    override suspend fun isAccessibilityConsentGranted(): Boolean = prefs.current().accessibilityConsentGranted

    override val consentGranted: Flow<Boolean> = prefs.preferences.map { it.accessibilityConsentGranted }.distinctUntilChanged()
    override val serviceEnabledInSettings: Flow<Boolean> = enabledInSettings
    override val serviceConnected: Flow<Boolean> = registry.service.map { it != null }.distinctUntilChanged()

    /** Re-reads Secure Settings; call from `onResume` of screens that show the a11y status. */
    fun refreshEnabledState() {
        enabledInSettings.value = AccessibilityServiceRegistry.isEnabledInSettings(context)
    }

    override suspend fun grantConsent() {
        prefs.grantAccessibilityConsent(clock.now().toEpochMilliseconds())
        executions.audit(AuditKind.CONSENT_GRANTED)
    }

    override suspend fun revokeConsent() {
        prefs.revokeAccessibilityConsent()
        executions.audit(AuditKind.CONSENT_REVOKED)
    }

    override fun onConnected() {
        enabledInSettings.value = true
        scope.launch { executions.audit(AuditKind.A11Y_SERVICE_CONNECTED) }
    }

    override fun onDisconnected() {
        refreshEnabledState()
        scope.launch { executions.audit(AuditKind.A11Y_SERVICE_DISCONNECTED) }
    }

    override suspend fun record(kind: String, subject: String?, detail: String?) {
        val known = AuditKind.entries.firstOrNull { it.name == kind } ?: AuditKind.OTHER
        executions.audit(known, subject, detail ?: kind.takeIf { known == AuditKind.OTHER })
    }

    suspend fun hasConsent(): Boolean = consentGranted.first()
}
