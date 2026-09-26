package com.macroandroid.core.common.display

import kotlinx.serialization.Serializable

/** Physical orientation of the display as the user sees it; derived from the real display size, not the activity. */
@Serializable
enum class DisplayOrientation {
    PORTRAIT,
    LANDSCAPE,
    ;

    companion object {
        fun of(widthPx: Int, heightPx: Int): DisplayOrientation = if (widthPx > heightPx) LANDSCAPE else PORTRAIT
    }
}

/** Integer rectangle in physical display pixels; `right`/`bottom` are exclusive. */
@Serializable
data class DisplayRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** System bar / cutout insets in physical pixels for the current orientation. */
@Serializable
data class DisplayInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/**
 * Snapshot of the *full* display the app runs on, in physical pixels, including the areas under system bars
 * and cutouts. This is the coordinate space used by Android input injection and by accessibility overlays,
 * so it is the single internal space of the Trigger Area feature (docs/phase-12-trigger-areas.md §4).
 */
@Serializable
data class DisplayGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val orientation: DisplayOrientation = DisplayOrientation.of(widthPx, heightPx),
    val systemBars: DisplayInsets = DisplayInsets(),
    val cutouts: List<DisplayRect> = emptyList(),
) {
    val isValid: Boolean get() = widthPx > 0 && heightPx > 0
    val aspectRatio: Double get() = if (heightPx == 0) 0.0 else widthPx.toDouble() / heightPx.toDouble()
    val bounds: DisplayRect get() = DisplayRect(0, 0, widthPx, heightPx)
}
