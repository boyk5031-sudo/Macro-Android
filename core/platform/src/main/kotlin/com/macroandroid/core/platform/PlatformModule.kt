package com.macroandroid.core.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import com.macroandroid.core.common.logging.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides
    @Singleton
    fun provideLogger(@ApplicationContext context: Context): Logger =
        AndroidLogger(debugEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
}
