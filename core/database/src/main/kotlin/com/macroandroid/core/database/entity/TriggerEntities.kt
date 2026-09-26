package com.macroandroid.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Trigger Area configuration (docs/phase-12-trigger-areas.md §12). The typed model is stored as strict JSON
 * (`TriggerJson`) – the same approach as macro steps – with the columns the list screen and the runtime query on.
 */
@Entity(tableName = "trigger_configs", indices = [Index("package_name")])
data class TriggerConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "config_json") val configJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
