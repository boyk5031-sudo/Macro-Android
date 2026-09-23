package com.macroandroid.core.common.error

/**
 * A typed failure. Never carries raw user data: [detail] is for developers (debug logs, redacted
 * diagnostics) and [cause] is never serialised. User-facing text is resolved from [code] in the UI layer.
 */
data class AppError(
    val code: ErrorCode,
    val detail: String? = null,
    val cause: Throwable? = null,
    /** Optional machine-readable context, e.g. step id or field name. Must not contain secrets. */
    val context: Map<String, String> = emptyMap(),
) {
    val category: ErrorCategory get() = code.category
    val retryable: Boolean get() = code.retryable

    override fun toString(): String = buildString {
        append("AppError(").append(code.name)
        if (detail != null) append(", detail=").append(detail)
        if (context.isNotEmpty()) append(", context=").append(context)
        append(')')
    }

    fun toException(): AppException = AppException(this)

    companion object {
        fun of(code: ErrorCode, detail: String? = null, cause: Throwable? = null): AppError =
            AppError(code = code, detail = detail, cause = cause)
    }
}

/** Carries an [AppError] across a suspension or API boundary that must throw. */
class AppException(val error: AppError) : RuntimeException(error.code.name, error.cause) {
    override val message: String get() = error.toString()
}
