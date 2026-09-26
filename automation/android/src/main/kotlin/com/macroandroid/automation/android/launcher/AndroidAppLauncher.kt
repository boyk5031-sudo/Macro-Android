package com.macroandroid.automation.android.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.macroandroid.automation.android.accessibility.AccessibilityServiceRegistry
import com.macroandroid.automation.android.gate.ForegroundGate
import com.macroandroid.automation.port.AppLauncher
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Intent-based launching. Starting an activity from the background is refused by the OS on 10+;
 * we check [ForegroundGate] first so the failure is a clear `FOREGROUND_REQUIRED` instead of a
 * silently dropped intent.
 */
@Singleton
class AndroidAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
    private val foregroundGate: ForegroundGate,
    private val registry: AccessibilityServiceRegistry,
) : AppLauncher {

    private val pm: PackageManager get() = context.packageManager

    override suspend fun isInstalled(packageName: String): Boolean = withContext(dispatchers.io) {
        runCatching { pm.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)
    }

    override suspend fun launch(packageName: String): AppResult<Unit> = withContext(dispatchers.main) {
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return@withContext AppResult.err(ErrorCode.NO_ACTIVITY_FOR_INTENT, packageName)
        if (!foregroundGate.mayStartActivity()) return@withContext AppResult.err(ErrorCode.FOREGROUND_REQUIRED, packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
            AppResult.ok(Unit)
        } catch (e: ActivityNotFoundException) {
            AppResult.err(ErrorCode.APP_LAUNCH_FAILED, packageName, e)
        } catch (e: SecurityException) {
            AppResult.err(ErrorCode.APP_LAUNCH_FAILED, packageName, e)
        }
    }

    override suspend fun openUrl(url: String, preferPackage: String?): AppResult<Unit> = withContext(dispatchers.main) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return@withContext AppResult.err(ErrorCode.URL_SCHEME_NOT_ALLOWED)
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return@withContext AppResult.err(ErrorCode.URL_SCHEME_NOT_ALLOWED, uri.scheme)
        if (!foregroundGate.mayStartActivity()) return@withContext AppResult.err(ErrorCode.FOREGROUND_REQUIRED)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (preferPackage != null && isInstalled(preferPackage)) intent.setPackage(preferPackage)
        try {
            context.startActivity(intent)
            AppResult.ok(Unit)
        } catch (e: ActivityNotFoundException) {
            if (preferPackage != null) {
                // Fall back to any handler.
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return@withContext AppResult.ok(Unit)
                } catch (e2: ActivityNotFoundException) {
                    return@withContext AppResult.err(ErrorCode.NO_ACTIVITY_FOR_INTENT, uri.scheme, e2)
                }
            }
            AppResult.err(ErrorCode.NO_ACTIVITY_FOR_INTENT, uri.scheme, e)
        }
    }

    override suspend fun awaitWindow(packageName: String, timeout: Duration): Boolean {
        // Without the accessibility service we cannot observe the foreground window; assume the launch worked.
        if (!registry.isConnected) {
            delay(timeout.coerceAtMost(NO_A11Y_GRACE))
            return true
        }
        return withTimeoutOrNull(timeout) {
            while (activePackage() != packageName) delay(POLL)
            true
        } ?: false
    }

    private suspend fun activePackage(): String? =
        withContext(dispatchers.main) { registry.service.value?.rootInActiveWindow?.packageName?.toString() }

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https", "mailto", "tel", "geo", "content")
        val POLL = 200.milliseconds
        val NO_A11Y_GRACE = 1500.milliseconds
    }
}
