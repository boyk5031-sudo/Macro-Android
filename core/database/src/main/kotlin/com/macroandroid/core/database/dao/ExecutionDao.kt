package com.macroandroid.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.macroandroid.core.database.entity.AuditEntryEntity
import com.macroandroid.core.database.entity.ExecutionEntity
import com.macroandroid.core.database.entity.ExecutionStepEntity
import com.macroandroid.core.database.entity.LogEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(execution: ExecutionEntity)

    @Update
    suspend fun update(execution: ExecutionEntity)

    @Query("SELECT * FROM executions WHERE id = :id")
    suspend fun get(id: String): ExecutionEntity?

    @Query("SELECT * FROM executions WHERE id = :id")
    fun observe(id: String): Flow<ExecutionEntity?>

    @Query("SELECT * FROM executions WHERE run_request_id = :runRequestId AND queued_at >= :since ORDER BY queued_at DESC LIMIT 1")
    suspend fun findByRunRequestId(runRequestId: String, since: Long): ExecutionEntity?

    @Query("SELECT * FROM executions WHERE state IN ('QUEUED','PREPARING','RUNNING','PAUSED','BLOCKED') ORDER BY queued_at")
    suspend fun active(): List<ExecutionEntity>

    @Query("SELECT * FROM executions WHERE state IN ('QUEUED','PREPARING','RUNNING','PAUSED','BLOCKED') ORDER BY queued_at")
    fun observeActive(): Flow<List<ExecutionEntity>>

    @Query("SELECT COUNT(*) FROM executions WHERE state IN ('QUEUED','PREPARING','RUNNING','PAUSED','BLOCKED')")
    fun observeActiveCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM executions
        WHERE (:macroId IS NULL OR macro_id = :macroId)
          AND (:state IS NULL OR state = :state)
        ORDER BY queued_at DESC LIMIT :limit OFFSET :offset
        """,
    )
    fun observeHistory(macroId: String?, state: String?, limit: Int, offset: Int): Flow<List<ExecutionEntity>>

    @Query("SELECT * FROM executions WHERE macro_id = :macroId ORDER BY queued_at DESC LIMIT :limit")
    fun observeRecentForMacro(macroId: String, limit: Int): Flow<List<ExecutionEntity>>

    @Query("SELECT * FROM executions ORDER BY queued_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExecutionEntity>>

    @Query(
        """
        UPDATE executions SET state = 'INTERRUPTED', ended_at = :now, error_code = :errorCode, error_category = 'CANCELLED'
        WHERE state IN ('QUEUED','PREPARING','RUNNING','PAUSED','BLOCKED') AND (owner_token IS NULL OR owner_token != :liveOwnerToken)
        """,
    )
    suspend fun interruptOrphans(liveOwnerToken: String, now: Long, errorCode: String): Int

    @Query(
        "UPDATE execution_steps SET state = 'CANCELLED', ended_at = :now WHERE state = 'RUNNING' " +
            "AND execution_id IN (SELECT id FROM executions WHERE state = 'INTERRUPTED')",
    )
    suspend fun cancelOrphanSteps(now: Long): Int

    // ---- steps & logs ----
    @Upsert
    suspend fun upsertStep(step: ExecutionStepEntity)

    @Query("SELECT * FROM execution_steps WHERE execution_id = :executionId ORDER BY started_at, attempt")
    fun observeSteps(executionId: String): Flow<List<ExecutionStepEntity>>

    @Insert
    suspend fun insertLogs(entries: List<LogEntryEntity>)

    @Query("SELECT * FROM log_entries WHERE execution_id = :executionId ORDER BY at, id")
    fun observeLogs(executionId: String): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM log_entries WHERE execution_id = :executionId ORDER BY at, id")
    suspend fun logs(executionId: String): List<LogEntryEntity>

    @Query("SELECT * FROM execution_steps WHERE execution_id = :executionId ORDER BY started_at, attempt")
    suspend fun steps(executionId: String): List<ExecutionStepEntity>

    // ---- retention ----
    @Query("DELETE FROM executions WHERE ended_at IS NOT NULL AND ended_at < :before")
    suspend fun deleteEndedBefore(before: Long): Int

    @Query(
        """
        DELETE FROM executions WHERE id IN (
            SELECT id FROM executions WHERE ended_at IS NOT NULL ORDER BY ended_at DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun keepMostRecent(keep: Int): Int

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun logCount(): Long

    @Query("DELETE FROM log_entries WHERE id IN (SELECT id FROM log_entries ORDER BY id ASC LIMIT :count)")
    suspend fun deleteOldestLogs(count: Long): Int

    @Query("DELETE FROM executions")
    suspend fun deleteAll()

    @Transaction
    suspend fun applyRetention(endedBefore: Long, keepRuns: Int, maxLogs: Long) {
        deleteEndedBefore(endedBefore)
        keepMostRecent(keepRuns)
        val excess = logCount() - maxLogs
        if (excess > 0) deleteOldestLogs(excess)
    }

    // ---- audit ----
    @Insert
    suspend fun insertAudit(entry: AuditEntryEntity)

    @Query("SELECT * FROM audit_entries ORDER BY at DESC LIMIT :limit")
    fun observeAudit(limit: Int): Flow<List<AuditEntryEntity>>

    @Query("DELETE FROM audit_entries WHERE at < :before")
    suspend fun deleteAuditBefore(before: Long): Int
}
