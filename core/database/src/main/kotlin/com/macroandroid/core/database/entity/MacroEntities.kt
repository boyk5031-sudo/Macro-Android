package com.macroandroid.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Macro header. The step tree is stored as one JSON blob per macro (`stepsJson`) in the current
 * schema-version wire format; that keeps import/export/editor round-trips exact and lets the engine
 * decode with the same strict serializer. Derived columns are denormalised for list queries.
 */
@Entity(
    tableName = "macros",
    indices = [Index("profile"), Index("name"), Index("updated_at")],
)
data class MacroEntity(
    @PrimaryKey val id: String,
    val revision: Int,
    val name: String,
    @ColumnInfo(name = "normalised_name") val normalisedName: String,
    val description: String,
    val profile: String,
    val enabled: Boolean,
    @ColumnInfo(name = "steps_json") val stepsJson: String,
    @ColumnInfo(name = "policy_json") val policyJson: String,
    @ColumnInfo(name = "variables_json") val variablesJson: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "step_count") val stepCount: Int,
    @ColumnInfo(name = "requires_accessibility") val requiresAccessibility: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long? = null,
    @ColumnInfo(name = "last_run_state") val lastRunState: String? = null,
)

@Entity(tableName = "tags")
data class TagEntity(@PrimaryKey val name: String)

@Entity(
    tableName = "macro_tags",
    primaryKeys = ["macro_id", "tag"],
    foreignKeys = [
        ForeignKey(MacroEntity::class, ["id"], ["macro_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["name"], ["tag"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tag")],
)
data class MacroTagCrossRef(
    @ColumnInfo(name = "macro_id") val macroId: String,
    val tag: String,
)

/** Autosaved editor draft; one per macro id (or per new-draft id). */
@Entity(tableName = "macro_drafts")
data class MacroDraftEntity(
    @PrimaryKey @ColumnInfo(name = "macro_id") val macroId: String,
    @ColumnInfo(name = "document_json") val documentJson: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

/** Keystore-encrypted parameter values (docs/phase-1/04 §7). */
@Entity(
    tableName = "secure_values",
    foreignKeys = [ForeignKey(MacroEntity::class, ["id"], ["macro_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("macro_id")],
)
data class SecureValueEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "macro_id") val macroId: String,
    @ColumnInfo(name = "step_id") val stepId: String,
    val param: String,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is SecureValueEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
