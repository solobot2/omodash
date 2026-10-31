package win.solobot.omodash.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import win.solobot.omodash.ui.theme.LocalDashboardPalette
import win.solobot.omodash.ui.theme.LocalDayMode

enum class DashboardSurface { CARD, NAVIGATION, CLIMATE }

@Composable
fun Modifier.dashboardBackground(): Modifier {
    val p = LocalDashboardPalette.current
    return background(Brush.verticalGradient(listOf(p.backgroundTop, p.backgroundBottom)))
}

/** All chrome is drawn from vectors/gradients in dp; no resolution-specific bitmap borders. */
@Composable
fun Modifier.dashboardSurface(kind: DashboardSurface = DashboardSurface.CARD, highlighted: Boolean = false): Modifier {
    val p = LocalDashboardPalette.current
    val card = kind == DashboardSurface.CARD
    val shape = RoundedCornerShape(if (card) 14.dp else 0.dp)
    val top = if (card) p.cardTop else p.panelTop
    val bottom = if (card) p.cardBottom else p.panelBottom
    return this
        .then(if (card) Modifier.shadow(10.dp, shape, ambientColor = p.shadow, spotColor = p.shadow) else Modifier)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .drawWithCache {
            val wash = Brush.radialGradient(listOf(p.shine.copy(alpha = p.shine.alpha * 0.30f), Color.Transparent),
                center = Offset(size.width * 0.25f, -size.height * 0.45f), radius = size.width.coerceAtLeast(size.height))
            onDrawBehind { drawRect(wash) }
        }
        .then(if (card) Modifier.border(if (highlighted) 1.5.dp else 0.8.dp,
            Brush.verticalGradient(listOf(if (highlighted) p.navAccent else p.edgeTop, p.edgeBottom)), shape)
        else Modifier.drawWithCache {
            onDrawWithContent {
                drawContent()
                if (kind == DashboardSurface.CLIMATE) drawLine(p.edgeTop, Offset.Zero, Offset(size.width, 0f), .8.dp.toPx())
                else {
                    drawLine(p.edgeBottom, Offset.Zero, Offset(0f, size.height), .6.dp.toPx())
                    drawLine(p.edgeBottom, Offset(size.width, 0f), Offset(size.width, size.height), .6.dp.toPx())
                }
            }
        })
}

/** Reusable shell. Content may be any image, vector, text, or their combination. */
@Composable
private fun ChromeButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    climate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val p = LocalDashboardPalette.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val selected = active && enabled
    val controlScale = LocalPanelDimensions.current.controlScale
    val shape = RoundedCornerShape((if (climate) 10.dp else 12.dp) * controlScale)
    val accent = if (climate) p.hvacAccent else p.navAccent
    val top = when {
        selected && climate -> p.hvacFillTop
        selected -> p.navFillTop
        climate -> p.buttonTop
        else -> Color.Transparent
    }
    val bottom = when {
        selected && climate -> p.hvacFillBottom
        selected -> p.navFillBottom
        climate -> p.buttonBottom
        else -> Color.Transparent
    }
    val showFrame = selected || climate
    val iconTint = if (selected && !climate && !LocalDayMode.current) p.text else if (selected) accent else p.icon
    Box(
        modifier
            .then(if (selected) Modifier.shadow(6.dp, shape, ambientColor = accent.copy(alpha = .15f),
                spotColor = accent.copy(alpha = .40f)) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .then(if (showFrame) Modifier.border(0.8.dp,
                Brush.verticalGradient(listOf(if (selected) accent.copy(alpha = 0.65f) else p.edgeTop.copy(alpha = 0.65f),
                    if (selected) accent.copy(alpha = 0.25f) else p.edgeBottom)), shape) else Modifier)
            .drawWithCache {
                val glow = Brush.horizontalGradient(listOf(Color.Transparent, accent, Color.Transparent))
                val sheen = Brush.horizontalGradient(listOf(Color.Transparent, p.shine, Color.Transparent))
                onDrawBehind {
                    if (showFrame) drawLine(sheen, Offset(size.width * 0.14f, 1.dp.toPx()),
                        Offset(size.width * 0.86f, 1.dp.toPx()), 1.dp.toPx())
                    if (selected) {
                        drawLine(glow, Offset(size.width * 0.16f, size.height - 2.dp.toPx()),
                            Offset(size.width * 0.84f, size.height - 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            .semantics { contentDescription = label; role = Role.Button; this.selected = selected; if (!enabled) disabled() }
            .then(if (enabled) Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        // Press raises image/content brightness by 50%; the frame stays unchanged.
        val paint = remember(pressed, enabled) {
            Paint().apply {
                colorFilter = if (!enabled) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                    else if (pressed) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                        1.5f, 0f, 0f, 0f, 0f, 0f, 1.5f, 0f, 0f, 0f,
                        0f, 0f, 1.5f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) else null
                alpha = if (enabled) 1f else 0.30f
            }
        }
        Box(Modifier.drawWithContent {
            // Near-white icons have little RGB headroom. A glyph-local halo
            // makes held +/- and other momentary controls visible in both themes.
            // The surrounding button frame is drawn separately and never changes.
            if (pressed && enabled) {
                val radius = size.minDimension * .75f
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .65f), Color.Transparent),
                    center = center, radius = radius), radius = radius)
            }
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            drawContext.canvas.restore()
        }, contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides iconTint) { content() }
        }
    }
}

@Composable
fun NavButton(label: String, active: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    ChromeButton(label, active, enabled, false, onClick, modifier, content)

@Composable
fun NavButton(label: String, @DrawableRes icon: Int, active: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit, modifier: Modifier = Modifier) =
    NavButton(label, active, enabled, onClick, modifier) {
        RoundedSymbol(icon, Modifier.size(LocalPanelDimensions.current.iconSize))
    }

@Composable
fun HvacButton(label: String, active: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    ChromeButton(label, active, enabled, true, onClick, modifier, content)

@Composable
fun HvacButton(label: String, @DrawableRes icon: Int, active: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit, modifier: Modifier = Modifier) =
    HvacButton(label, active, enabled, onClick, modifier) {
        RoundedSymbol(icon, Modifier.size(LocalPanelDimensions.current.iconSize))
    }

@Composable
fun DashboardVerticalDivider(modifier: Modifier = Modifier) {
    val p = LocalDashboardPalette.current
    Canvas(modifier.width(LocalPanelDimensions.current.dividerThickness).fillMaxHeight(0.60f)) {
        drawLine(Brush.verticalGradient(listOf(Color.Transparent, p.edgeTop, Color.Transparent)),
            Offset(0f, 0f), Offset(0f, size.height), 0.8.dp.toPx())
        drawLine(p.shine, Offset(1.dp.toPx(), size.height * .1f), Offset(1.dp.toPx(), size.height * .9f), .5.dp.toPx())
    }
}

@Composable
fun DashboardHorizontalDivider(modifier: Modifier = Modifier) {
    val p = LocalDashboardPalette.current
    Canvas(modifier.fillMaxWidth().height(LocalPanelDimensions.current.dividerThickness)) {
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, p.edgeTop, Color.Transparent)),
            Offset.Zero, Offset(size.width, 0f), .8.dp.toPx())
    }
}
