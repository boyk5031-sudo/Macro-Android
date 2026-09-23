package com.macroandroid.core.common.error

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.cancellation.CancellationException

/** Result type used across layers instead of exceptions. */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): AppError? = (this as? Err)?.error

    companion object {
        fun <T> ok(value: T): AppResult<T> = Ok(value)
        fun err(error: AppError): AppResult<Nothing> = Err(error)
        fun err(code: ErrorCode, detail: String? = null, cause: Throwable? = null): AppResult<Nothing> =
            Err(AppError(code, detail, cause))
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Ok -> transform(value)
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.mapError(transform: (AppError) -> AppError): AppResult<T> = when (this) {
    is AppResult.Ok -> this
    is AppResult.Err -> AppResult.Err(transform(error))
}

inline fun <T> AppResult<T>.getOrElse(onError: (AppError) -> T): T = when (this) {
    is AppResult.Ok -> value
    is AppResult.Err -> onError(error)
}

fun <T> AppResult<T>.getOrThrow(): T = when (this) {
    is AppResult.Ok -> value
    is AppResult.Err -> throw error.toException()
}

@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.onOk(action: (T) -> Unit): AppResult<T> {
    contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
    if (this is AppResult.Ok) action(value)
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.onErr(action: (AppError) -> Unit): AppResult<T> {
    contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
    if (this is AppResult.Err) action(error)
    return this
}

/**
 * Runs [block] converting thrown exceptions into [AppResult.Err]. [CancellationException] is always
 * rethrown so structured concurrency keeps working; [AppException] keeps its own error.
 */
inline fun <T> appRunCatching(
    fallback: ErrorCode = ErrorCode.UNEXPECTED,
    block: () -> T,
): AppResult<T> = try {
    AppResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: AppException) {
    AppResult.Err(e.error)
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    AppResult.Err(AppError(fallback, detail = e.javaClass.simpleName, cause = e))
}
