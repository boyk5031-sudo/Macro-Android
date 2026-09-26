package com.macroandroid.automation.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Export/import envelope. Always written at [CURRENT_SCHEMA_VERSION]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MacroDocument(
    /** Always written, even though it equals the default: importers key migrations off it (doc 08 §5). */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Instant,
    val appVersionCode: Int,
    val macros: List<Macro>,
    val schedules: List<ScheduleSpec> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
