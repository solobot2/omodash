package win.solobot.omodash.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** A card's settings stay in the Activity window, preserving OEM system-bar state and UI scale. */
@Composable
internal fun WidgetOverlay(anchor: IntRect, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val reveal by animateFloatAsState(if (appeared) 1f else 0f, tween(140), label = "widgetMenuReveal")
    Box(Modifier.fillMaxSize().zIndex(30f)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        Layout(content = { Box(Modifier.graphicsLayer {
            alpha = reveal; scaleX = .96f + .04f * reveal; scaleY = scaleX
        }.semantics { contentDescription = "Widget settings" }) { content() } },
            modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val inset = minOf(12.dp.roundToPx(), constraints.maxWidth / 2, constraints.maxHeight / 2)
            val panel = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxWidth = (constraints.maxWidth - inset * 2).coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - inset * 2).coerceAtLeast(0)))
            val x = (anchor.left + (anchor.width - panel.width) / 2)
                .coerceIn(inset, (constraints.maxWidth - panel.width - inset).coerceAtLeast(inset))
            val y = (anchor.top + (anchor.height - panel.height) / 2)
                .coerceIn(inset, (constraints.maxHeight - panel.height - inset).coerceAtLeast(inset))
            layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
        }
    }
}
