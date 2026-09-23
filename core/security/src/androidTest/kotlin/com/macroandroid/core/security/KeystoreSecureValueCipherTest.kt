package com.macroandroid.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecureValueCipherTest {

    private val cipher = KeystoreSecureValueCipher()

    @Test
    fun roundTrip() {
        val enc = (cipher.encrypt("hunter2", "m/s/text") as AppResult.Ok).value
        assertThat(enc.iv).hasLength(12)
        assertThat(String(enc.ciphertext, Charsets.ISO_8859_1)).doesNotContain("hunter2")
        assertThat((cipher.decrypt(enc, "m/s/text") as AppResult.Ok).value).isEqualTo("hunter2")
    }

    @Test
    fun wrongAssociatedDataFails() {
        val enc = (cipher.encrypt("hunter2", "m/s1/text") as AppResult.Ok).value
        val r = cipher.decrypt(enc, "m/s2/text")
        assertThat(r.errorOrNull()?.code).isEqualTo(ErrorCode.SECURE_VALUE_UNAVAILABLE)
    }

    @Test
    fun ivIsRandomPerEncryption() {
        val a = (cipher.encrypt("same", "aad") as AppResult.Ok).value
        val b = (cipher.encrypt("same", "aad") as AppResult.Ok).value
        assertThat(a.iv).isNotEqualTo(b.iv)
        assertThat(a.ciphertext).isNotEqualTo(b.ciphertext)
    }

    @Test
    fun selfTestPasses() {
        assertThat(cipher.selfTest()).isInstanceOf(AppResult.Ok::class.java)
    }
}
