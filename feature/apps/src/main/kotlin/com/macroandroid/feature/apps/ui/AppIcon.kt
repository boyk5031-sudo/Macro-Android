package com.macroandroid.feature.apps.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Loads the launcher icon off the main thread through the repository-provided loader. */
@Composable
fun AppIcon(
    packageName: String,
    contentDescription: String?,
    loader: suspend (packageName: String, sizePx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    dimmed: Boolean = false,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, packageName, sizePx) {
        value = loader(packageName, sizePx)
    }
    Box(modifier.size(size).alpha(if (dimmed) DIMMED_ALPHA else 1f)) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = contentDescription, modifier = Modifier.size(size))
        } else {
            Icon(
                Icons.Outlined.Android,
                contentDescription = contentDescription,
                modifier = Modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val DIMMED_ALPHA = 0.45f
