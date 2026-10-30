package com.openlauncher.app.data

import kotlin.math.abs

data class WidgetSize(val width: Int, val height: Int) {
    val area get() = width * height
    override fun toString() = "$width × $height"
}

/** The allowed pairs also express fixed sizes and coupled dimensions without special UI rules. */
data class WidgetSizeRules(val sizes: List<WidgetSize>) {
    init {
        require(sizes.isNotEmpty() && sizes.distinct().size == sizes.size)
        require(sizes.all { it.width in 1..GRID_COLS && it.height in 1..GRID_ROWS })
    }
    val minWidth get() = sizes.minOf { it.width }
    val maxWidth get() = sizes.maxOf { it.width }
    val minHeight get() = sizes.minOf { it.height }
    val maxHeight get() = sizes.maxOf { it.height }
    val isResizable get() = sizes.size > 1
    val isSquare get() = sizes.all { it.width == it.height }
    fun closest(width: Int, height: Int): WidgetSize = sizes.minWith(
        compareBy<WidgetSize> { abs(it.width.toLong() - width) + abs(it.height.toLong() - height) }
            .thenBy { abs(it.area.toLong() - width.toLong() * height) }.thenBy { it.area })
}

private fun rectangle(minWidth: Int, maxWidth: Int, minHeight: Int = 1, maxHeight: Int = GRID_ROWS) =
    WidgetSizeRules((minHeight..maxHeight).flatMap { height -> (minWidth..maxWidth).map { WidgetSize(it, height) } })
private val squareInstrument = WidgetSizeRules(listOf(WidgetSize(1, 1), WidgetSize(2, 2)))
private val fixedAltimeter = WidgetSizeRules(listOf(WidgetSize(1, 1)))

fun AppSettings.widgetSizeRules(id: String): WidgetSizeRules = when (id) {
    "CLOCK" -> if (clockStyle == ClockStyle.ANALOG) squareInstrument else rectangle(1, 4)
    "WEATHER" -> rectangle(1, 3)
    "TELEMETRY" -> squareInstrument
    "NOW_PLAYING" -> rectangle(2, GRID_COLS)
    "ALTIMETER" -> fixedAltimeter
    "SPEEDOMETER" -> if (speedometerDigitalOnly) rectangle(1, 3, maxHeight = 1) else squareInstrument
    "VITALS" -> rectangle(1, 4)
    "TRIP_TRACKER" -> rectangle(2, 4)
    "SOUNDBOARD" -> rectangle(1, 4)
    else -> rectangle(1, GRID_COLS)
}

private val WidgetConfig.size get() = WidgetSize(spanX, spanY)
private fun WidgetConfig.sized(size: WidgetSize) = copy(
    spanX = size.width, spanY = size.height,
    gridX = gridX.coerceIn(0, GRID_COLS - size.width),
    gridY = gridY.coerceIn(0, GRID_ROWS - size.height))

private fun AppSettings.smallerSizes(widget: WidgetConfig): List<WidgetSize> = listOf(widget.size) +
    widgetSizeRules(widget.id).sizes.filter {
        it != widget.size && it.width <= widget.spanX && it.height <= widget.spanY
    }.sortedByDescending { it.area }

/** Exact packing on the 12-cell grid. A failed operation never leaves overlapping widgets. */
internal fun packWidgetLayout(
    widgets: List<WidgetConfig>,
    sizeOptions: Map<String, List<WidgetSize>> = emptyMap(),
    pinnedId: String? = null
): List<WidgetConfig>? {
    if (widgets.map { it.id }.distinct().size != widgets.size) return null
    val ordered = widgets.sortedBy { if (it.id == pinnedId) 0 else 1 }
    val options = ordered.map { sizeOptions[it.id] ?: listOf(it.size) }
    if (options.any { sizes -> sizes.isEmpty() || sizes.any { it.width !in 1..GRID_COLS || it.height !in 1..GRID_ROWS } }) return null
    val minimumArea = IntArray(ordered.size + 1)
    for (i in ordered.indices.reversed()) minimumArea[i] = minimumArea[i + 1] + options[i].minOf { it.area }
    val placed = arrayOfNulls<WidgetConfig>(ordered.size)
    val failed = HashSet<Int>()
    fun place(index: Int, occupied: Int): Boolean {
        if (index == ordered.size) return true
        if (Integer.bitCount(occupied) + minimumArea[index] > GRID_COLS * GRID_ROWS) return false
        val state = (index shl (GRID_COLS * GRID_ROWS)) or occupied
        if (state in failed) return false
        val widget = ordered[index]
        for (size in options[index]) {
            val preferred = widget.sized(size)
            val positions = mutableListOf(preferred.gridX to preferred.gridY)
            if (widget.id != pinnedId) for (y in 0..GRID_ROWS - size.height) for (x in 0..GRID_COLS - size.width) {
                if (x != preferred.gridX || y != preferred.gridY) positions.add(x to y)
            }
            for ((x, y) in positions) {
                var mask = 0
                for (dy in 0 until size.height) for (dx in 0 until size.width) mask = mask or (1 shl ((y + dy) * GRID_COLS + x + dx))
                if ((mask and occupied) != 0) continue
                placed[index] = preferred.copy(gridX = x, gridY = y)
                if (place(index + 1, occupied or mask)) return true
            }
        }
        failed.add(state)
        return false
    }
    if (!place(0, 0)) return null
    val byId = placed.filterNotNull().associateBy { it.id }
    return widgets.map { byId.getValue(it.id) }
}

/** Also runs for old saved layouts and after changing a widget's display style. */
fun AppSettings.withValidWidgetLayout(): AppSettings {
    val activeIds = activeWidgetIds()
    val configured = widgetLayout.distinctBy { it.id }.toMutableList()
    for (id in activeIds) if (configured.none { it.id == id }) {
        configured.add(defaultWidgetLayout().firstOrNull { it.id == id }
            ?: WidgetConfig(id, 0, 0).sized(widgetSizeRules(id).sizes.first()))
    }
    val normalized = configured.map { it.sized(widgetSizeRules(it.id).closest(it.spanX, it.spanY)) }
    val active = normalized.filter { it.id in activeIds }.map { it.copy(enabled = true) }
    // All built-in minimum sizes fit together; inactive configurations are retained for re-adding.
    val packed = checkNotNull(packWidgetLayout(active, active.associate { it.id to smallerSizes(it) }))
    val byId = packed.associateBy { it.id }
    return copy(widgetLayout = normalized.map { byId[it.id] ?: it })
}

fun AppSettings.withResizedWidget(id: String, width: Int, height: Int): AppSettings? {
    val rules = widgetSizeRules(id)
    val size = WidgetSize(width, height)
    if (size !in rules.sizes) return null
    val widget = widgetLayout.firstOrNull { it.id == id } ?: return null
    if (size == widget.size) return this
    if (!rules.isResizable) return null
    val activeIds = activeWidgetIds()
    val active = widgetLayout.filter { it.enabled && it.id in activeIds }
        .map { if (it.id == id) it.sized(size) else it }
    val packed = packWidgetLayout(active, pinnedId = id) ?: return null
    val byId = packed.associateBy { it.id }
    return copy(widgetLayout = widgetLayout.map { byId[it.id] ?: it })
}

fun AppSettings.withWidgetEnabled(id: String, enabled: Boolean): AppSettings = when (id) {
    "CLOCK" -> copy(showClock = enabled)
    "WEATHER" -> copy(showWeather = enabled)
    "NOW_PLAYING" -> copy(showNowPlaying = enabled)
    "TELEMETRY" -> copy(showTelemetry = enabled)
    "ALTIMETER" -> copy(showAltimeter = enabled)
    "SPEEDOMETER" -> copy(showSpeedometer = enabled)
    "VITALS" -> copy(showVitals = enabled)
    "TRIP_TRACKER" -> copy(showTripTracker = enabled)
    "SOUNDBOARD" -> copy(showSoundboard = enabled)
    else -> this
}

fun AppSettings.withAddedWidget(id: String): AppSettings? {
    val enabled = withWidgetEnabled(id, true)
    if (id !in enabled.activeWidgetIds()) return null
    if (id in activeWidgetIds()) return this
    val rules = widgetSizeRules(id)
    val previous = widgetLayout.firstOrNull { it.id == id }
    val widget = (previous ?: WidgetConfig(id, 0, 0)).sized(
        if (previous == null) rules.sizes.first() else rules.closest(previous.spanX, previous.spanY)).copy(enabled = true)
    val active = widgetLayout.filter { it.enabled && it.id in activeWidgetIds() } + widget
    val packed = packWidgetLayout(active, active.associate { it.id to smallerSizes(it) }) ?: return null
    val byId = packed.associateBy { it.id }
    val layout = widgetLayout.map { byId[it.id] ?: it }.toMutableList()
    if (previous == null) layout.add(byId.getValue(id))
    return enabled.copy(widgetLayout = layout)
}
