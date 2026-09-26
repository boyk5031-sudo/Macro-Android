package com.macroandroid.core.testing

import android.content.Context
import androidx.room.Room
import com.macroandroid.core.database.MacroDatabase
import com.macroandroid.core.database.dao.AppDao
import com.macroandroid.core.database.dao.ExecutionDao
import com.macroandroid.core.database.dao.ImportedApkDao
import com.macroandroid.core.database.dao.MacroDao
import com.macroandroid.core.database.dao.ScheduleDao
import com.macroandroid.core.database.di.DatabaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Replaces the on-disk Room database with an in-memory one in Hilt instrumented tests. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MacroDatabase =
        Room.inMemoryDatabaseBuilder(context, MacroDatabase::class.java).allowMainThreadQueries().build()

    @Provides fun macroDao(db: MacroDatabase): MacroDao = db.macroDao()
    @Provides fun appDao(db: MacroDatabase): AppDao = db.appDao()
    @Provides fun importedApkDao(db: MacroDatabase): ImportedApkDao = db.importedApkDao()
    @Provides fun scheduleDao(db: MacroDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun executionDao(db: MacroDatabase): ExecutionDao = db.executionDao()
}
