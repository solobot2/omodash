package com.openlauncher.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.openlauncher.app.data.AppFont

val LocalDayMode = staticCompositionLocalOf { false }

@Composable
fun outlinedControlColors() = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
fun textControlColors() = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
fun filledControlColors(): androidx.compose.material3.ButtonColors {
    val scheme = MaterialTheme.colorScheme
    val high = maxOf(scheme.primary.luminance(), scheme.surface.luminance())
    val low = minOf(scheme.primary.luminance(), scheme.surface.luminance())
    val container = if ((high + 0.05f) / (low + 0.05f) >= 3f) scheme.primary
        else if (scheme.surface.luminance() < 0.5f) Color.White else Color.Black
    val content = if (container.luminance() > 0.179f) Color.Black else Color.White
    return ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
}

@Composable
fun OpenLauncherTheme(
    accent: Color     = AccentWhite,
    background: Color = Black,
    textColor: Color  = Color.White,
    fontBold: Boolean = false,
    textScale: Float  = 1.0f,
    appFont: AppFont  = AppFont.JETBRAINS_MONO,
    isDayMode: Boolean = false,
    useCustomBg: Boolean = false,
    content: @Composable () -> Unit
) {
    // Contrast-aware: the accent is user-chosen and can be any brightness,
    // so a fixed onPrimary (white) goes invisible on light accents
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val colorScheme = if (isDayMode) lightColorScheme(
        primary          = accent,
        onPrimary        = onAccent,
        secondary        = accent.copy(alpha = 0.7f),
        onSecondary      = onAccent,
        tertiary         = accent.copy(alpha = 0.5f),
        background       = if (useCustomBg) background else Color(0xFFEEEEEE),
        surface          = Color(0xFFFFFFFF),
        onBackground     = Color(0xFF111111),
        onSurface        = Color(0xFF111111),
        surfaceVariant   = Color(0xFFF5F5F5),
        onSurfaceVariant = Color(0xFF666666),
        outline          = Color(0xFFCCCCCC)
    ) else darkColorScheme(
        primary          = accent,
        onPrimary        = onAccent,
        secondary        = accent.copy(alpha = 0.7f),
        onSecondary      = onAccent,
        tertiary         = accent.copy(alpha = 0.5f),
        background       = background,
        surface          = CardSurface,
        onBackground     = textColor,
        onSurface        = textColor,
        surfaceVariant   = DimSurface,
        onSurfaceVariant = TextMuted,
        outline          = DividerGray
    )
    CompositionLocalProvider(LocalDayMode provides isDayMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = launcherTypography(fontBold, textScale, appFont.toFontFamily()),
            content = {
                CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
            }
        )
    }
}
