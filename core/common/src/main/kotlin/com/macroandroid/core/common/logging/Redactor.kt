package com.macroandroid.core.common.logging

import java.security.MessageDigest

/**
 * Redaction rules from docs/phase-1/04-threat-model-and-data-classification.md §6.
 * Secure values never appear in logs; in their place we store a short, salted-free hash prefix `h:`
 * so identical values can be correlated in one execution without being recoverable.
 */
object Redactor {
    const val REDACTED = "\u2022\u2022\u2022\u2022"
    private const val HASH_PREFIX = "h:"
    private const val HASH_LENGTH = 8
    private const val MAX_LOGGED_TEXT = 200

    /** Text typed into a field or read from a node; truncated and control characters stripped. */
    fun clipForLog(text: String, max: Int = MAX_LOGGED_TEXT): String {
        val clean = text.replace(CONTROL_CHARS, " ")
        return if (clean.length <= max) clean else clean.take(max) + "\u2026(${clean.length})"
    }

    /** Stable opaque token for a sensitive value. */
    fun hashToken(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return HASH_PREFIX + hex.take(HASH_LENGTH)
    }

    /** Redacts a URL to scheme + host (query strings and paths can carry tokens). */
    fun redactUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return REDACTED
        val hostStart = schemeEnd + 3
        val hostEnd = url.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let { if (it < 0) url.length else it }
        val host = url.substring(hostStart, hostEnd).substringAfter('@') // drop userinfo
        return url.substring(0, hostStart) + host + if (hostEnd < url.length) "/\u2026" else ""
    }

    private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")
}
