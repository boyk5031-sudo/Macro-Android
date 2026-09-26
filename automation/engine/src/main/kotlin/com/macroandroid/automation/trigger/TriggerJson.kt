package com.macroandroid.automation.trigger

import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.flatMap
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Export/import envelope for trigger configurations (`*.triggers.json`), versioned like the macro schema. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TriggerDocument(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val schemaVersion: Int = TriggerJson.SCHEMA_VERSION,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val kind: String = TriggerJson.KIND,
    val triggers: List<TriggerConfiguration>,
)

/** Strict JSON for persistence (Room column) and for the export document. */
object TriggerJson {
    const val SCHEMA_VERSION = 1
    const val KIND = "macroandroid.triggers"
    const val MAX_BYTES = 256 * 1024
    const val MAX_TRIGGERS = 50

    /** Compact form used for the database column. */
    private val compact: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = false
        explicitNulls = false
    }

    /** Pretty form for the export document. Built from [compact] (an indent may only be set when pretty-printing). */
    val instance: Json = Json(compact) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encodeConfiguration(config: TriggerConfiguration): String =
        compact.encodeToString(TriggerConfiguration.serializer(), config)

    fun decodeConfiguration(json: String): AppResult<TriggerConfiguration> = try {
        AppResult.ok(compact.decodeFromString(TriggerConfiguration.serializer(), json))
    } catch (e: SerializationException) {
        AppResult.err(ErrorCode.DB_CORRUPT, detail = "trigger json", cause = e)
    } catch (e: IllegalArgumentException) {
        AppResult.err(ErrorCode.DB_CORRUPT, detail = "trigger json", cause = e)
    }

    fun encodeDocument(triggers: List<TriggerConfiguration>): String =
        instance.encodeToString(TriggerDocument.serializer(), TriggerDocument(triggers = triggers))

    fun decodeDocument(json: String): AppResult<TriggerDocument> {
        if (json.length > MAX_BYTES) return AppResult.err(ErrorCode.FILE_TOO_LARGE)
        return parse(json).flatMap(::check)
    }

    private fun parse(json: String): AppResult<TriggerDocument> = try {
        AppResult.ok(instance.decodeFromString(TriggerDocument.serializer(), json))
    } catch (e: SerializationException) {
        AppResult.err(ErrorCode.IMPORT_PARSE_FAILED, detail = e.message?.take(MAX_DETAIL), cause = e)
    } catch (e: IllegalArgumentException) {
        AppResult.err(ErrorCode.IMPORT_PARSE_FAILED, detail = e.message?.take(MAX_DETAIL), cause = e)
    }

    private fun check(document: TriggerDocument): AppResult<TriggerDocument> {
        if (document.kind != KIND) return AppResult.err(ErrorCode.IMPORT_PARSE_FAILED, detail = "kind")
        if (document.schemaVersion > SCHEMA_VERSION) return AppResult.err(ErrorCode.SCHEMA_TOO_NEW)
        if (document.triggers.size > MAX_TRIGGERS) return AppResult.err(ErrorCode.LIMIT_EXCEEDED)
        val invalid = document.triggers.firstNotNullOfOrNull { t -> TriggerValidator.validate(t).firstOrNull() }
        return if (invalid != null) AppResult.err(invalid) else AppResult.ok(document)
    }

    private const val MAX_DETAIL = 200
}
