package com.macroandroid.core.common.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactorTest {

    @Test
    fun `hash token is stable, short, and not the value`() {
        val a = Redactor.hashToken("hunter2")
        assertThat(a).isEqualTo(Redactor.hashToken("hunter2"))
        assertThat(a).startsWith("h:")
        assertThat(a).hasLength(10)
        assertThat(a).doesNotContain("hunter")
        assertThat(a).isNotEqualTo(Redactor.hashToken("hunter3"))
    }

    @Test
    fun `urls lose path, query and userinfo`() {
        assertThat(Redactor.redactUrl("https://user:pw@example.com/reset?token=abc#x"))
            .isEqualTo("https://example.com/\u2026")
        assertThat(Redactor.redactUrl("https://example.com")).isEqualTo("https://example.com")
        assertThat(Redactor.redactUrl("not a url")).isEqualTo(Redactor.REDACTED)
    }

    @Test
    fun `clip truncates and strips control chars`() {
        val long = "a".repeat(300) + "\n\u0007"
        val clipped = Redactor.clipForLog(long)
        assertThat(clipped).startsWith("a".repeat(200))
        assertThat(clipped).endsWith("\u2026(302)")
        assertThat(clipped).doesNotContain("\n")
    }
}
