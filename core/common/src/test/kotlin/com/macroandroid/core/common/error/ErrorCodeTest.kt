package com.macroandroid.core.common.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ErrorCodeTest {

    @Test
    fun `override never widens retryability beyond the category default`() {
        ErrorCode.entries.forEach { code ->
            if (!code.category.defaultRetryable) {
                assertThat(code.retryable).isFalse()
            }
        }
    }

    @Test
    fun `documented narrowed codes are not retryable`() {
        listOf(
            ErrorCode.NODE_IS_PASSWORD,
            ErrorCode.NODE_AMBIGUOUS,
            ErrorCode.NO_ACTIVITY_FOR_INTENT,
            ErrorCode.APP_NOT_INSTALLED,
            ErrorCode.MACRO_TIMEOUT,
            ErrorCode.QUEUE_TIMEOUT,
        ).forEach { assertThat(it.retryable).named(it.name).isFalse() }
    }

    @Test
    fun `retryable categories keep default for plain codes`() {
        assertThat(ErrorCode.NODE_NOT_FOUND.retryable).isTrue()
        assertThat(ErrorCode.STEP_TIMEOUT.retryable).isTrue()
        assertThat(ErrorCode.LOCK_CONTENTION.retryable).isTrue()
    }

    @Test
    fun `every category has at least one code`() {
        val used = ErrorCode.entries.map { it.category }.toSet()
        assertThat(used).containsExactlyElementsIn(ErrorCategory.entries)
    }
}
