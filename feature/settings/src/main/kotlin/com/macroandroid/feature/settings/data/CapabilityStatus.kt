package com.macroandroid.feature.settings.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutManagerCompat
import com.macroandroid.core.common.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads device capability state for the Permission center (FR-PRM-1/3) and opens the matching system screens. */
@Singleton
class CapabilityStatus @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {
    val notificationsEnabled: Boolean get() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val notificationsNeedRuntimePermission: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val ignoringBatteryOptimizations: Boolean
        get() = context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true

    val pinShortcutsSupported: Boolean get() = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Android 13+ blocks enabling accessibility services of side-loaded apps ("Restricted setting") until the user
     * allows it from App info. Play-installed builds are not affected; we can only tell by the installer package.
     */
    val restrictedSettingsMayApply: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
            return installer !in TRUSTED_INSTALLERS
        }

    val versionName: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    val versionCode: Long
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)

    fun openNotificationSettings() = launch(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openAccessibilitySettings() = launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Opens the list, never the direct request dialog (Play policy on REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    fun openBatteryOptimizationList() = launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun openAppInfo() = launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            logger.w(TAG, "no activity for ${intent.action}", e)
            false
        }
    }

    fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val TAG = "Capabilities"
        val TRUSTED_INSTALLERS = setOf("com.android.vending")
    }
}
