package com.macroandroid.core.common.error

import kotlinx.serialization.Serializable

/**
 * Coarse classification of every failure in the app (docs/phase-1/09-error-taxonomy.md).
 *
 * @property defaultRetryable whether codes in this category may be retried by the engine unless the
 * code itself narrows it. Codes may narrow retryability but never widen it.
 */
@Serializable
enum class ErrorCategory(val defaultRetryable: Boolean) {
    /** Input rejected before anything ran. */
    VALIDATION(false),

    /** A permission, consent, or gate is missing; the user must act. */
    PERMISSION(false),

    /** Runtime condition not met (screen locked, service disconnected). UI steps become BLOCKED. */
    PRECONDITION(false),

    /** The target app's UI did not behave as expected; commonly transient. */
    TARGET_UI(true),

    /** Timeouts, IO hiccups, lock contention. */
    TRANSIENT(true),

    /** Our own limits (rate limit, queue, budget); never retried. */
    POLICY(false),

    /** Persistence, crypto, or schema problems. */
    DATA(false),

    /** Bugs. Logged with stack trace in debug builds only. */
    INTERNAL(false),

    /** Cooperative cancellation; not an error from the user's point of view. */
    CANCELLED(false),
}
