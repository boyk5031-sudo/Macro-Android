package com.macroandroid.core.database.repository

import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.ScheduleSpec
import kotlin.time.Instant

/** List-row projection of a macro; avoids decoding the step JSON for every row. */
data class MacroSummary(
    val id: MacroId,
    val name: String,
    val description: String,
    val profile: String,
    val tags: List<String>,
    val enabled: Boolean,
    val stepCount: Int,
    val requiresAccessibility: Boolean,
    val updatedAt: Instant,
    val lastRunAt: Instant?,
    val lastRunState: ExecutionState?,
)

/** A schedule as stored, with the scheduler bookkeeping columns. */
data class StoredSchedule(
    val spec: ScheduleSpec,
    val workName: String,
    val nextRunAt: Instant?,
    val lastPlannedAt: Instant?,
    val lastFiredAt: Instant?,
    val lastResult: String?,
    val updatedAt: Instant,
)

/** Audit log kinds (security-relevant user actions). */
enum class AuditKind {
    CONSENT_GRANTED,
    CONSENT_REVOKED,
    MACRO_IMPORTED,
    MACRO_EXPORTED,
    MACRO_DELETED,
    SECURE_VALUE_SET,
    DATA_WIPED,
    A11Y_SERVICE_CONNECTED,
    A11Y_SERVICE_DISCONNECTED,
}

data class AuditEntry(val at: Instant, val kind: AuditKind, val subject: String?, val detail: String?)
