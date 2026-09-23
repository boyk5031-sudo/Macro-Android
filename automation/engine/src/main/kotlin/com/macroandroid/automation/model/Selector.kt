package com.macroandroid.automation.model

import kotlinx.serialization.Serializable

@Serializable
enum class TextMatch { EQUALS, EQUALS_IGNORE_CASE, CONTAINS, REGEX }

/**
 * Describes how to find an accessibility node. At least one of [viewId], [text], [contentDescription],
 * [className] must be set (validator `SELECTOR_EMPTY`).
 */
@Serializable
data class NodeSelector(
    /** Fully-qualified resource id, e.g. `com.android.settings:id/search`. */
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val textMatch: TextMatch = TextMatch.EQUALS_IGNORE_CASE,
    /** Restrict matching to windows of this package. */
    val packageName: String? = null,
    /** Zero-based index among matches, 0..50. */
    val index: Int = 0,
    /** If the match is not actionable, walk up to 5 ancestors looking for one that is. */
    val clickableAncestor: Boolean = true,
) {
    val isEmpty: Boolean
        get() = viewId == null && text == null && contentDescription == null && className == null

    /** Human-readable summary for logs and the editor; never includes typed text. */
    fun describe(): String = buildList {
        viewId?.let { add("id=$it") }
        text?.let { add("text${matchSymbol()}\"$it\"") }
        contentDescription?.let { add("desc${matchSymbol()}\"$it\"") }
        className?.let { add("class=$it") }
        packageName?.let { add("pkg=$it") }
        if (index != 0) add("#$index")
    }.joinToString(" ")

    private fun matchSymbol() = when (textMatch) {
        TextMatch.EQUALS -> "=="
        TextMatch.EQUALS_IGNORE_CASE -> "="
        TextMatch.CONTAINS -> "~"
        TextMatch.REGEX -> "/"
    }
}
