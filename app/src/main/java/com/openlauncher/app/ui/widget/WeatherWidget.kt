package com.openlauncher.app.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.WeatherState

@Composable
fun WeatherWidget(
    state: WeatherState?,
    accent: Color,
    metric: Boolean,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subColor     = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    BoxWithConstraints(modifier = modifier) {
        val showIcon = maxHeight >= 92.dp * LocalDensity.current.fontScale + 40.dp
        if (state != null) {
            Column(
                modifier            = Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.Start
            ) {
                if (showIcon) Text(
                    text     = state.conditionIcon,
                    fontSize = 34.sp
                )
                if (showIcon) Spacer(Modifier.height(4.dp))
                Text(
                    text       = state.temperatureDisplay(metric),
                    color      = contentColor,
                    fontSize   = 32.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text          = state.conditionLabel.uppercase(),
                    color         = subColor,
                    fontSize      = 9.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
