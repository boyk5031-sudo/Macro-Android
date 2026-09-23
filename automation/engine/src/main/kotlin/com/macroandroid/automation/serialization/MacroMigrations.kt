package com.macroandroid.automation.serialization

import com.macroandroid.automation.model.MacroDocument
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.serialization.json.JsonObject

/** One step of the schema migration chain. */
interface MacroMigration {
    val from: Int
    val to: Int
    fun migrate(document: JsonObject): JsonObject
}

/**
 * Applies migrations from an older schema version up to [MacroDocument.CURRENT_SCHEMA_VERSION].
 * The chain is intentionally empty for v1; adding v2 means adding `V1ToV2` here and a fixture test.
 */
class MacroMigrations(private val migrations: List<MacroMigration> = emptyList()) {

    fun migrate(fromVersion: Int, document: JsonObject): AppResult<JsonObject> {
        val target = MacroDocument.CURRENT_SCHEMA_VERSION
        if (fromVersion > target) {
            return AppResult.err(ErrorCode.SCHEMA_TOO_NEW, "document=$fromVersion app=$target")
        }
        var version = fromVersion
        var current = document
        while (version < target) {
            val step = migrations.firstOrNull { it.from == version }
                ?: return AppResult.Err(AppError(ErrorCode.SCHEMA_NO_MIGRATION_PATH, "from=$version"))
            current = step.migrate(current)
            version = step.to
        }
        return AppResult.ok(current)
    }

    companion object {
        val DEFAULT = MacroMigrations()
    }
}
