package com.macroandroid.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.macroandroid.core.database.entity.MacroDraftEntity
import com.macroandroid.core.database.entity.MacroEntity
import com.macroandroid.core.database.entity.MacroTagCrossRef
import com.macroandroid.core.database.entity.SecureValueEntity
import com.macroandroid.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros WHERE id = :id")
    fun observe(id: String): Flow<MacroEntity?>

    @Query("SELECT * FROM macros WHERE id = :id")
    suspend fun get(id: String): MacroEntity?

    @Query("SELECT * FROM macros WHERE enabled = 1")
    suspend fun enabled(): List<MacroEntity>

    @Query("SELECT normalised_name FROM macros WHERE profile = :profile AND id != :excludingId")
    suspend fun namesInProfile(profile: String, excludingId: String): List<String>

    @Query("SELECT DISTINCT profile FROM macros ORDER BY profile")
    fun observeProfiles(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM macros")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(macro: MacroEntity)

    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE macros SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE macros SET last_run_at = :at, last_run_state = :state WHERE id = :id")
    suspend fun recordLastRun(id: String, at: Long, state: String)

    // ---- tags ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Query("DELETE FROM macro_tags WHERE macro_id = :macroId")
    suspend fun clearTags(macroId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMacroTags(refs: List<MacroTagCrossRef>)

    @Query("SELECT tag FROM macro_tags WHERE macro_id = :macroId ORDER BY tag")
    suspend fun tagsOf(macroId: String): List<String>

    @Query("SELECT * FROM macro_tags")
    fun observeAllMacroTags(): Flow<List<MacroTagCrossRef>>

    @Query("DELETE FROM tags WHERE name NOT IN (SELECT DISTINCT tag FROM macro_tags)")
    suspend fun pruneUnusedTags()

    @Transaction
    suspend fun upsertWithTags(macro: MacroEntity, tags: List<String>) {
        upsert(macro)
        clearTags(macro.id)
        if (tags.isNotEmpty()) {
            insertTags(tags.map(::TagEntity))
            insertMacroTags(tags.map { MacroTagCrossRef(macro.id, it) })
        }
        pruneUnusedTags()
    }

    // ---- drafts ----
    @Upsert
    suspend fun upsertDraft(draft: MacroDraftEntity)

    @Query("SELECT * FROM macro_drafts WHERE macro_id = :macroId")
    suspend fun draft(macroId: String): MacroDraftEntity?

    @Query("DELETE FROM macro_drafts WHERE macro_id = :macroId")
    suspend fun deleteDraft(macroId: String)

    @Query("SELECT * FROM macro_drafts ORDER BY saved_at DESC")
    fun observeDrafts(): Flow<List<MacroDraftEntity>>

    // ---- secure values ----
    @Upsert
    suspend fun upsertSecureValue(value: SecureValueEntity)

    @Query("SELECT * FROM secure_values WHERE id = :id")
    suspend fun secureValue(id: String): SecureValueEntity?

    @Query("SELECT id FROM secure_values WHERE macro_id = :macroId")
    suspend fun secureValueIds(macroId: String): List<String>

    @Query("DELETE FROM secure_values WHERE id = :id")
    suspend fun deleteSecureValue(id: String)

    @Query("DELETE FROM secure_values WHERE macro_id = :macroId AND id NOT IN (:keepIds)")
    suspend fun pruneSecureValues(macroId: String, keepIds: List<String>)

    @Delete
    suspend fun deleteSecureValues(values: List<SecureValueEntity>)
}
