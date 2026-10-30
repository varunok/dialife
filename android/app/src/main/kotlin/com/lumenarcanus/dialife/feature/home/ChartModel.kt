package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.core.ui.theme.Onest
import com.lumenarcanus.dialife.core.ui.theme.PlexMono
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import kotlin.math.max
import kotlin.math.min

val ChartRanges = listOf(3, 6, 12, 24)
internal const val HOUR = 3_600_000L
internal const val FORECAST_SPAN = 30 * 60_000L
internal const val MIN_SPAN = HOUR // pinch in no further than an hour
internal val ACT_TOP = 4.dp // activity bars run along the top of the plot
internal val ACT_H = 12.dp
internal const val MAX_SPAN = 24 * HOUR + FORECAST_SPAN // the history the screen holds

/**
 * A pinched or panned time window; null means "the chosen range, ending now".
 * A window pushed against "now" keeps following it as new readings arrive.
 */
data class ChartWindow(val start: Long, val end: Long, val followsNow: Boolean = false) {
    fun at(now: Long): ChartWindow = if (followsNow) ChartWindow(now + FORECAST_SPAN - (end - start), now + FORECAST_SPAN, true) else this
}

internal sealed interface Pick {
    data class Reading(val index: Int) : Pick
    data class Event(val treatment: Treatment) : Pick
    data class Meter(val point: MeterPoint) : Pick // uploaded by xDrip+: nothing to edit here
    data class Suspect(val question: com.lumenarcanus.dialife.data.Question) : Pick
}

/**
 * Where a mark came from: typed in here (by whom), typed in xDrip+ and synced,
 * or sent by a glucometer by itself. Anything not entered in DiaLife gets a dot.
 */
internal sealed interface Origin {
    data class Here(val who: String) : Origin
    data object XDrip : Origin
    data class Device(val name: String) : Origin
}

internal fun originOf(tr: Treatment): Origin =
    tr.enteredBy?.takeIf { it.isNotBlank() && !it.equals("xdrip", ignoreCase = true) }?.let { Origin.Here(it) }
        ?: if (tr.pending) Origin.Here("") else Origin.XDrip

/** A glucometer reading: entered here as a BG check (treatment set), or uploaded by xDrip+. */
internal data class MeterPoint(val t: Long, val v: Int, val treatment: Treatment? = null, val src: String? = null) {
    val origin: Origin
        get() = when {
            treatment != null -> originOf(treatment)
            src.isNullOrBlank() || src == "manual" -> Origin.XDrip
            else -> Origin.Device(src.substringBefore(' ')) // "Roche 00:00:5E:…": the brand is enough
        }
}

/** What a window of the chart holds: the readings, marks and scale bounds in view. */
internal data class ChartView(
    val start: Long,
    val end: Long,
    val now: Long,
    val ts: List<Long>,
    val vs: List<Int>,
    val visible: List<Int>,
    val treatments: List<Treatment>,
    val thresholds: Thresholds,
    val meters: List<MeterPoint>,
    val lo: Float,
    val hi: Float,
    val activities: List<Treatment>,
    val suspects: List<com.lumenarcanus.dialife.data.Question>
)

internal fun chartView(
    history: History?,
    suspects: List<com.lumenarcanus.dialife.data.Question>,
    thresholds: Thresholds,
    start: Long,
    end: Long,
    now: Long
): ChartView {
    val ts = history?.sgv?.t.orEmpty()
    val vs = history?.sgv?.v.orEmpty()
    val until = min(end, now)
    val visible = ts.indices.filter { ts[it] in start..until }
    val inView = history?.treatments.orEmpty().filter { it.t in start..until }
    // glucometer checks sit on the glucose scale; everything else is a marker along the bottom
    val treatments = inView.filter { it.glucose == null && it.duration == null }
    // activity lasts: one that began before the window still shows its part
    val activities = history?.treatments.orEmpty().filter { a ->
        a.duration != null &&
            a.t <= until &&
            a.t + a.duration * 60_000L >= start
    }
    val marks = suspects.filter { it.state == "unknown" && it.from in start..until }
    val mbg = history?.mbg ?: com.lumenarcanus.dialife.data.Series()
    val meters = inView.mapNotNull { tr -> tr.glucose?.let { MeterPoint(tr.t, it, tr) } } +
        mbg.t.indices.filter { mbg.t[it] in start..until }.map { MeterPoint(mbg.t[it], mbg.v[it], src = mbg.d.getOrNull(it)) }
    val maxV = max(visible.maxOfOrNull { vs[it] } ?: 0, meters.maxOfOrNull { it.v } ?: 0)
    val hi = max(300, min(400, maxV + 20)).toFloat()
    return ChartView(start, end, now, ts, vs, visible, treatments, thresholds, meters, 40f, hi, activities, marks)
}

/** The small dot on a mark that came in from xDrip+ rather than typed in DiaLife. */
internal fun DrawScope.syncedDot(at: Offset, ink: Color, halo: Color) {
    drawCircle(halo, 3.6.dp.toPx(), at)
    drawCircle(ink, 2.4.dp.toPx(), at)
}

internal fun DrawScope.tooltip(
    measurer: androidx.compose.ui.text.TextMeasurer,
    lines: List<String>,
    ax: Float,
    ay: Float,
    plotW: Float,
    plotH: Float,
    bg: Color,
    border: Color,
    text: Color,
    muted: Color
) {
    val first = TextStyle(fontFamily = Onest, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = text)
    val rest = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = muted)
    val pad = 10.dp.toPx()
    // a long line (a meal typed as one long name) wraps instead of growing past the plot
    val fit = androidx.compose.ui.unit.Constraints(maxWidth = (plotW - pad * 2).toInt().coerceAtLeast(1))
    val ms = lines.mapIndexed { i, s -> measurer.measure(s, if (i == 0) first else rest, constraints = fit) }
    val w = ms.maxOf { it.size.width } + pad * 2
    val h = ms.sumOf { it.size.height } + pad * 2 + 2.dp.toPx() * (ms.size - 1)
    val left = (ax - w / 2).coerceIn(0f, max(0f, plotW - w))
    val top = if (ay > plotH / 2) max(0f, ay - h - 14.dp.toPx()) else min(plotH - h, ay + 14.dp.toPx())
    drawRoundRect(bg, Offset(left, top), Size(w, h), CornerRadius(12.dp.toPx()))
    drawRoundRect(border, Offset(left, top), Size(w, h), CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
    var yy = top + pad
    ms.forEach { m ->
        drawText(m, topLeft = Offset(left + pad, yy))
        yy += m.size.height + 2.dp.toPx()
    }
}

internal fun band(v: Int, th: Thresholds) = when {
    v < th.low -> -1
    v > th.high -> 1
    else -> 0
}

internal fun colorFor(v: Int, th: Thresholds, low: Color, ok: Color, high: Color) = when (band(v, th)) {
    -1 -> low
    1 -> high
    else -> ok
}

/** The reading just before index, if it is within 10 minutes. */
internal fun previous(ts: List<Long>, vs: List<Int>, i: Int): Int? = if (i > 0 && ts[i] - ts[i - 1] <= 10 * 60_000L) vs[i - 1] else null
