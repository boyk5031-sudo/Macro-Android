package com.macroandroid.automation.serialization

import com.macroandroid.automation.model.MacroDocument
import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for import and export (docs/phase-1/08-macro-schema.md §5).
 * Strict by design: unknown keys, lenient quoting, and open polymorphism are all rejected.
 */
object MacroJson {
    val instance: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowStructuredMapKeys = false
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(document: MacroDocument): String = instance.encodeToString(MacroDocument.serializer(), document)
}
