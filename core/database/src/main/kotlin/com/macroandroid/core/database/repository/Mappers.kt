package com.macroandroid.core.database.repository

import com.macroandroid.automation.engine.BlockedReason
import com.macroandroid.automation.engine.ExecutionLogEntry
import com.macroandroid.automation.engine.ExecutionOrigin
import com.macroandroid.automation.engine.ExecutionRecord
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.engine.StepAttemptRecord
import com.macroandroid.automation.engine.StepState
import com.macroandroid.automation.model.ExecutionId
import com.macroandroid.automation.model.ExecutionPolicy
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroDocument
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.automation.serialization.MacroJson
import com.macroandroid.core.common.error.ErrorCategory
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.LogLevel
import com.macroandroid.core.database.entity.ExecutionEntity
import com.macroandroid.core.database.entity.ExecutionStepEntity
import com.macroandroid.core.database.entity.LogEntryEntity
import com.macroandroid.core.database.entity.MacroEntity
import com.macroandroid.core.database.entity.ScheduleEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant

/** Entity <-> engine model conversions. JSON columns use the strict [MacroJson] configuration. */
internal object Mappers {
    private val json = MacroJson.instance
    private val stepsSerializer = ListSerializer(MacroStep.serializer())
    private val variablesSerializer = MapSerializer(String.serializer(), VariableValue.serializer())

    fun normaliseName(name: String): String = name.trim().lowercase()

    fun toEntity(macro: Macro, now: Instant, existing: MacroEntity?): MacroEntity = MacroEntity(
        id = macro.id.value,
        revision = macro.revision,
        name = macro.name,
        normalisedName = normaliseName(macro.name),
        description = macro.description,
        profile = macro.profile,
        enabled = macro.enabled,
        stepsJson = json.encodeToString(stepsSerializer, macro.steps),
        policyJson = json.encodeToString(ExecutionPolicy.serializer(), macro.executionPolicy),
        variablesJson = json.encodeToString(variablesSerializer, macro.variables),
        schemaVersion = MacroDocument.CURRENT_SCHEMA_VERSION,
        stepCount = macro.allSteps().size,
        requiresAccessibility = macro.requiresAccessibility,
        createdAt = existing?.createdAt ?: macro.createdAt.toEpochMilliseconds(),
        updatedAt = now.toEpochMilliseconds(),
        lastRunAt = existing?.lastRunAt,
        lastRunState = existing?.lastRunState,
    )

    fun toMacro(entity: MacroEntity, tags: List<String>): Macro = Macro(
        id = MacroId(entity.id),
        revision = entity.revision,
        name = entity.name,
        description = entity.description,
        profile = entity.profile,
        tags = tags,
        enabled = entity.enabled,
        executionPolicy = json.decodeFromString(ExecutionPolicy.serializer(), entity.policyJson),
        variables = json.decodeFromString(variablesSerializer, entity.variablesJson),
        steps = json.decodeFromString(stepsSerializer, entity.stepsJson),
        createdAt = Instant.fromEpochMilliseconds(entity.createdAt),
        updatedAt = Instant.fromEpochMilliseconds(entity.updatedAt),
    )

    fun toSummary(entity: MacroEntity, tags: List<String>): MacroSummary = MacroSummary(
        id = MacroId(entity.id),
        name = entity.name,
        description = entity.description,
        profile = entity.profile,
        tags = tags,
        enabled = entity.enabled,
        stepCount = entity.stepCount,
        requiresAccessibility = entity.requiresAccessibility,
        updatedAt = Instant.fromEpochMilliseconds(entity.updatedAt),
        lastRunAt = entity.lastRunAt?.let(Instant::fromEpochMilliseconds),
        lastRunState = entity.lastRunState?.let { runCatching { ExecutionState.valueOf(it) }.getOrNull() },
    )

    // ---- schedules ----

    fun toEntity(spec: ScheduleSpec, now: Instant, existing: ScheduleEntity?): ScheduleEntity = ScheduleEntity(
        id = spec.id.value,
        macroId = spec.macroId.value,
        enabled = spec.enabled,
        specJson = json.encodeToString(ScheduleSpec.serializer(), spec),
        workName = "schedule:${spec.id.value}",
        nextRunAt = existing?.nextRunAt,
        lastPlannedAt = existing?.lastPlannedAt,
        lastFiredAt = existing?.lastFiredAt,
        lastResult = existing?.lastResult,
        createdAt = existing?.createdAt ?: now.toEpochMilliseconds(),
        updatedAt = now.toEpochMilliseconds(),
    )

    fun toSchedule(entity: ScheduleEntity): StoredSchedule = StoredSchedule(
        spec = json.decodeFromString(ScheduleSpec.serializer(), entity.specJson).copy(
            id = ScheduleId(entity.id),
            macroId = MacroId(entity.macroId),
            enabled = entity.enabled,
        ),
        workName = entity.workName,
        nextRunAt = entity.nextRunAt?.let(Instant::fromEpochMilliseconds),
        lastPlannedAt = entity.lastPlannedAt?.let(Instant::fromEpochMilliseconds),
        lastFiredAt = entity.lastFiredAt?.let(Instant::fromEpochMilliseconds),
        lastResult = entity.lastResult,
        updatedAt = Instant.fromEpochMilliseconds(entity.updatedAt),
    )

    // ---- executions ----

    fun toEntity(r: ExecutionRecord): ExecutionEntity = ExecutionEntity(
        id = r.id.value,
        macroId = r.macroId.value,
        macroRevision = r.macroRevision,
        macroName = r.macroName,
        originJson = json.encodeToString(ExecutionOrigin.serializer(), r.origin),
        runRequestId = r.runRequestId,
        state = r.state.name,
        ownerToken = r.ownerToken,
        queuedAt = r.queuedAt.toEpochMilliseconds(),
        startedAt = r.startedAt?.toEpochMilliseconds(),
        endedAt = r.endedAt?.toEpochMilliseconds(),
        pausedAt = r.pausedAt?.toEpochMilliseconds(),
        blockedAt = r.blockedAt?.toEpochMilliseconds(),
        blockedCode = r.blockedReason?.code?.name,
        blockedDetail = r.blockedReason?.detail,
        currentStepIndex = r.currentStepIndex,
        currentStepId = r.currentStepId?.value,
        currentAttempt = r.currentAttempt,
        completedSteps = r.completedSteps,
        totalStaticSteps = r.totalStaticSteps,
        retryTotal = r.retryTotal,
        errorCode = r.errorCode?.name,
        errorCategory = r.errorCategory?.name,
        errorDetail = r.errorDetail,
    )

    fun toRecord(e: ExecutionEntity): ExecutionRecord = ExecutionRecord(
        id = ExecutionId(e.id),
        macroId = MacroId(e.macroId),
        macroRevision = e.macroRevision,
        macroName = e.macroName,
        origin = runCatching { json.decodeFromString(ExecutionOrigin.serializer(), e.originJson) }
            .getOrDefault(ExecutionOrigin.Manual),
        runRequestId = e.runRequestId,
        state = ExecutionState.valueOf(e.state),
        ownerToken = e.ownerToken,
        queuedAt = Instant.fromEpochMilliseconds(e.queuedAt),
        startedAt = e.startedAt?.let(Instant::fromEpochMilliseconds),
        endedAt = e.endedAt?.let(Instant::fromEpochMilliseconds),
        pausedAt = e.pausedAt?.let(Instant::fromEpochMilliseconds),
        blockedAt = e.blockedAt?.let(Instant::fromEpochMilliseconds),
        blockedReason = e.blockedCode?.let { code -> BlockedReason(errorCode(code) ?: ErrorCode.UNEXPECTED, e.blockedDetail) },
        currentStepIndex = e.currentStepIndex,
        currentStepId = e.currentStepId?.let(::StepId),
        currentAttempt = e.currentAttempt,
        completedSteps = e.completedSteps,
        totalStaticSteps = e.totalStaticSteps,
        retryTotal = e.retryTotal,
        errorCode = e.errorCode?.let(::errorCode),
        errorCategory = e.errorCategory?.let { c -> runCatching { ErrorCategory.valueOf(c) }.getOrNull() },
        errorDetail = e.errorDetail,
    )

    fun toEntity(a: StepAttemptRecord): ExecutionStepEntity = ExecutionStepEntity(
        executionId = a.executionId.value,
        stepId = a.stepId.value,
        stepIndex = a.stepIndex,
        attempt = a.attempt,
        actionType = a.actionType,
        state = a.state.name,
        startedAt = a.startedAt.toEpochMilliseconds(),
        endedAt = a.endedAt?.toEpochMilliseconds(),
        errorCode = a.errorCode?.name,
        errorCategory = a.errorCategory?.name,
        outputSummary = a.outputSummary,
    )

    fun toAttempt(e: ExecutionStepEntity): StepAttemptRecord = StepAttemptRecord(
        executionId = ExecutionId(e.executionId),
        stepId = StepId(e.stepId),
        stepIndex = e.stepIndex,
        attempt = e.attempt,
        actionType = e.actionType,
        state = runCatching { StepState.valueOf(e.state) }.getOrDefault(StepState.FAILED),
        startedAt = Instant.fromEpochMilliseconds(e.startedAt),
        endedAt = e.endedAt?.let(Instant::fromEpochMilliseconds),
        errorCode = e.errorCode?.let(::errorCode),
        errorCategory = e.errorCategory?.let { c -> runCatching { ErrorCategory.valueOf(c) }.getOrNull() },
        outputSummary = e.outputSummary,
    )

    fun toEntity(l: ExecutionLogEntry): LogEntryEntity = LogEntryEntity(
        executionId = l.executionId.value,
        at = l.at.toEpochMilliseconds(),
        level = l.level.name,
        stepIndex = l.stepIndex,
        attempt = l.attempt,
        message = l.message,
        errorCode = l.errorCode?.name,
    )

    fun toLog(e: LogEntryEntity): ExecutionLogEntry = ExecutionLogEntry(
        executionId = ExecutionId(e.executionId),
        at = Instant.fromEpochMilliseconds(e.at),
        level = runCatching { LogLevel.valueOf(e.level) }.getOrDefault(LogLevel.INFO),
        stepIndex = e.stepIndex,
        attempt = e.attempt,
        message = e.message,
        errorCode = e.errorCode?.let(::errorCode),
    )

    private fun errorCode(name: String): ErrorCode? = runCatching { ErrorCode.valueOf(name) }.getOrNull()
}
