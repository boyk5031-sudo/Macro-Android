package com.macroandroid.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.macroandroid.core.database.MacroDatabase
import com.macroandroid.core.database.dao.AppDao
import com.macroandroid.core.database.dao.ExecutionDao
import com.macroandroid.core.database.dao.ImportedApkDao
import com.macroandroid.core.database.dao.MacroDao
import com.macroandroid.core.database.dao.ScheduleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MacroDatabase =
        Room.databaseBuilder(context, MacroDatabase::class.java, MacroDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // No destructive fallback: a missing migration must fail loudly in CI's migration test, not wipe user data.
            .build()

    @Provides fun macroDao(db: MacroDatabase): MacroDao = db.macroDao()

    @Provides fun appDao(db: MacroDatabase): AppDao = db.appDao()

    @Provides fun importedApkDao(db: MacroDatabase): ImportedApkDao = db.importedApkDao()

    @Provides fun scheduleDao(db: MacroDatabase): ScheduleDao = db.scheduleDao()

    @Provides fun executionDao(db: MacroDatabase): ExecutionDao = db.executionDao()
}
