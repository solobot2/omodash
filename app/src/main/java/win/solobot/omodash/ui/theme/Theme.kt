package win.solobot.omodash.ui.theme

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
import win.solobot.omodash.data.AppFont

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
fun OmodashTheme(
    fontBold: Boolean = false,
    textScale: Float = 1.0f,
    appFont: AppFont = AppFont.SYSTEM,
    isDayMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val p = if (isDayMode) DayDashboard else NightDashboard
    val scheme = if (isDayMode) lightColorScheme(
        primary = p.navAccent, onPrimary = Color.White,
        secondary = p.hvacAccent, onSecondary = Color.White,
        background = p.backgroundBottom, onBackground = p.text,
        surface = p.cardTop, onSurface = p.text,
        surfaceVariant = p.panelBottom, onSurfaceVariant = p.muted,
        outline = p.edgeBottom
    ) else darkColorScheme(
        primary = p.navAccent, onPrimary = Color(0xFF032219),
        secondary = p.hvacAccent, onSecondary = Color(0xFF2C2007),
        background = p.backgroundBottom, onBackground = p.text,
        surface = p.cardTop, onSurface = p.text,
        surfaceVariant = p.panelBottom, onSurfaceVariant = p.muted,
        outline = p.edgeBottom
    )
    CompositionLocalProvider(LocalDayMode provides isDayMode, LocalDashboardPalette provides p) {
        MaterialTheme(colorScheme = scheme,
            typography = launcherTypography(fontBold, textScale, appFont.toFontFamily())) {
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}
