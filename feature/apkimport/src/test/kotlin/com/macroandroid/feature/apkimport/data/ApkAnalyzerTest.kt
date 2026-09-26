package com.macroandroid.feature.apkimport.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ApkAnalyzerTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val analyzer = ApkAnalyzer(context, dispatcherRule.dispatchers)

    private fun register(uri: Uri, bytes: ByteArray) {
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    @Test
    fun `rejects files that are not zip archives`() = runTest {
        val uri = Uri.parse("content://test/not-apk")
        register(uri, "hello world".toByteArray())
        val r = analyzer.readFile(uri, 1024)
        assertThat((r as AppResult.Err).error.code).isEqualTo(ErrorCode.NOT_AN_APK)
    }

    @Test
    fun `computes sha256 of a zip and deletes nothing from the source`() = runTest {
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("AndroidManifest.xml"))
                z.write(ByteArray(64) { it.toByte() })
                z.closeEntry()
            }
        }.toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
        val uri = Uri.parse("content://test/app.apk")
        register(uri, zip)

        val r = analyzer.readFile(uri, 1024 * 1024)

        val facts = (r as AppResult.Ok).value
        assertThat(facts.sha256).isEqualTo(expected)
        assertThat(facts.sizeBytes).isEqualTo(zip.size.toLong())
        assertThat(facts.cachedCopy.exists()).isTrue()
        facts.cachedCopy.delete()
    }

    @Test
    fun `streams larger than the limit are rejected without keeping a copy`() = runTest {
        val uri = Uri.parse("content://test/big.apk")
        register(uri, byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(2048))
        val r = analyzer.readFile(uri, 1024)
        assertThat((r as AppResult.Err).error.code).isEqualTo(ErrorCode.FILE_TOO_LARGE)
        assertThat(java.io.File(context.cacheDir, "apk-import").listFiles().orEmpty()).isEmpty()
    }
}
