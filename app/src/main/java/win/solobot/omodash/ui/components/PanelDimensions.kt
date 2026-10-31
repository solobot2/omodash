package win.solobot.omodash.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** One geometry for both panels, independent of text size and font metrics. */
internal data class PanelDimensions(val controlScale: Float = 1f) {
    val thickness = 78.dp
    val buttonSize = 48.dp * controlScale
    val iconSize = 30.dp * controlScale
    val gap = 6.dp * controlScale
    val padding = 12.dp * controlScale
    val dividerPadding = 5.dp * controlScale
    val dividerThickness = 2.dp * controlScale
}

internal val LocalPanelDimensions = staticCompositionLocalOf { PanelDimensions() }

@Composable
fun ProvidePanelDimensions(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Seven sidebar buttons, eight gaps, padding and divider need 420 dp.
        // When height is tight, both panels use the same smaller controls.
        // Resolve pixels with the current density explicitly. The scope's dp
        // bounds can retain the previous density when pixel constraints stay equal.
        val height = with(LocalDensity.current) { constraints.maxHeight.toDp() }
        val scale = ((height - 78.dp) / 420.dp).coerceIn(.1f, 1f)
        CompositionLocalProvider(LocalPanelDimensions provides PanelDimensions(scale)) { content() }
    }
}
