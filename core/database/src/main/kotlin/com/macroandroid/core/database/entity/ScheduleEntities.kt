package com.macroandroid.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(MacroEntity::class, ["id"], ["macro_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("macro_id"), Index("next_run_at")],
)
data class ScheduleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "macro_id") val macroId: String,
    val enabled: Boolean,
    @ColumnInfo(name = "spec_json") val specJson: String,
    @ColumnInfo(name = "work_name") val workName: String,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long?,
    @ColumnInfo(name = "last_planned_at") val lastPlannedAt: Long?,
    @ColumnInfo(name = "last_fired_at") val lastFiredAt: Long?,
    @ColumnInfo(name = "last_result") val lastResult: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
