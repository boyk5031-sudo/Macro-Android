package com.macroandroid.core.common.contract

import com.macroandroid.core.common.error.AppResult
import kotlinx.coroutines.flow.Flow

/*
 * Cross-feature contracts (docs/phase-1/05-architecture.md §2.2): features never depend on each other;
 * they talk through these interfaces, which `:app` (or automation:android) binds.
 */

/** Starts a macro by id from any feature (apps detail "run macros using this app", macro list, shortcuts). */
interface MacroRunnerContract {
    /** Returns the execution id or a policy/validation error. */
    suspend fun runMacro(macroId: String, runRequestId: String? = null): AppResult<String>

    /** Runs a single saved step in isolation (FR-MAC-5); returns the execution id. */
    suspend fun testStep(macroId: String, stepId: String): AppResult<String>
    fun cancel(executionId: String)
    val activeCount: Flow<Int>
}

/** Home-screen shortcuts (apps + macros). */
interface ShortcutsContract {
    val isPinSupported: Boolean
    suspend fun pinApp(packageName: String, label: String): AppResult<Unit>
    suspend fun pinMacro(macroId: String, name: String): AppResult<Unit>
    suspend fun publishRecentMacros()
}

/** Accessibility consent + service status for gates and the permission center. */
interface ConsentContract {
    val consentGranted: Flow<Boolean>
    val serviceEnabledInSettings: Flow<Boolean>
    val serviceConnected: Flow<Boolean>
    suspend fun grantConsent()
    suspend fun revokeConsent()
}

/** Security audit trail writer, usable from any feature. */
interface AuditContract {
    suspend fun record(kind: String, subject: String? = null, detail: String? = null)
}

/** How many macros reference a given package (App detail). */
interface MacroUsageContract {
    fun observeMacroCountForPackage(packageName: String): Flow<Int>
}
