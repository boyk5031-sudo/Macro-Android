package com.macroandroid.core.ui.theme

import androidx.compose.ui.graphics.Color

// Brand seed: deep teal. Static schemes are used on API < 31 or when dynamic colour is disabled.
internal val md_light_primary = Color(0xFF00696F)
internal val md_light_onPrimary = Color(0xFFFFFFFF)
internal val md_light_primaryContainer = Color(0xFF6FF6FF)
internal val md_light_onPrimaryContainer = Color(0xFF002022)
internal val md_light_secondary = Color(0xFF4A6365)
internal val md_light_onSecondary = Color(0xFFFFFFFF)
internal val md_light_secondaryContainer = Color(0xFFCCE8EA)
internal val md_light_onSecondaryContainer = Color(0xFF051F21)
internal val md_light_tertiary = Color(0xFF4F5F7D)
internal val md_light_onTertiary = Color(0xFFFFFFFF)
internal val md_light_tertiaryContainer = Color(0xFFD6E3FF)
internal val md_light_onTertiaryContainer = Color(0xFF0A1B36)
internal val md_light_error = Color(0xFFBA1A1A)
internal val md_light_onError = Color(0xFFFFFFFF)
internal val md_light_errorContainer = Color(0xFFFFDAD6)
internal val md_light_onErrorContainer = Color(0xFF410002)
internal val md_light_background = Color(0xFFFAFDFC)
internal val md_light_onBackground = Color(0xFF191C1D)
internal val md_light_surface = Color(0xFFFAFDFC)
internal val md_light_onSurface = Color(0xFF191C1D)
internal val md_light_surfaceVariant = Color(0xFFDAE4E5)
internal val md_light_onSurfaceVariant = Color(0xFF3F4849)
internal val md_light_outline = Color(0xFF6F797A)

internal val md_dark_primary = Color(0xFF4CD9E2)
internal val md_dark_onPrimary = Color(0xFF00363A)
internal val md_dark_primaryContainer = Color(0xFF004F54)
internal val md_dark_onPrimaryContainer = Color(0xFF6FF6FF)
internal val md_dark_secondary = Color(0xFFB1CBCE)
internal val md_dark_onSecondary = Color(0xFF1B3437)
internal val md_dark_secondaryContainer = Color(0xFF324B4D)
internal val md_dark_onSecondaryContainer = Color(0xFFCCE8EA)
internal val md_dark_tertiary = Color(0xFFB6C7EA)
internal val md_dark_onTertiary = Color(0xFF20314C)
internal val md_dark_tertiaryContainer = Color(0xFF374764)
internal val md_dark_onTertiaryContainer = Color(0xFFD6E3FF)
internal val md_dark_error = Color(0xFFFFB4AB)
internal val md_dark_onError = Color(0xFF690005)
internal val md_dark_errorContainer = Color(0xFF93000A)
internal val md_dark_onErrorContainer = Color(0xFFFFDAD6)
internal val md_dark_background = Color(0xFF191C1D)
internal val md_dark_onBackground = Color(0xFFE0E3E3)
internal val md_dark_surface = Color(0xFF191C1D)
internal val md_dark_onSurface = Color(0xFFE0E3E3)
internal val md_dark_surfaceVariant = Color(0xFF3F4849)
internal val md_dark_onSurfaceVariant = Color(0xFFBEC8C9)
internal val md_dark_outline = Color(0xFF899393)

/** Semantic colours that M3 does not provide (execution states). */
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
    val neutral: Color,
)

internal val LightStatusColors = StatusColors(
    success = Color(0xFF2E7D32), onSuccess = Color.White,
    warning = Color(0xFFB26A00), onWarning = Color.White,
    info = Color(0xFF1565C0), onInfo = Color.White,
    neutral = Color(0xFF6F797A),
)

internal val DarkStatusColors = StatusColors(
    success = Color(0xFF81C784), onSuccess = Color(0xFF003910),
    warning = Color(0xFFFFB74D), onWarning = Color(0xFF3F2400),
    info = Color(0xFF90CAF9), onInfo = Color(0xFF002F5B),
    neutral = Color(0xFF899393),
)
