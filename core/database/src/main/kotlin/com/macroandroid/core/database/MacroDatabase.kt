package com.macroandroid.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.macroandroid.core.database.dao.AppDao
import com.macroandroid.core.database.dao.ExecutionDao
import com.macroandroid.core.database.dao.ImportedApkDao
import com.macroandroid.core.database.dao.MacroDao
import com.macroandroid.core.database.dao.ScheduleDao
import com.macroandroid.core.database.entity.AppFavoriteEntity
import com.macroandroid.core.database.entity.AuditEntryEntity
import com.macroandroid.core.database.entity.ExecutionEntity
import com.macroandroid.core.database.entity.ExecutionStepEntity
import com.macroandroid.core.database.entity.ImportedApkEntity
import com.macroandroid.core.database.entity.LogEntryEntity
import com.macroandroid.core.database.entity.MacroDraftEntity
import com.macroandroid.core.database.entity.MacroEntity
import com.macroandroid.core.database.entity.MacroTagCrossRef
import com.macroandroid.core.database.entity.ScheduleEntity
import com.macroandroid.core.database.entity.SecureValueEntity
import com.macroandroid.core.database.entity.TagEntity

@Database(
    version = MacroDatabase.VERSION,
    exportSchema = true,
    entities = [
        MacroEntity::class,
        TagEntity::class,
        MacroTagCrossRef::class,
        MacroDraftEntity::class,
        SecureValueEntity::class,
        AppFavoriteEntity::class,
        ImportedApkEntity::class,
        ScheduleEntity::class,
        ExecutionEntity::class,
        ExecutionStepEntity::class,
        LogEntryEntity::class,
        AuditEntryEntity::class,
    ],
)
abstract class MacroDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao
    abstract fun appDao(): AppDao
    abstract fun importedApkDao(): ImportedApkDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun executionDao(): ExecutionDao

    companion object {
        const val VERSION = 1
        const val NAME = "macro.db"
    }
}
