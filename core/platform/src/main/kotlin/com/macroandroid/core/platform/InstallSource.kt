package com.macroandroid.core.platform

import android.content.Context
import android.os.Build

/** Installer facts that change how Android treats the app (restricted settings for side-loads on 13+). */
object InstallSource {
    private val TRUSTED_INSTALLERS = setOf("com.android.vending")

    /**
     * Android 13+ blocks enabling accessibility services of side-loaded apps ("Restricted setting") until the user
     * allows it from App info. Play-installed builds are not affected; we can only tell by the installer package.
     */
    fun restrictedSettingsMayApply(context: Context): Boolean {
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
}
