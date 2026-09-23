package com.macroandroid.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Mirrors `ExecutionRecord` (docs/phase-1/07 §1.1). Macro rows may be deleted; executions keep the name. */
@Entity(
    tableName = "executions",
    indices = [Index("macro_id"), Index("state"), Index("queued_at"), Index(value = ["run_request_id"])],
)
data class ExecutionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "macro_id") val macroId: String,
    @ColumnInfo(name = "macro_revision") val macroRevision: Int,
    @ColumnInfo(name = "macro_name") val macroName: String,
    @ColumnInfo(name = "origin_json") val originJson: String,
    @ColumnInfo(name = "run_request_id") val runRequestId: String,
    val state: String,
    @ColumnInfo(name = "owner_token") val ownerToken: String?,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "paused_at") val pausedAt: Long?,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long?,
    @ColumnInfo(name = "blocked_code") val blockedCode: String?,
    @ColumnInfo(name = "blocked_detail") val blockedDetail: String?,
    @ColumnInfo(name = "current_step_index") val currentStepIndex: Int,
    @ColumnInfo(name = "current_step_id") val currentStepId: String?,
    @ColumnInfo(name = "current_attempt") val currentAttempt: Int,
    @ColumnInfo(name = "completed_steps") val completedSteps: Int,
    @ColumnInfo(name = "total_static_steps") val totalStaticSteps: Int,
    @ColumnInfo(name = "retry_total") val retryTotal: Int,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_category") val errorCategory: String?,
    @ColumnInfo(name = "error_detail") val errorDetail: String?,
)

@Entity(
    tableName = "execution_steps",
    primaryKeys = ["execution_id", "step_id", "attempt"],
    foreignKeys = [ForeignKey(ExecutionEntity::class, ["id"], ["execution_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("execution_id")],
)
data class ExecutionStepEntity(
    @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "step_id") val stepId: String,
    @ColumnInfo(name = "step_index") val stepIndex: Int,
    val attempt: Int,
    @ColumnInfo(name = "action_type") val actionType: String,
    val state: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_category") val errorCategory: String?,
    @ColumnInfo(name = "output_summary") val outputSummary: String?,
)

@Entity(
    tableName = "log_entries",
    foreignKeys = [ForeignKey(ExecutionEntity::class, ["id"], ["execution_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("execution_id"), Index("at")],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "execution_id") val executionId: String,
    val at: Long,
    val level: String,
    @ColumnInfo(name = "step_index") val stepIndex: Int?,
    val attempt: Int?,
    val message: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
)

/** Security-relevant user actions (consent granted/revoked, import, export, delete). */
@Entity(tableName = "audit_entries", indices = [Index("at")])
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val subject: String?,
    val detail: String?,
)
