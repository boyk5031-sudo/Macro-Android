package com.macroandroid.core.database

import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.flatMap
import com.macroandroid.core.database.dao.MacroDao
import com.macroandroid.core.database.entity.SecureValueEntity
import com.macroandroid.core.security.EncryptedValue
import com.macroandroid.core.security.SecureValueCipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Stores sensitive macro parameters encrypted with the Keystore key, bound to
 * `macroId/stepId/param` via AEAD associated data (docs/phase-1/04 §7).
 */
@Singleton
class SecureValueStore @Inject constructor(
    private val dao: MacroDao,
    private val cipher: SecureValueCipher,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun put(
        macroId: String,
        stepId: String,
        param: String,
        plaintext: String,
        now: Long,
        existingId: String? = null,
    ): AppResult<String> {
        val id = existingId ?: Uuid.random().toString()
        return cipher.encrypt(plaintext, aad(macroId, stepId, param)).flatMap { enc ->
            dao.upsertSecureValue(
                SecureValueEntity(
                    id = id, macroId = macroId, stepId = stepId, param = param,
                    keyAlias = enc.keyAlias, iv = enc.iv, ciphertext = enc.ciphertext, createdAt = now,
                ),
            )
            AppResult.ok(id)
        }
    }

    suspend fun get(id: String): AppResult<String> {
        val row = dao.secureValue(id) ?: return AppResult.err(ErrorCode.SECURE_VALUE_UNAVAILABLE, "missing $id")
        return cipher.decrypt(EncryptedValue(row.iv, row.ciphertext, row.keyAlias), aad(row.macroId, row.stepId, row.param))
    }

    suspend fun exists(id: String): Boolean = dao.secureValue(id) != null

    suspend fun delete(id: String) = dao.deleteSecureValue(id)

    /** Deletes every secure value of [macroId] that is not referenced any more. */
    suspend fun prune(macroId: String, referencedIds: Collection<String>) {
        if (referencedIds.isEmpty()) {
            dao.secureValueIds(macroId).forEach { dao.deleteSecureValue(it) }
        } else {
            dao.pruneSecureValues(macroId, referencedIds.toList())
        }
    }

    /** Re-binds a value to a new step (copy on macro duplication / import) by decrypting and re-encrypting. */
    suspend fun copy(id: String, newMacroId: String, newStepId: String, now: Long): AppResult<String> {
        val row = dao.secureValue(id) ?: return AppResult.err(ErrorCode.SECURE_VALUE_UNAVAILABLE, "missing $id")
        return get(id).flatMap { plaintext -> put(newMacroId, newStepId, row.param, plaintext, now) }
    }

    private fun aad(macroId: String, stepId: String, param: String) = "$macroId/$stepId/$param"
}
