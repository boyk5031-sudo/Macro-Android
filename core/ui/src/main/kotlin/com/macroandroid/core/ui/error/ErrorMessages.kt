package com.macroandroid.core.ui.error

import androidx.annotation.StringRes
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.ErrorCategory
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.ui.R

/** Maps the closed [ErrorCode] set to localisable user text (doc 09 §3 "user message" column). */
object ErrorMessages {

    @StringRes
    fun titleRes(code: ErrorCode): Int = when (code) {
        ErrorCode.NAME_INVALID -> R.string.err_name_invalid
        ErrorCode.NAME_DUPLICATE -> R.string.err_name_duplicate
        ErrorCode.NO_STEPS -> R.string.err_no_steps
        ErrorCode.STEP_LIMIT, ErrorCode.NESTING_LIMIT, ErrorCode.VARIABLE_LIMIT, ErrorCode.LIMIT_EXCEEDED,
        ErrorCode.PARALLEL_SIZE, ErrorCode.TEXT_TOO_LONG, ErrorCode.FILE_TOO_LARGE,
        -> R.string.err_limit_exceeded
        ErrorCode.PARALLEL_CONTAINS_UI_STEP -> R.string.err_parallel_ui
        ErrorCode.PACKAGE_NAME_INVALID -> R.string.err_package_invalid
        ErrorCode.URL_SCHEME_NOT_ALLOWED, ErrorCode.URL_RUNTIME_CHECK -> R.string.err_url_scheme
        ErrorCode.JUMP_TARGET_MISSING, ErrorCode.JUMP_BACKWARD, ErrorCode.LABEL_DUPLICATE -> R.string.err_jump
        ErrorCode.SENSITIVE_FLAG_REQUIRED, ErrorCode.SECURE_IN_TEMPLATE -> R.string.err_sensitive
        ErrorCode.SECURE_VALUE_REDACTED -> R.string.err_secure_redacted
        ErrorCode.IMPORT_PARSE_FAILED, ErrorCode.IMPORT_DEPTH_EXCEEDED -> R.string.err_import_parse
        ErrorCode.SCHEMA_TOO_NEW, ErrorCode.SCHEMA_NO_MIGRATION_PATH -> R.string.err_schema_too_new
        ErrorCode.NOT_AN_APK, ErrorCode.APK_PARSE_FAILED -> R.string.err_not_apk
        ErrorCode.NOTIFICATIONS_DENIED -> R.string.err_notifications_denied
        ErrorCode.A11Y_SERVICE_NOT_ENABLED, ErrorCode.A11Y_SERVICE_DISCONNECTED -> R.string.err_a11y_not_enabled
        ErrorCode.A11Y_CONSENT_MISSING -> R.string.err_a11y_consent
        ErrorCode.URI_PERMISSION_REVOKED -> R.string.err_uri_revoked
        ErrorCode.SHORTCUT_PIN_UNSUPPORTED -> R.string.err_shortcut_unsupported
        ErrorCode.FGS_START_NOT_ALLOWED, ErrorCode.FOREGROUND_REQUIRED -> R.string.err_foreground_required
        ErrorCode.PRECONDITION_SCREEN_OFF -> R.string.err_screen_off
        ErrorCode.PRECONDITION_SCREEN_LOCKED -> R.string.err_screen_locked
        ErrorCode.UI_LOCK_TIMEOUT, ErrorCode.LOCK_CONTENTION -> R.string.err_ui_busy
        ErrorCode.BLOCKED_TIMEOUT -> R.string.err_blocked_timeout
        ErrorCode.TARGET_WINDOW_NOT_ACTIVE, ErrorCode.WINDOW_CHANGED -> R.string.err_window_changed
        ErrorCode.NODE_NOT_FOUND, ErrorCode.NODE_NOT_VISIBLE, ErrorCode.NODE_STALE -> R.string.err_node_not_found
        ErrorCode.NODE_NOT_CLICKABLE, ErrorCode.NODE_NOT_EDITABLE, ErrorCode.NODE_NOT_SCROLLABLE,
        ErrorCode.NODE_ACTION_REJECTED, ErrorCode.GLOBAL_ACTION_FAILED,
        -> R.string.err_node_action
        ErrorCode.NODE_IS_PASSWORD -> R.string.err_node_password
        ErrorCode.NODE_AMBIGUOUS -> R.string.err_node_ambiguous
        ErrorCode.NO_ACTIVITY_FOR_INTENT, ErrorCode.APP_LAUNCH_FAILED, ErrorCode.APP_WINDOW_TIMEOUT -> R.string.err_launch_failed
        ErrorCode.APP_NOT_INSTALLED -> R.string.err_app_not_installed
        ErrorCode.STEP_TIMEOUT -> R.string.err_step_timeout
        ErrorCode.MACRO_TIMEOUT -> R.string.err_macro_timeout
        ErrorCode.IO_ERROR, ErrorCode.FILE_UNREADABLE -> R.string.err_io
        ErrorCode.QUEUE_TIMEOUT -> R.string.err_queue_timeout
        ErrorCode.RATE_LIMITED -> R.string.err_rate_limited
        ErrorCode.QUEUE_FULL -> R.string.err_queue_full
        ErrorCode.MACRO_DISABLED -> R.string.err_macro_disabled
        ErrorCode.CONCURRENT_SELF_NOT_ALLOWED -> R.string.err_already_running
        ErrorCode.STOPPED_BY_MACRO -> R.string.err_stopped_by_macro
        ErrorCode.MISSED -> R.string.err_missed
        ErrorCode.DUPLICATE_EXACT, ErrorCode.DUPLICATE_SEMANTIC -> R.string.err_duplicate
        ErrorCode.DB_ERROR, ErrorCode.DB_CORRUPT -> R.string.err_db
        ErrorCode.SECURE_VALUE_UNAVAILABLE, ErrorCode.KEYSTORE_UNAVAILABLE -> R.string.err_secure_unavailable
        ErrorCode.KEY_INVALIDATED -> R.string.err_key_invalidated
        ErrorCode.CHECKSUM_MISMATCH -> R.string.err_checksum
        ErrorCode.MACRO_NOT_FOUND, ErrorCode.SCHEDULE_NOT_FOUND, ErrorCode.EXECUTION_NOT_FOUND -> R.string.err_not_found
        ErrorCode.CANCELLED_BY_USER -> R.string.err_cancelled_user
        ErrorCode.CANCELLED_PROCESS_DEATH, ErrorCode.CANCELLED_REBOOT, ErrorCode.CANCELLED_SUPERSEDED -> R.string.err_interrupted
        else -> when (code.category) {
            ErrorCategory.VALIDATION -> R.string.err_validation_generic
            ErrorCategory.PERMISSION -> R.string.err_permission_generic
            ErrorCategory.PRECONDITION -> R.string.err_precondition_generic
            ErrorCategory.TARGET_UI -> R.string.err_target_generic
            ErrorCategory.TRANSIENT -> R.string.err_transient_generic
            ErrorCategory.POLICY -> R.string.err_policy_generic
            ErrorCategory.DATA -> R.string.err_data_generic
            ErrorCategory.INTERNAL -> R.string.err_internal_generic
            ErrorCategory.CANCELLED -> R.string.err_cancelled_user
        }
    }

    @StringRes
    fun titleRes(error: AppError): Int = titleRes(error.code)
}
