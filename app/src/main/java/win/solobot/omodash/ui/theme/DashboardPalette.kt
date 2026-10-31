package win.solobot.omodash.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Fixed semantic colours shared by panels, controls and widget shells. */
@Immutable
data class DashboardPalette(
    val backgroundTop: Color, val backgroundBottom: Color,
    val panelTop: Color, val panelBottom: Color,
    val cardTop: Color, val cardBottom: Color,
    val edgeTop: Color, val edgeBottom: Color, val shine: Color,
    val text: Color, val muted: Color, val icon: Color,
    val navAccent: Color, val navFillTop: Color, val navFillBottom: Color,
    val hvacAccent: Color, val hvacFillTop: Color, val hvacFillBottom: Color,
    val buttonTop: Color, val buttonBottom: Color, val shadow: Color
)

val NightDashboard = DashboardPalette(
    Color(0xFF0B151E), Color(0xFF03090D),
    Color(0xFF101C25), Color(0xFF050D13),
    Color(0xFF182833), Color(0xFF071017),
    Color(0xFF4B626E), Color(0xFF203640), Color(0x24D6F0FF),
    Color(0xFFF3F7FA), Color(0xFF9AB0C0), Color(0xFFE9F1F7),
    Color(0xFF3CE9B5), Color(0xFF12372F), Color(0xFF08231F),
    Color(0xFFFFD98A), Color(0xFF35332B), Color(0xFF181D1C),
    Color(0xFF192730), Color(0xFF071017), Color(0x70000000)
)

val DayDashboard = DashboardPalette(
    Color(0xFFB4CCE1), Color(0xFFD4E4F0),
    Color(0xFFE2EDF7), Color(0xFFBFD4E6),
    Color(0xFFEAF3FB), Color(0xFFC5DBEF),
    Color(0xEFFFFFFF), Color(0xFFACC6DD), Color(0x9AFFFFFF),
    Color(0xFF071B36), Color(0xFF476482), Color(0xFF10294A),
    Color(0xFF0965F2), Color(0xFFBCDCFF), Color(0xFF9CC6F8),
    Color(0xFF0658D1), Color(0xFFC6E3FF), Color(0xFFA5CEF9),
    Color(0xFFF2F8FC), Color(0xFFD5E5F3), Color(0x25365778)
)

val LocalDashboardPalette = staticCompositionLocalOf { NightDashboard }
