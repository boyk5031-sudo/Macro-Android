package com.macroandroid.feature.apkimport.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.dao.AppDao
import com.macroandroid.core.database.entity.ImportedApkEntity
import com.macroandroid.core.datastore.UserPreferencesRepository
import com.macroandroid.feature.apkimport.domain.ApkImportOutcome
import com.macroandroid.feature.apkimport.domain.ApkImportProgress
import com.macroandroid.feature.apkimport.domain.ApkStatus
import com.macroandroid.feature.apkimport.domain.ImportedApk
import com.macroandroid.feature.apkimport.domain.InstalledState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Singleton
class ApkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppDao,
    private val analyzer: ApkAnalyzer,
    private val prefs: UserPreferencesRepository,
    private val audit: AuditContract,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val logger: Logger,
) {
    fun observeAll(): Flow<List<ImportedApk>> = dao.observeAll().map { rows -> markSemanticDuplicates(rows.map(::toDomain)) }

    fun observe(id: String): Flow<ImportedApk?> = dao.observe(id).map { it?.let(::toDomain) }

    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * FR-APK-1/2/3: imports up to [MAX_URIS_PER_IMPORT] URIs; the rest are reported as LIMIT_EXCEEDED.
     * [relinkId] re-selects the file for an UNAVAILABLE record (FR-APK-5).
     */
    suspend fun import(
        uris: List<Uri>,
        relinkId: String? = null,
        onProgress: suspend (ApkImportProgress) -> Unit = {},
    ): List<ApkImportOutcome> = withContext(dispatchers.io) {
        val accepted = uris.take(MAX_URIS_PER_IMPORT)
        val outcomes = ArrayList<ApkImportOutcome>(uris.size)
        accepted.forEachIndexed { index, uri ->
            onProgress(ApkImportProgress(index, accepted.size, uri.lastPathSegment))
            outcomes += importOne(uri, relinkId)
        }
        uris.drop(MAX_URIS_PER_IMPORT).forEach {
            outcomes += ApkImportOutcome.Rejected(it.lastPathSegment ?: it.toString(), ErrorCode.LIMIT_EXCEEDED)
        }
        onProgress(ApkImportProgress(accepted.size, accepted.size, null))
        outcomes
    }

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun importOne(uri: Uri, relinkId: String?): ApkImportOutcome {
        val now = clock.now().toEpochMilliseconds()
        takePersistable(uri)
        val maxBytes = MAX_APK_BYTES
        val file = when (val r = analyzer.readFile(uri, maxBytes)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return invalidOrRejected(uri, uri.lastPathSegment ?: "?", r.error.code, now)
        }
        try {
            // FR-APK-3 exact duplicate.
            dao.findBySha256(file.sha256)?.let { existing ->
                if (relinkId != null && existing.id == relinkId) {
                    val relinked = existing.copy(uri = uri.toString(), grantValid = true, status = ApkStatus.READY.name, errorCode = null)
                    dao.upsert(relinked)
                    return ApkImportOutcome.Imported(toDomain(relinked), semanticDuplicate = false)
                }
                return ApkImportOutcome.Rejected(file.displayName, ErrorCode.DUPLICATE_EXACT, existingId = existing.id)
            }
            val existingByUri = dao.findByUri(uri.toString())
            val id = relinkId ?: existingByUri?.id ?: Uuid.random().toString()
            val meta = when (val p = analyzer.parse(file.cachedCopy)) {
                is AppResult.Ok -> p.value
                is AppResult.Err -> {
                    val entity = ImportedApkEntity(
                        id = id, uri = uri.toString(), displayName = file.displayName, sizeBytes = file.sizeBytes, sha256 = file.sha256,
                        packageName = null, versionName = null, versionCode = null, minSdk = null, targetSdk = null, label = null,
                        signerSha256 = null, permissionsJson = "[]", isSplit = false, installedVersionCode = null, importedAt = now,
                        status = ApkStatus.INVALID.name, errorCode = p.error.code.name,
                    )
                    dao.upsert(entity)
                    return ApkImportOutcome.Invalid(toDomain(entity))
                }
            }
            val keepCopy = prefs.current().copyApkOnImport
            val localCopy = if (keepCopy) keepLocalCopy(file.cachedCopy, id) else null
            val installed = analyzer.installed(meta.packageName)
            val entity = ImportedApkEntity(
                id = id,
                uri = uri.toString(),
                displayName = file.displayName,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                packageName = meta.packageName,
                versionName = meta.versionName,
                versionCode = meta.versionCode,
                minSdk = meta.minSdk,
                targetSdk = meta.targetSdk,
                label = meta.label,
                signerSha256 = meta.signerSha256,
                permissionsJson = json.encodeToString(meta.permissions),
                isSplit = meta.isSplit,
                installedVersionCode = installed?.first,
                importedAt = now,
                status = ApkStatus.READY.name,
                errorCode = null,
                localCopyPath = localCopy?.absolutePath,
            )
            dao.upsert(entity)
            val semantic = dao.findSemanticDuplicate(meta.packageName, meta.versionCode, id) != null
            audit.record("APK_IMPORTED", meta.packageName, file.sha256.take(SHA_PREFIX))
            return ApkImportOutcome.Imported(toDomain(entity), semanticDuplicate = semantic)
        } finally {
            file.cachedCopy.delete()
        }
    }

    private suspend fun invalidOrRejected(uri: Uri, name: String, code: ErrorCode, now: Long): ApkImportOutcome {
        // Unreadable / oversized files are still recorded so the user can see and delete them (FR-APK-2).
        if (code == ErrorCode.URI_PERMISSION_REVOKED || code == ErrorCode.FILE_TOO_LARGE || code == ErrorCode.NOT_AN_APK) {
            val existing = dao.findByUri(uri.toString())
            val entity = (existing ?: ImportedApkEntity(
                id = Uuid.random().toString(), uri = uri.toString(), displayName = name, sizeBytes = 0, sha256 = "",
                packageName = null, versionName = null, versionCode = null, minSdk = null, targetSdk = null, label = null,
                signerSha256 = null, permissionsJson = "[]", isSplit = false, installedVersionCode = null, importedAt = now,
            )).copy(
                status = (if (code == ErrorCode.URI_PERMISSION_REVOKED) ApkStatus.UNAVAILABLE else ApkStatus.INVALID).name,
                errorCode = code.name,
                grantValid = code != ErrorCode.URI_PERMISSION_REVOKED,
            )
            dao.upsert(entity)
            return ApkImportOutcome.Invalid(toDomain(entity))
        }
        return ApkImportOutcome.Rejected(name, code)
    }

    /** FR-APK-5: verify the grant before reading; FR-APK-6 "Re-verify checksum". */
    suspend fun reverify(id: String): AppResult<Boolean> = withContext(dispatchers.io) {
        val row = dao.get(id) ?: return@withContext AppResult.err(ErrorCode.NOT_FOUND, id)
        val uri = Uri.parse(row.uri)
        if (!analyzer.hasPersistedReadPermission(uri)) {
            dao.setStatus(id, ApkStatus.UNAVAILABLE.name, ErrorCode.URI_PERMISSION_REVOKED.name)
            dao.setGrantValid(id, false)
            return@withContext AppResult.err(ErrorCode.URI_PERMISSION_REVOKED, id)
        }
        when (val r = analyzer.readFile(uri, MAX_APK_BYTES)) {
            is AppResult.Ok -> {
                r.value.cachedCopy.delete()
                val same = r.value.sha256 == row.sha256
                if (same && row.status != ApkStatus.READY.name && row.packageName != null) {
                    dao.setStatus(id, ApkStatus.READY.name, null)
                    dao.setGrantValid(id, true)
                }
                AppResult.ok(same)
            }
            is AppResult.Err -> {
                if (r.error.code == ErrorCode.URI_PERMISSION_REVOKED) {
                    dao.setStatus(id, ApkStatus.UNAVAILABLE.name, r.error.code.name)
                    dao.setGrantValid(id, false)
                }
                AppResult.err(r.error)
            }
        }
    }

    /** FR-APK-4 recomputed on demand (installed apps change under us). */
    fun installedState(apk: ImportedApk): InstalledState? {
        val pkg = apk.packageName ?: return null
        val (installedCode, installedSigner) = analyzer.installed(pkg) ?: return InstalledState.NOT_VISIBLE
        val ourCode = apk.versionCode ?: return InstalledState.NOT_VISIBLE
        return when {
            apk.signerSha256 != null && installedSigner != null && apk.signerSha256 != installedSigner ->
                InstalledState.INSTALLED_DIFFERENT_SIGNATURE
            installedCode == ourCode -> InstalledState.INSTALLED_SAME_VERSION
            installedCode < ourCode -> InstalledState.INSTALLED_OLDER
            else -> InstalledState.INSTALLED_NEWER
        }
    }

    /** FR-APK-7: never deletes the source file. */
    suspend fun delete(id: String) = withContext(dispatchers.io) {
        val row = dao.get(id) ?: return@withContext
        dao.delete(id)
        row.localCopyPath?.let { File(it).delete() }
        if (dao.countByUri(row.uri) == 0) {
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(row.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                logger.d(TAG, "release grant failed: ${e.message}")
            }
        }
        audit.record("APK_DELETED", row.packageName ?: row.displayName)
    }

    suspend fun setNotes(id: String, notes: String) = withContext(dispatchers.io) { dao.setNotes(id, notes.take(MAX_NOTES)) }

    /** Share intent for the detail screen; explicit read grant per Android 18 guidance. */
    fun shareIntent(apk: ImportedApk): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(apk.uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun takePersistable(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            logger.d(TAG, "takePersistableUriPermission refused: ${e.message}")
        }
    }

    private fun keepLocalCopy(cached: File, id: String): File? = try {
        val dir = File(context.filesDir, "apk-copies").apply { mkdirs() }
        val target = File(dir, "$id.apk")
        cached.copyTo(target, overwrite = true)
    } catch (e: java.io.IOException) {
        logger.w(TAG, "local copy failed", e)
        null
    }

    private fun markSemanticDuplicates(list: List<ImportedApk>): List<ImportedApk> {
        val groups = list.filter { it.status == ApkStatus.READY && it.packageName != null }
            .groupBy { Triple(it.packageName, it.versionCode, it.signerSha256) }
        val dupOf = HashMap<String, String>()
        groups.values.filter { it.size > 1 }.forEach { g ->
            val distinctHashes = g.map { it.sha256 }.distinct()
            if (distinctHashes.size > 1) g.forEach { a -> dupOf[a.id] = g.first { it.id != a.id }.id }
        }
        return if (dupOf.isEmpty()) list else list.map { it.copy(semanticDuplicateOf = dupOf[it.id]) }
    }

    private fun toDomain(e: ImportedApkEntity) = ImportedApk(
        id = e.id,
        uri = e.uri,
        displayName = e.displayName,
        sizeBytes = e.sizeBytes,
        sha256 = e.sha256,
        packageName = e.packageName,
        versionName = e.versionName,
        versionCode = e.versionCode,
        minSdk = e.minSdk,
        targetSdk = e.targetSdk,
        label = e.label,
        signerSha256 = e.signerSha256,
        permissions = runCatching { json.decodeFromString<List<String>>(e.permissionsJson) }.getOrDefault(emptyList()),
        isSplit = e.isSplit,
        installedVersionCode = e.installedVersionCode,
        importedAt = Instant.fromEpochMilliseconds(e.importedAt),
        grantValid = e.grantValid,
        notes = e.notes,
        status = runCatching { ApkStatus.valueOf(e.status) }.getOrDefault(ApkStatus.INVALID),
        errorCode = e.errorCode?.let { c -> runCatching { ErrorCode.valueOf(c) }.getOrNull() },
        localCopyPath = e.localCopyPath,
    )

    companion object {
        const val MAX_URIS_PER_IMPORT = 25
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        private const val MAX_NOTES = 500
        private const val SHA_PREFIX = 12
        private const val TAG = "ApkRepository"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
