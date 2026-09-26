package com.macroandroid.feature.apkimport.domain

import com.macroandroid.core.common.error.ErrorCode
import kotlin.time.Instant

enum class ApkStatus { READY, INVALID, UNAVAILABLE }

/** FR-APK-4. `NOT_VISIBLE` is shown as "Unknown", never "Not installed". */
enum class InstalledState { NOT_VISIBLE, INSTALLED_SAME_VERSION, INSTALLED_OLDER, INSTALLED_NEWER, INSTALLED_DIFFERENT_SIGNATURE }

data class ImportedApk(
    val id: String,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val label: String?,
    val signerSha256: String?,
    val permissions: List<String>,
    val isSplit: Boolean,
    val installedVersionCode: Long?,
    val importedAt: Instant,
    val grantValid: Boolean,
    val notes: String,
    val status: ApkStatus,
    val errorCode: ErrorCode?,
    val localCopyPath: String?,
    /** Another READY record with the same package + versionCode + signer but a different SHA-256 (FR-APK-3). */
    val semanticDuplicateOf: String? = null,
) {
    val title: String get() = label ?: displayName
}

/** Outcome of importing one URI. */
sealed interface ApkImportOutcome {
    data class Imported(val apk: ImportedApk, val semanticDuplicate: Boolean) : ApkImportOutcome
    data class Rejected(val displayName: String, val code: ErrorCode, val existingId: String? = null) : ApkImportOutcome
    data class Invalid(val apk: ImportedApk) : ApkImportOutcome
}

data class ApkImportProgress(val done: Int, val total: Int, val current: String?)
