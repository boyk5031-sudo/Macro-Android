package com.macroandroid.core.common.error

import kotlinx.serialization.Serializable

/**
 * Closed set of error codes shared by the engine, repositories, and UI.
 * Names are stable identifiers: they are persisted in execution records and shown in diagnostics exports.
 */
@Serializable
@Suppress("MagicNumber")
enum class ErrorCode(
    val category: ErrorCategory,
    /** Narrowed retryability; defaults to the category's. Never wider than the category default. */
    private val retryableOverride: Boolean? = null,
) {
    // ---- VALIDATION -------------------------------------------------------------------------
    NAME_INVALID(ErrorCategory.VALIDATION),
    NAME_DUPLICATE(ErrorCategory.VALIDATION),
    NO_STEPS(ErrorCategory.VALIDATION),
    STEP_LIMIT(ErrorCategory.VALIDATION),
    NESTING_LIMIT(ErrorCategory.VALIDATION),
    REPEAT_BOUNDS(ErrorCategory.VALIDATION),
    PARALLEL_CONTAINS_UI_STEP(ErrorCategory.VALIDATION),
    PARALLEL_SIZE(ErrorCategory.VALIDATION),
    TIMEOUT_RANGE(ErrorCategory.VALIDATION),
    RETRY_RANGE(ErrorCategory.VALIDATION),
    PACKAGE_NAME_INVALID(ErrorCategory.VALIDATION),
    URL_SCHEME_NOT_ALLOWED(ErrorCategory.VALIDATION),
    URL_RUNTIME_CHECK(ErrorCategory.VALIDATION),
    SELECTOR_EMPTY(ErrorCategory.VALIDATION),
    REGEX_INVALID(ErrorCategory.VALIDATION),
    LABEL_DUPLICATE(ErrorCategory.VALIDATION),
    JUMP_TARGET_MISSING(ErrorCategory.VALIDATION),
    JUMP_BACKWARD(ErrorCategory.VALIDATION),
    VARIABLE_NAME_INVALID(ErrorCategory.VALIDATION),
    VARIABLE_UNDEFINED(ErrorCategory.VALIDATION),
    VARIABLE_LIMIT(ErrorCategory.VALIDATION),
    TEXT_TOO_LONG(ErrorCategory.VALIDATION),
    TAG_INVALID(ErrorCategory.VALIDATION),
    SECURE_IN_TEMPLATE(ErrorCategory.VALIDATION),
    SENSITIVE_FLAG_REQUIRED(ErrorCategory.VALIDATION),
    SECURE_VALUE_REDACTED(ErrorCategory.VALIDATION),
    ACTION_NOT_SUPPORTED(ErrorCategory.VALIDATION),
    CONTINUE_ON_CANCEL_RESERVED(ErrorCategory.VALIDATION),
    REQUIRES_DEVICE_IDLE_WITH_UI(ErrorCategory.VALIDATION),
    A11Y_STEP_WITHOUT_LAUNCH(ErrorCategory.VALIDATION),
    TARGET_APP_NOT_VISIBLE(ErrorCategory.VALIDATION),
    SCHEDULE_TIME_IN_PAST(ErrorCategory.VALIDATION),
    SCHEDULE_INTERVAL_TOO_SHORT(ErrorCategory.VALIDATION),
    IMPORT_PARSE_FAILED(ErrorCategory.VALIDATION),
    IMPORT_DEPTH_EXCEEDED(ErrorCategory.VALIDATION),
    SCHEMA_TOO_NEW(ErrorCategory.VALIDATION),
    SCHEMA_NO_MIGRATION_PATH(ErrorCategory.VALIDATION),
    FILE_TOO_LARGE(ErrorCategory.VALIDATION),
    LIMIT_EXCEEDED(ErrorCategory.VALIDATION),
    NOT_AN_APK(ErrorCategory.VALIDATION),

    // ---- PERMISSION -------------------------------------------------------------------------
    NOTIFICATIONS_DENIED(ErrorCategory.PERMISSION),
    A11Y_SERVICE_NOT_ENABLED(ErrorCategory.PERMISSION),
    A11Y_CONSENT_MISSING(ErrorCategory.PERMISSION),
    URI_PERMISSION_REVOKED(ErrorCategory.PERMISSION),
    SHORTCUT_PIN_UNSUPPORTED(ErrorCategory.PERMISSION),
    FGS_START_NOT_ALLOWED(ErrorCategory.PERMISSION),

    // ---- PRECONDITION -----------------------------------------------------------------------
    PRECONDITION_SCREEN_OFF(ErrorCategory.PRECONDITION),
    PRECONDITION_SCREEN_LOCKED(ErrorCategory.PRECONDITION),
    A11Y_SERVICE_DISCONNECTED(ErrorCategory.PRECONDITION),
    FOREGROUND_REQUIRED(ErrorCategory.PRECONDITION),
    UI_LOCK_TIMEOUT(ErrorCategory.PRECONDITION),
    BLOCKED_TIMEOUT(ErrorCategory.PRECONDITION),
    TARGET_WINDOW_NOT_ACTIVE(ErrorCategory.PRECONDITION),

    // ---- TARGET_UI --------------------------------------------------------------------------
    NODE_NOT_FOUND(ErrorCategory.TARGET_UI),
    NODE_NOT_VISIBLE(ErrorCategory.TARGET_UI),
    NODE_NOT_CLICKABLE(ErrorCategory.TARGET_UI),
    NODE_NOT_EDITABLE(ErrorCategory.TARGET_UI),
    NODE_NOT_SCROLLABLE(ErrorCategory.TARGET_UI),
    NODE_ACTION_REJECTED(ErrorCategory.TARGET_UI),
    NODE_STALE(ErrorCategory.TARGET_UI),
    WINDOW_CHANGED(ErrorCategory.TARGET_UI),
    NODE_IS_PASSWORD(ErrorCategory.TARGET_UI, retryableOverride = false),
    NODE_AMBIGUOUS(ErrorCategory.TARGET_UI, retryableOverride = false),
    GLOBAL_ACTION_FAILED(ErrorCategory.TARGET_UI),
    NO_ACTIVITY_FOR_INTENT(ErrorCategory.TARGET_UI, retryableOverride = false),
    APP_LAUNCH_FAILED(ErrorCategory.TARGET_UI),
    APP_NOT_INSTALLED(ErrorCategory.TARGET_UI, retryableOverride = false),
    APP_WINDOW_TIMEOUT(ErrorCategory.TARGET_UI),

    // ---- TRANSIENT --------------------------------------------------------------------------
    STEP_TIMEOUT(ErrorCategory.TRANSIENT),
    MACRO_TIMEOUT(ErrorCategory.TRANSIENT, retryableOverride = false),
    IO_ERROR(ErrorCategory.TRANSIENT),
    FILE_UNREADABLE(ErrorCategory.TRANSIENT),
    QUEUE_TIMEOUT(ErrorCategory.TRANSIENT, retryableOverride = false),
    LOCK_CONTENTION(ErrorCategory.TRANSIENT),

    // ---- POLICY -----------------------------------------------------------------------------
    RATE_LIMITED(ErrorCategory.POLICY),
    QUEUE_FULL(ErrorCategory.POLICY),
    MACRO_DISABLED(ErrorCategory.POLICY),
    CONCURRENT_SELF_NOT_ALLOWED(ErrorCategory.POLICY),
    STOPPED_BY_MACRO(ErrorCategory.POLICY),
    MISSED(ErrorCategory.POLICY),
    DUPLICATE_EXACT(ErrorCategory.POLICY),
    DUPLICATE_SEMANTIC(ErrorCategory.POLICY),

    // ---- DATA -------------------------------------------------------------------------------
    DB_ERROR(ErrorCategory.DATA),
    DB_CORRUPT(ErrorCategory.DATA),
    SECURE_VALUE_UNAVAILABLE(ErrorCategory.DATA),
    KEYSTORE_UNAVAILABLE(ErrorCategory.DATA),
    KEY_INVALIDATED(ErrorCategory.DATA),
    APK_PARSE_FAILED(ErrorCategory.DATA),
    CHECKSUM_MISMATCH(ErrorCategory.DATA),
    MACRO_NOT_FOUND(ErrorCategory.DATA),
    SCHEDULE_NOT_FOUND(ErrorCategory.DATA),
    EXECUTION_NOT_FOUND(ErrorCategory.DATA),

    // ---- INTERNAL ---------------------------------------------------------------------------
    UNEXPECTED(ErrorCategory.INTERNAL),
    ACTION_NOT_REGISTERED(ErrorCategory.INTERNAL),
    INVARIANT_VIOLATION(ErrorCategory.INTERNAL),

    // ---- CANCELLED --------------------------------------------------------------------------
    CANCELLED_BY_USER(ErrorCategory.CANCELLED),
    CANCELLED_PROCESS_DEATH(ErrorCategory.CANCELLED),
    CANCELLED_REBOOT(ErrorCategory.CANCELLED),
    CANCELLED_SUPERSEDED(ErrorCategory.CANCELLED),
    ;

    /** Effective retryability: the override may only narrow the category default. */
    val retryable: Boolean
        get() = category.defaultRetryable && (retryableOverride ?: true)
}
