package com.macroandroid.feature.apkimport.data

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.pm.PackageInfoCompat
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Parsed facts about an APK file (FR-APK-2 step 5). */
data class ApkMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int?,
    val label: String?,
    val signerSha256: String?,
    val permissions: List<String>,
    val isSplit: Boolean,
)

data class ApkFileFacts(val displayName: String, val sizeBytes: Long, val sha256: String, val cachedCopy: File)

/**
 * Reads an APK through SAF and extracts metadata with `PackageManager.getPackageArchiveInfo`.
 * Never installs anything (ADR-0007); the only file written is a temporary copy in `cacheDir`.
 */
@Singleton
class ApkAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Steps 1–3 of FR-APK-2: size check, streamed copy + SHA-256, ZIP magic. */
    suspend fun readFile(uri: Uri, maxBytes: Long): AppResult<ApkFileFacts> = withContext(dispatchers.io) {
        val (name, size) = queryNameAndSize(uri)
        if (size != null && size > maxBytes) return@withContext AppResult.err(ErrorCode.FILE_TOO_LARGE, "$size > $maxBytes")
        val dir = File(context.cacheDir, "apk-import").apply { mkdirs() }
        val target = File(dir, "${System.nanoTime()}.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(ZIP_MAGIC.size)
            var headerRead = 0
            var total = 0L
            val input = resolver.openInputStream(uri) ?: return@withContext AppResult.err(ErrorCode.FILE_UNREADABLE, "openInputStream=null")
            input.use { stream ->
                target.outputStream().buffered().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (headerRead < header.size) {
                            val take = minOf(header.size - headerRead, n)
                            System.arraycopy(buf, 0, header, headerRead, take)
                            headerRead += take
                        }
                        total += n
                        if (total > maxBytes) {
                            return@withContext AppResult.err(ErrorCode.FILE_TOO_LARGE, "stream exceeded $maxBytes").also { target.delete() }
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            if (headerRead < header.size || !header.contentEquals(ZIP_MAGIC)) {
                target.delete()
                return@withContext AppResult.err(ErrorCode.NOT_AN_APK, "bad magic")
            }
            AppResult.ok(ApkFileFacts(name, total, digest.digest().toHex(), target))
        } catch (e: SecurityException) {
            target.delete()
            AppResult.err(ErrorCode.URI_PERMISSION_REVOKED, e.message)
        } catch (e: FileNotFoundException) {
            target.delete()
            AppResult.err(ErrorCode.URI_PERMISSION_REVOKED, e.message)
        } catch (e: IOException) {
            target.delete()
            AppResult.err(ErrorCode.FILE_UNREADABLE, e.message, e)
        }
    }

    /** Steps 4–5 of FR-APK-2. */
    suspend fun parse(file: File): AppResult<ApkMetadata> = withContext(dispatchers.io) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, flags)
            }
        } catch (e: RuntimeException) {
            return@withContext AppResult.err(ErrorCode.APK_PARSE_FAILED, e.message, e)
        } ?: return@withContext AppResult.err(ErrorCode.APK_PARSE_FAILED, "getPackageArchiveInfo=null")
        val app = info.applicationInfo
        app?.sourceDir = file.absolutePath
        app?.publicSourceDir = file.absolutePath
        val label = try {
            app?.loadLabel(pm)?.toString()
        } catch (_: RuntimeException) {
            null
        }
        val signers: Array<Signature>? = info.signingInfo?.let { si ->
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        }
        val signerDigest = signers?.firstOrNull()?.toByteArray()?.let { MessageDigest.getInstance("SHA-256").digest(it).toHex() }
        AppResult.ok(
            ApkMetadata(
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                minSdk = app?.minSdkVersion,
                targetSdk = app?.targetSdkVersion,
                label = label,
                signerSha256 = signerDigest,
                permissions = info.requestedPermissions?.toList().orEmpty(),
                isSplit = info.splitNames?.isNotEmpty() == true || app?.splitSourceDirs?.isNotEmpty() == true,
            ),
        )
    }

    /** Installed-state facts for FR-APK-4. Returns null when the package is not visible under `<queries>`. */
    fun installed(packageName: String): Pair<Long, String?>? {
        val pm = context.packageManager
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val signer = info.signingInfo?.let { si ->
                (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory).firstOrNull()
            }?.toByteArray()?.let { MessageDigest.getInstance("SHA-256").digest(it).toHex() }
            PackageInfoCompat.getLongVersionCode(info) to signer
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun hasPersistedReadPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long?> {
        var name = uri.lastPathSegment ?: "unknown.apk"
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        } catch (_: SecurityException) {
            // handled by openInputStream below
        } catch (_: IllegalArgumentException) {
            // some providers throw for unsupported projections
        }
        return name to size
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val BUFFER = 64 * 1024
    }
}
