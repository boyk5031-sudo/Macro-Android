package com.macroandroid.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.macroandroid.core.database.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY next_run_at IS NULL, next_run_at")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE macro_id = :macroId")
    fun observeForMacro(macroId: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun get(id: String): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE id = :id")
    fun observe(id: String): Flow<ScheduleEntity?>

    @Query("SELECT * FROM schedules WHERE enabled = 1")
    suspend fun enabled(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules")
    suspend fun all(): List<ScheduleEntity>

    @Query("SELECT COUNT(*) FROM schedules WHERE enabled = 1")
    fun observeEnabledCount(): Flow<Int>

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity)

    @Query("UPDATE schedules SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE schedules SET next_run_at = :nextRunAt, last_planned_at = :lastPlannedAt WHERE id = :id")
    suspend fun setNextRun(id: String, nextRunAt: Long?, lastPlannedAt: Long?)

    @Query("UPDATE schedules SET last_fired_at = :firedAt, last_result = :result WHERE id = :id")
    suspend fun recordFired(id: String, firedAt: Long, result: String)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String)
}
