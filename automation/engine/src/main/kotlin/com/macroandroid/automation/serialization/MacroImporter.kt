package com.macroandroid.automation.serialization

import com.macroandroid.automation.model.MacroDocument
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.flatMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Import pipeline: size → UTF-8 → depth pre-scan → envelope → migrate → decode.
 * Validation of the resulting model is a separate step ([com.macroandroid.automation.validation.MacroValidator]).
 */
class MacroImporter(
    private val migrations: MacroMigrations = MacroMigrations.DEFAULT,
    private val json: kotlinx.serialization.json.Json = MacroJson.instance,
) {
    fun import(bytes: ByteArray): AppResult<MacroDocument> {
        if (bytes.size > MacroLimits.IMPORT_BYTES_MAX) {
            return AppResult.err(ErrorCode.FILE_TOO_LARGE, "bytes=${bytes.size}")
        }
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return AppResult.Err(AppError(ErrorCode.IMPORT_PARSE_FAILED, "invalid UTF-8", e))
        }
        return import(text)
    }

    fun import(text: String): AppResult<MacroDocument> {
        val depth = JsonDepthScanner.maxDepth(text)
        if (depth > MacroLimits.IMPORT_JSON_DEPTH_MAX) {
            return AppResult.err(ErrorCode.IMPORT_DEPTH_EXCEEDED, "depth=$depth")
        }
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return AppResult.Err(AppError(ErrorCode.IMPORT_PARSE_FAILED, e.message?.take(MAX_DETAIL), e))
        } catch (e: IllegalArgumentException) {
            return AppResult.Err(AppError(ErrorCode.IMPORT_PARSE_FAILED, e.message?.take(MAX_DETAIL), e))
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return AppResult.err(ErrorCode.IMPORT_PARSE_FAILED, "schemaVersion missing")
        return migrations.migrate(version, root).flatMap { migrated -> decode(migrated) }
    }

    private fun decode(root: JsonObject): AppResult<MacroDocument> {
        val macroCount = (root["macros"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        if (macroCount > MacroLimits.IMPORT_MACROS_MAX) {
            return AppResult.err(ErrorCode.LIMIT_EXCEEDED, "macros=$macroCount")
        }
        return try {
            val doc = json.decodeFromJsonElement(MacroDocument.serializer(), root)
            AppResult.ok(doc)
        } catch (e: SerializationException) {
            val message = e.message.orEmpty()
            val code = if (RESERVED_ACTION.containsMatchIn(message) || UNKNOWN_TYPE.containsMatchIn(message)) {
                ErrorCode.ACTION_NOT_SUPPORTED
            } else {
                ErrorCode.IMPORT_PARSE_FAILED
            }
            AppResult.Err(AppError(code, message.take(MAX_DETAIL), e))
        } catch (e: IllegalArgumentException) {
            AppResult.Err(AppError(ErrorCode.IMPORT_PARSE_FAILED, e.message?.take(MAX_DETAIL), e))
        }
    }

    private companion object {
        const val MAX_DETAIL = 300
        val RESERVED_ACTION = Regex("'(screenshot|gesture)'")
        val UNKNOWN_TYPE = Regex("Serializer for subclass '.*' is not found|Polymorphic serializer was not found")
    }
}

/** Cheap streaming depth scan performed before the full parse to bound memory on hostile input. */
object JsonDepthScanner {
    @Suppress("CyclomaticComplexMethod")
    fun maxDepth(text: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> { depth++; if (depth > max) max = depth }
                '}', ']' -> depth--
            }
        }
        return max
    }
}
