package com.macroandroid.core.database.repository

import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.port.ExecutionStore
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.database.dao.ExecutionDao
import com.macroandroid.core.database.entity.AuditEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/** Durable execution history: engine persistence port + UI queries + retention + audit. */
interface ExecutionRepository : ExecutionStore {
    fun observe(id: ExecutionId): Flow<ExecutionRecord?>
    fun observeActive(): Flow<List<ExecutionRecord>>
    fun observeActiveCount(): Flow<Int>
    fun observeRecent(limit: Int): Flow<List<ExecutionRecord>>
    fun observeRecentForMacro(macroId: MacroId, limit: Int): Flow<List<ExecutionRecord>>
    fun observeHistory(macroId: MacroId?, state: ExecutionState?, limit: Int, offset: Int): Flow<List<ExecutionRecord>>
    fun observeSteps(id: ExecutionId): Flow<List<StepAttemptRecord>>
    fun observeLogs(id: ExecutionId): Flow<List<ExecutionLogEntry>>
    suspend fun get(id: ExecutionId): ExecutionRecord?
    suspend fun steps(id: ExecutionId): List<StepAttemptRecord>
    suspend fun logs(id: ExecutionId): List<ExecutionLogEntry>

    /** Marks records owned by a dead process as INTERRUPTED (doc 07 §7). Returns the number affected. */
    suspend fun interruptOrphans(liveOwnerToken: String, reason: ErrorCode): Int
    suspend fun applyRetention(maxAgeDays: Int, keepRuns: Int, maxLogs: Long)
    suspend fun deleteAll()

    suspend fun audit(kind: AuditKind, subject: String? = null, detail: String? = null)
    fun observeAudit(limit: Int): Flow<List<AuditEntry>>
}

@Singleton
class RoomExecutionRepository @Inject constructor(
    private val dao: ExecutionDao,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : ExecutionRepository {

    // ---- ExecutionStore (engine) ----

    override suspend fun insert(record: ExecutionRecord) = withContext(dispatchers.io) { dao.insert(Mappers.toEntity(record)) }

    override suspend fun update(record: ExecutionRecord) = withContext(dispatchers.io) { dao.update(Mappers.toEntity(record)) }

    override suspend fun findByRunRequestId(runRequestId: String, since: Instant): ExecutionRecord? =
        withContext(dispatchers.io) { dao.findByRunRequestId(runRequestId, since.toEpochMilliseconds())?.let(Mappers::toRecord) }

    override suspend fun insertStepAttempt(attempt: StepAttemptRecord) =
        withContext(dispatchers.io) { dao.upsertStep(Mappers.toEntity(attempt)) }

    override suspend fun updateStepAttempt(attempt: StepAttemptRecord) =
        withContext(dispatchers.io) { dao.upsertStep(Mappers.toEntity(attempt)) }

    override suspend fun appendLogs(entries: List<ExecutionLogEntry>) {
        if (entries.isEmpty()) return
        withContext(dispatchers.io) { dao.insertLogs(entries.map(Mappers::toEntity)) }
    }

    override suspend fun activeExecutions(): List<ExecutionRecord> =
        withContext(dispatchers.io) { dao.active().map(Mappers::toRecord) }

    // ---- queries ----

    override fun observe(id: ExecutionId): Flow<ExecutionRecord?> =
        dao.observe(id.value).map { it?.let(Mappers::toRecord) }.distinctUntilChanged()

    override fun observeActive(): Flow<List<ExecutionRecord>> = dao.observeActive().map { it.map(Mappers::toRecord) }

    override fun observeActiveCount(): Flow<Int> = dao.observeActiveCount().distinctUntilChanged()

    override fun observeRecent(limit: Int): Flow<List<ExecutionRecord>> = dao.observeRecent(limit).map { it.map(Mappers::toRecord) }

    override fun observeRecentForMacro(macroId: MacroId, limit: Int): Flow<List<ExecutionRecord>> =
        dao.observeRecentForMacro(macroId.value, limit).map { it.map(Mappers::toRecord) }

    override fun observeHistory(macroId: MacroId?, state: ExecutionState?, limit: Int, offset: Int): Flow<List<ExecutionRecord>> =
        dao.observeHistory(macroId?.value, state?.name, limit, offset).map { it.map(Mappers::toRecord) }

    override fun observeSteps(id: ExecutionId): Flow<List<StepAttemptRecord>> =
        dao.observeSteps(id.value).map { it.map(Mappers::toAttempt) }

    override fun observeLogs(id: ExecutionId): Flow<List<ExecutionLogEntry>> = dao.observeLogs(id.value).map { it.map(Mappers::toLog) }

    override suspend fun get(id: ExecutionId): ExecutionRecord? = withContext(dispatchers.io) { dao.get(id.value)?.let(Mappers::toRecord) }

    override suspend fun steps(id: ExecutionId): List<StepAttemptRecord> =
        withContext(dispatchers.io) { dao.steps(id.value).map(Mappers::toAttempt) }

    override suspend fun logs(id: ExecutionId): List<ExecutionLogEntry> =
        withContext(dispatchers.io) { dao.logs(id.value).map(Mappers::toLog) }

    // ---- maintenance ----

    override suspend fun interruptOrphans(liveOwnerToken: String, reason: ErrorCode): Int = withContext(dispatchers.io) {
        val now = clock.now().toEpochMilliseconds()
        val n = dao.interruptOrphans(liveOwnerToken, now, reason.name)
        dao.cancelOrphanSteps(now)
        n
    }

    override suspend fun applyRetention(maxAgeDays: Int, keepRuns: Int, maxLogs: Long) = withContext(dispatchers.io) {
        val cutoff = clock.now().toEpochMilliseconds() - maxAgeDays.toLong() * MILLIS_PER_DAY
        dao.applyRetention(cutoff, keepRuns, maxLogs)
        dao.deleteAuditBefore(clock.now().toEpochMilliseconds() - AUDIT_RETENTION_DAYS * MILLIS_PER_DAY)
        Unit
    }

    override suspend fun deleteAll() = withContext(dispatchers.io) { dao.deleteAll() }

    override suspend fun audit(kind: AuditKind, subject: String?, detail: String?) = withContext(dispatchers.io) {
        dao.insertAudit(AuditEntryEntity(at = clock.now().toEpochMilliseconds(), kind = kind.name, subject = subject, detail = detail))
    }

    override fun observeAudit(limit: Int): Flow<List<AuditEntry>> = dao.observeAudit(limit).map { rows ->
        rows.map {
            AuditEntry(
                at = Instant.fromEpochMilliseconds(it.at),
                kind = runCatching { AuditKind.valueOf(it.kind) }.getOrDefault(AuditKind.OTHER),
                subject = it.subject,
                detail = it.detail,
            )
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        const val AUDIT_RETENTION_DAYS = 365L
    }
}
