package com.macroandroid.feature.apps.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.feature.apps.domain.InstalledApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** PackageManager access, all off the main thread. */
@Singleton
class InstalledAppsDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) {
    private val pm: PackageManager get() = context.packageManager
    private val iconCache = LruCache<String, Bitmap>(ICON_CACHE_SIZE)

    /** Every package with a launcher activity visible under our `<queries>` (FR-APP-1). */
    suspend fun loadLaunchable(): List<InstalledApp> = withContext(dispatchers.io) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        resolved.map { it.activityInfo.packageName }.distinct().mapNotNull { pkg -> loadBlocking(pkg) }
    }

    suspend fun load(packageName: String): InstalledApp? = withContext(dispatchers.io) { loadBlocking(packageName) }

    /** Must run on [AppDispatchers.io]. */
    @Suppress("ReturnCount")
    private fun loadBlocking(packageName: String): InstalledApp? {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val app = info.applicationInfo ?: return null
        val flags = app.flags
        val isSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0 && flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        return InstalledApp(
            packageName = packageName,
            label = app.loadLabel(pm).toString(),
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            firstInstalledAt = Instant.fromEpochMilliseconds(info.firstInstallTime),
            lastUpdatedAt = Instant.fromEpochMilliseconds(info.lastUpdateTime),
            isSystem = isSystem,
            isEnabled = app.enabled,
        )
    }

    suspend fun icon(packageName: String, sizePx: Int): Bitmap? = withContext(dispatchers.io) {
        iconCache.get(packageName)?.let { return@withContext it }
        val drawable = try {
            pm.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext null
        }
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        } else {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
            }
        }
        iconCache.put(packageName, bitmap)
        bitmap
    }

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    /** Emits on package add/remove/change/replace while collected (FR-APP-1 "updates within 2 s"). */
    fun packageChanges(): Flow<String?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.data?.schemeSpecificPart)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }.conflate()

    companion object {
        private const val ICON_CACHE_SIZE = 200

        fun foldDiacritics(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()

        private val DIACRITICS = Regex("\\p{Mn}+")
    }
}
