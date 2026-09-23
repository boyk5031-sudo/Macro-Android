package com.macroandroid.core.common.util

/** Validation helpers shared by the schema validator and the UI. */
object Identifiers {
    private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val VARIABLE_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]{0,31}$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val TAG = Regex("^[a-z0-9][a-z0-9 _-]{0,29}$")

    fun isValidPackageName(value: String): Boolean = value.length <= MAX_PACKAGE_NAME && PACKAGE_NAME.matches(value)
    fun isValidVariableName(value: String): Boolean = VARIABLE_NAME.matches(value)
    fun isUuid(value: String): Boolean = UUID.matches(value)
    fun isValidTag(value: String): Boolean = TAG.matches(value)
    fun normaliseTag(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")

    const val MAX_PACKAGE_NAME = 255
}
