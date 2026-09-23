package com.macroandroid.core.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class Sha256Test {
    @Test
    fun `known vectors`() = runTest {
        assertThat(Sha256.of(ByteArray(0))).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(Sha256.of("abc".toByteArray())).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val progress = ArrayList<Long>()
        val streamed = Sha256.of("abc".byteInputStream()) { progress += it }
        assertThat(streamed).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(progress).containsExactly(3L)
    }
}
