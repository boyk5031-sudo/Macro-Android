package com.macroandroid.core.security

import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Streaming SHA-256; cooperative with cancellation so large APKs can be aborted. */
object Sha256 {
    private const val BUFFER = 64 * 1024

    suspend fun of(input: InputStream, onProgress: ((bytesRead: Long) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        var total = 0L
        input.use { stream ->
            while (true) {
                coroutineContext.ensureActive()
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                total += n
                onProgress?.invoke(total)
            }
        }
        return digest.digest().toHex()
    }

    fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
