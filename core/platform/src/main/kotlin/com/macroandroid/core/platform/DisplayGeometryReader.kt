package com.macroandroid.core.platform

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.macroandroid.core.common.display.DisplayGeometry
import com.macroandroid.core.common.display.DisplayInsets
import com.macroandroid.core.common.display.DisplayOrientation
import com.macroandroid.core.common.display.DisplayRect

/**
 * Measures the FULL display (including system bars and cutouts) the given context is attached to.
 * Works for activities and for the accessibility service (both are display-associated contexts).
 */
object DisplayGeometryReader {

    fun read(context: Context): DisplayGeometry? {
        val wm = context.getSystemService<WindowManager>() ?: return null
        val densityDpi = context.resources.configuration.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readModern(wm, densityDpi)
        } else {
            readLegacy(wm, densityDpi)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readModern(wm: WindowManager, densityDpi: Int): DisplayGeometry? {
        val bounds: Rect = wm.maximumWindowMetrics.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val insets = runCatching { wm.currentWindowMetrics.windowInsets }.getOrNull()
        val bars = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val cutouts = insets?.displayCutout?.boundingRects.orEmpty().map { DisplayRect(it.left, it.top, it.right, it.bottom) }
        return DisplayGeometry(
            widthPx = bounds.width(),
            heightPx = bounds.height(),
            densityDpi = densityDpi,
            orientation = DisplayOrientation.of(bounds.width(), bounds.height()),
            systemBars = if (bars == null) DisplayInsets() else DisplayInsets(bars.left, bars.top, bars.right, bars.bottom),
            cutouts = cutouts,
        )
    }

    @Suppress("DEPRECATION") // pre-R: Display.getRealMetrics is the only full-display source
    private fun readLegacy(wm: WindowManager, densityDpi: Int): DisplayGeometry? {
        val metrics = DisplayMetrics()
        val display = wm.defaultDisplay ?: return null
        display.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        return DisplayGeometry(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = densityDpi,
            orientation = DisplayOrientation.of(metrics.widthPixels, metrics.heightPixels),
        )
    }
}
