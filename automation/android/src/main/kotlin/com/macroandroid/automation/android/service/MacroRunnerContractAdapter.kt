package com.macroandroid.automation.android.service

import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.MacroId
import com.macroandroid.core.common.contract.MacroRunnerContract
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.map
import com.macroandroid.core.database.repository.ExecutionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Exposes the engine host to feature modules through the cross-feature contract. */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class MacroRunnerContractAdapter @Inject constructor(
    private val runner: MacroRunner,
    executions: ExecutionRepository,
) : MacroRunnerContract {
    override val activeCount: Flow<Int> = executions.observeActiveCount()

    override suspend fun runMacro(macroId: String, runRequestId: String?): AppResult<String> =
        runner.run(MacroId(macroId), runRequestId = runRequestId ?: Uuid.random().toString()).map { it.id.value }

    override fun cancel(executionId: String) {
        runner.cancel(ExecutionId(executionId))
    }
}
