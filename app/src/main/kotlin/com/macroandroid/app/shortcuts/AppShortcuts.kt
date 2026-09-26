package com.macroandroid.app.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.macroandroid.app.R
import com.macroandroid.app.ui.IntentRoutes
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.contract.ShortcutsContract
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppError
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.MacroRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-APP-5 / FR-MAC-9: pinned and dynamic shortcuts. App shortcuts reuse the launcher intent of the target package
 * (no package-visibility escalation); macro shortcuts open MainActivity with `macroandroid://macro/{id}/run`.
 */
@Singleton
class AppShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val macros: MacroRepository,
    private val audit: AuditContract,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : ShortcutsContract {

    override val isPinSupported: Boolean get() = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    override suspend fun pinApp(packageName: String, label: String): AppResult<Unit> = withContext(dispatchers.io) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext AppResult.err(AppError(ErrorCode.APP_LAUNCH_FAILED, packageName))
        val icon = try {
            IconCompat.createWithBitmap(context.packageManager.getApplicationIcon(packageName).toBitmapCompat())
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext AppResult.err(AppError(ErrorCode.APP_NOT_INSTALLED, packageName))
        }
        val info = ShortcutInfoCompat.Builder(context, "app:$packageName")
            .setShortLabel(label.take(SHORT_LABEL_MAX))
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(launch.setAction(Intent.ACTION_MAIN))
            .build()
        requestPin(info, "app", packageName)
    }

    override suspend fun pinMacro(macroId: String, name: String): AppResult<Unit> = withContext(dispatchers.io) {
        requestPin(macroShortcut(macroId, name), "macro", macroId)
    }

    /** Dynamic shortcuts for the 4 most recently run enabled macros (launcher long-press). */
    override suspend fun publishRecentMacros() = withContext(dispatchers.io) {
        val recent = macros.observeSummaries().first()
            .filter { it.enabled && it.lastRunAt != null }
            .sortedByDescending { it.lastRunAt }
            .take(MAX_DYNAMIC)
        val list = recent.map { macroShortcut(it.id.value, it.name) }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, list)
        } catch (e: IllegalArgumentException) {
            logger.w(TAG, "dynamic shortcuts rejected", e)
        } catch (e: IllegalStateException) {
            logger.w(TAG, "dynamic shortcuts unavailable", e)
        }
    }

    private fun macroShortcut(macroId: String, name: String): ShortcutInfoCompat {
        val intent = Intent(Intent.ACTION_VIEW, IntentRoutes.runMacroUri(macroId))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return ShortcutInfoCompat.Builder(context, "macro:$macroId")
            .setShortLabel(name.take(SHORT_LABEL_MAX))
            .setLongLabel(name)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_macro))
            .setIntent(intent)
            .build()
    }

    private suspend fun requestPin(info: ShortcutInfoCompat, kind: String, subject: String): AppResult<Unit> {
        if (!isPinSupported) return AppResult.err(AppError(ErrorCode.SHORTCUT_PIN_UNSUPPORTED))
        val ok = try {
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        } catch (e: IllegalStateException) {
            logger.w(TAG, "pin failed", e)
            false
        }
        return if (ok) {
            audit.record(AuditKind.SHORTCUT_PINNED.name, subject, kind)
            AppResult.ok(Unit)
        } else {
            AppResult.err(AppError(ErrorCode.SHORTCUT_PIN_UNSUPPORTED))
        }
    }

    private companion object {
        const val TAG = "Shortcuts"
        const val SHORT_LABEL_MAX = 10
        const val MAX_DYNAMIC = 4
    }
}

private fun Drawable.toBitmapCompat(): Bitmap {
    val size = ICON_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap
}

private const val ICON_PX = 192
