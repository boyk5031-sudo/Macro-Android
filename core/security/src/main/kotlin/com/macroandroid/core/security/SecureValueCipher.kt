package com.macroandroid.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Ciphertext plus the IV it was produced with. Stored as two BLOB columns. */
data class EncryptedValue(val iv: ByteArray, val ciphertext: ByteArray, val keyAlias: String) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedValue && iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext) && keyAlias == other.keyAlias

    override fun hashCode(): Int = 31 * (31 * iv.contentHashCode() + ciphertext.contentHashCode()) + keyAlias.hashCode()
}

/** Encrypts/decrypts sensitive macro parameters with an Android Keystore AES-256-GCM key (ADR-0006). */
interface SecureValueCipher {
    /**
     * @param associatedData binds the ciphertext to its location (`"$macroId/$stepId/$param"`), so a row
     * copied to another step fails to decrypt.
     */
    fun encrypt(plaintext: String, associatedData: String): AppResult<EncryptedValue>
    fun decrypt(value: EncryptedValue, associatedData: String): AppResult<String>

    /** True if the key exists and a round-trip works; used by the diagnostics screen. */
    fun selfTest(): AppResult<Unit>
}

@Singleton
class KeystoreSecureValueCipher @Inject constructor() : SecureValueCipher {

    private val lock = Any()

    override fun encrypt(plaintext: String, associatedData: String): AppResult<EncryptedValue> = guard {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        EncryptedValue(cipher.iv, ct, KEY_ALIAS)
    }

    override fun decrypt(value: EncryptedValue, associatedData: String): AppResult<String> = guard {
        if (value.keyAlias != KEY_ALIAS) throw UnrecoverableKeyException("unknown alias ${value.keyAlias}")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, value.iv))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(value.ciphertext), Charsets.UTF_8)
    }

    override fun selfTest(): AppResult<Unit> {
        val probe = "probe-${System.nanoTime()}"
        return when (val enc = encrypt(probe, "selftest")) {
            is AppResult.Err -> enc
            is AppResult.Ok -> when (val dec = decrypt(enc.value, "selftest")) {
                is AppResult.Err -> dec
                is AppResult.Ok -> if (dec.value == probe) AppResult.ok(Unit) else AppResult.err(ErrorCode.KEYSTORE_UNAVAILABLE, "mismatch")
            }
        }
    }

    private fun key(): SecretKey = synchronized(lock) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: generate()
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(false)
                    if (strongBox) setIsStrongBoxBacked(true)
                }
            }
            .build()
        return try {
            generator.init(spec(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P))
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private inline fun <T> guard(block: () -> T): AppResult<T> = try {
        AppResult.ok(block())
    } catch (e: KeyPermanentlyInvalidatedException) {
        AppResult.Err(AppError(ErrorCode.KEY_INVALIDATED, cause = e))
    } catch (e: UnrecoverableKeyException) {
        AppResult.Err(AppError(ErrorCode.KEY_INVALIDATED, cause = e))
    } catch (e: javax.crypto.AEADBadTagException) {
        AppResult.Err(AppError(ErrorCode.SECURE_VALUE_UNAVAILABLE, "AEAD tag mismatch", e))
    } catch (e: GeneralSecurityException) {
        AppResult.Err(AppError(ErrorCode.KEYSTORE_UNAVAILABLE, e.javaClass.simpleName, e))
    } catch (e: IllegalStateException) {
        AppResult.Err(AppError(ErrorCode.KEYSTORE_UNAVAILABLE, e.javaClass.simpleName, e))
    }

    companion object {
        const val KEY_ALIAS = "macro.secure.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
