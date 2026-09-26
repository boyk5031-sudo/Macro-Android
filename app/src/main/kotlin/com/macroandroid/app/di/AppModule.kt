package com.macroandroid.app.di

import com.macroandroid.app.shortcuts.AppShortcuts
import com.macroandroid.core.common.contract.ShortcutsContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds abstract fun shortcuts(impl: AppShortcuts): ShortcutsContract
}
