package com.macroandroid.feature.macros.data

import com.macroandroid.core.common.contract.MacroUsageContract
import com.macroandroid.core.database.repository.MacroRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MacroUsage @Inject constructor(private val macros: MacroRepository) : MacroUsageContract {
    override fun observeMacroCountForPackage(packageName: String): Flow<Int> =
        macros.observeAllMacros().map { list -> list.count { packageName in it.targetPackages } }.distinctUntilChanged()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MacrosFeatureModule {
    @Binds abstract fun macroUsage(impl: MacroUsage): MacroUsageContract
}
