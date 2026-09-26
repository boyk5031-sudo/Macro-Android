package com.macroandroid.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.macroandroid.core.database.entity.TriggerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TriggerDao {
    @Query("SELECT * FROM trigger_configs ORDER BY name COLLATE NOCASE, created_at")
    fun observeAll(): Flow<List<TriggerConfigEntity>>

    @Query("SELECT * FROM trigger_configs WHERE id = :id")
    fun observe(id: String): Flow<TriggerConfigEntity?>

    @Query("SELECT * FROM trigger_configs WHERE id = :id")
    suspend fun get(id: String): TriggerConfigEntity?

    @Query("SELECT * FROM trigger_configs ORDER BY name COLLATE NOCASE, created_at")
    suspend fun all(): List<TriggerConfigEntity>

    @Query("SELECT COUNT(*) FROM trigger_configs")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entity: TriggerConfigEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TriggerConfigEntity>)

    @Query("DELETE FROM trigger_configs WHERE id = :id")
    suspend fun delete(id: String)
}
