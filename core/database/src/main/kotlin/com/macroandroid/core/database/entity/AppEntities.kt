package com.macroandroid.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "app_favorites")
data class AppFavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Metadata of an APK the user imported through SAF. The file itself stays where the user keeps it. */
@Entity(
    tableName = "imported_apks",
    indices = [Index("sha256"), Index("package_name"), Index(value = ["uri"], unique = true)],
)
data class ImportedApkEntity(
    @PrimaryKey val id: String,
    val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "version_name") val versionName: String?,
    @ColumnInfo(name = "version_code") val versionCode: Long?,
    @ColumnInfo(name = "min_sdk") val minSdk: Int?,
    @ColumnInfo(name = "target_sdk") val targetSdk: Int?,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "signer_sha256") val signerSha256: String?,
    @ColumnInfo(name = "permissions_json") val permissionsJson: String,
    @ColumnInfo(name = "is_split") val isSplit: Boolean,
    @ColumnInfo(name = "installed_version_code") val installedVersionCode: Long?,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "grant_valid") val grantValid: Boolean = true,
    val notes: String = "",
)
