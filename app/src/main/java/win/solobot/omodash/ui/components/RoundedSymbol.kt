package win.solobot.omodash.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import win.solobot.omodash.R

/** Official Google Material Symbols, Rounded / 24. */
@Composable
fun RoundedSymbol(@DrawableRes icon: Int, modifier: Modifier = Modifier, description: String? = null,
    tint: Color = LocalContentColor.current) {
    Icon(painterResource(icon), description, modifier, tint)
}
