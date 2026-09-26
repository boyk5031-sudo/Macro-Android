package com.macroandroid.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.macroandroid.core.database.entity.AppFavoriteEntity
import com.macroandroid.core.database.entity.ImportedApkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT package_name FROM app_favorites")
    fun observeFavorites(): Flow<List<String>>

    @Upsert
    suspend fun addFavorite(favorite: AppFavoriteEntity)

    @Query("DELETE FROM app_favorites WHERE package_name = :packageName")
    suspend fun removeFavorite(packageName: String)

    @Query("DELETE FROM app_favorites WHERE package_name IN (:packageNames)")
    suspend fun removeFavorites(packageNames: List<String>)
}

@Dao
interface ImportedApkDao {
    @Query("SELECT * FROM imported_apks ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<ImportedApkEntity>>

    @Query("SELECT * FROM imported_apks WHERE id = :id")
    fun observe(id: String): Flow<ImportedApkEntity?>

    @Query("SELECT * FROM imported_apks WHERE id = :id")
    suspend fun get(id: String): ImportedApkEntity?

    @Query("SELECT * FROM imported_apks WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): ImportedApkEntity?

    @Query("SELECT * FROM imported_apks WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ImportedApkEntity?

    @Query("SELECT * FROM imported_apks WHERE package_name = :packageName AND version_code = :versionCode AND id != :excludingId LIMIT 1")
    suspend fun findSemanticDuplicate(packageName: String, versionCode: Long, excludingId: String): ImportedApkEntity?

    @Query("SELECT * FROM imported_apks")
    suspend fun all(): List<ImportedApkEntity>

    @Query("SELECT COUNT(*) FROM imported_apks")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(apk: ImportedApkEntity)

    @Query("UPDATE imported_apks SET grant_valid = :valid WHERE id = :id")
    suspend fun setGrantValid(id: String, valid: Boolean)

    @Query("UPDATE imported_apks SET status = :status, error_code = :errorCode WHERE id = :id")
    suspend fun setStatus(id: String, status: String, errorCode: String?)

    @Query("SELECT COUNT(*) FROM imported_apks WHERE uri = :uri")
    suspend fun countByUri(uri: String): Int

    @Query("UPDATE imported_apks SET installed_version_code = :installedVersionCode WHERE package_name = :packageName")
    suspend fun setInstalledVersion(packageName: String, installedVersionCode: Long?)

    @Query("UPDATE imported_apks SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: String, notes: String)

    @Query("DELETE FROM imported_apks WHERE id = :id")
    suspend fun delete(id: String)
}
