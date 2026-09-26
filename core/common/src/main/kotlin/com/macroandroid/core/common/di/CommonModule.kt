package com.macroandroid.core.common.di

import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.coroutines.ApplicationScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton
import kotlin.time.Clock

/** Process-wide primitives: dispatchers, the application supervisor scope, and the wall clock. */
@Module
@InstallIn(SingletonComponent::class)
object CommonModule {

    @Provides
    @Singleton
    fun provideDispatchers(): AppDispatchers = AppDispatchers(
        io = Dispatchers.IO,
        default = Dispatchers.Default,
        main = Dispatchers.Main,
        mainImmediate = Dispatchers.Main.immediate,
    )

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(dispatchers: AppDispatchers): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System
}
