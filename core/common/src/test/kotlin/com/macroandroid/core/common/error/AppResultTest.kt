package com.macroandroid.core.common.error

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class AppResultTest {

    @Test
    fun `map and flatMap propagate errors`() {
        val err: AppResult<Int> = AppResult.err(ErrorCode.DB_ERROR)
        assertThat(err.map { it + 1 }).isEqualTo(err)
        assertThat(err.flatMap { AppResult.ok(it + 1) }).isEqualTo(err)
        assertThat(AppResult.ok(1).map { it + 1 }).isEqualTo(AppResult.ok(2))
    }

    @Test
    fun `appRunCatching converts exceptions but rethrows cancellation`() {
        val r = appRunCatching(ErrorCode.IO_ERROR) { error("boom") }
        assertThat(r.errorOrNull()?.code).isEqualTo(ErrorCode.IO_ERROR)
        assertThat(r.errorOrNull()?.detail).isEqualTo("IllegalStateException")

        assertThrows(CancellationException::class.java) {
            appRunCatching { throw CancellationException("cancel") }
        }
    }

    @Test
    fun `appRunCatching keeps AppException error`() {
        val original = AppError(ErrorCode.MACRO_NOT_FOUND, detail = "id=1")
        val r = appRunCatching { throw original.toException() }
        assertThat(r.errorOrNull()).isEqualTo(original)
    }

    @Test
    fun `getOrThrow throws AppException`() {
        val ex = assertThrows(AppException::class.java) {
            AppResult.err(ErrorCode.UNEXPECTED).getOrThrow()
        }
        assertThat(ex.error.code).isEqualTo(ErrorCode.UNEXPECTED)
    }
}
