package com.macroandroid.automation.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Export/import envelope. Always written at [CURRENT_SCHEMA_VERSION]. */
@Serializable
data class MacroDocument(
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
