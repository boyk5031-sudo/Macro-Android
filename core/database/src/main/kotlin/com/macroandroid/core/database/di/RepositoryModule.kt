package com.macroandroid.core.database.di

import com.macroandroid.automation.port.ExecutionStore
import com.macroandroid.automation.port.MacroSource
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.database.repository.MacroRepository
import com.macroandroid.core.database.repository.RoomExecutionRepository
import com.macroandroid.core.database.repository.RoomMacroRepository
import com.macroandroid.core.database.repository.RoomScheduleRepository
import com.macroandroid.core.database.repository.ScheduleRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun macroRepository(impl: RoomMacroRepository): MacroRepository
    @Binds abstract fun macroSource(impl: RoomMacroRepository): MacroSource
    @Binds abstract fun executionRepository(impl: RoomExecutionRepository): ExecutionRepository
    @Binds abstract fun executionStore(impl: RoomExecutionRepository): ExecutionStore
    @Binds abstract fun scheduleRepository(impl: RoomScheduleRepository): ScheduleRepository
}
