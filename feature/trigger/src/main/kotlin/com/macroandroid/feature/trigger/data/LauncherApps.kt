package com.macroandroid.feature.trigger.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.macroandroid.core.common.coroutines.AppDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

/** Launchable apps for the "bind to game" picker (visible through the existing `<queries>` LAUNCHER filter). */
@Singleton
class LauncherApps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) {
    data class Entry(val packageName: String, val label: String)

    suspend fun list(): List<Entry> = withContext(dispatchers.io) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        activities
            .asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> Entry(pkg, label) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList()
    }
}
