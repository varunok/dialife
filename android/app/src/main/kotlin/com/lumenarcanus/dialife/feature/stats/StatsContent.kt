package com.lumenarcanus.dialife.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.data.Stats
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.PlexMono
import java.util.Locale

@Composable
internal fun StatsContent(s: Stats, units: Units) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val th = s.thresholds
    fun g(v: Int) = formatGlucose(v, units, locale)
    fun pct(v: Double) = String.format(locale, "%.0f%%", v)

    // time in range: the big number and the five zones as one bar
    Card {
        Text(stringResource(R.string.tile_time_in_range), color = c.textMuted, fontSize = 13.sp)
        Text(pct(s.zones.inRange), color = c.inRange, fontSize = 48.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.stats_range_bounds, g(th.low), g(th.high)), color = c.textMuted, fontSize = 13.sp)
        val zones = listOf(
            Triple(s.zones.veryHigh, c.urgent, stringResource(R.string.stats_zone_very_high, g(th.urgentHigh))),
            Triple(s.zones.high, c.high, stringResource(R.string.stats_zone_high, g(th.high), g(th.urgentHigh))),
            Triple(s.zones.inRange, c.inRange, stringResource(R.string.stats_zone_in_range, g(th.low), g(th.high))),
            Triple(s.zones.low, c.low, stringResource(R.string.stats_zone_low, g(th.urgentLow), g(th.low))),
            Triple(s.zones.veryLow, Color(0xFFB0123A), stringResource(R.string.stats_zone_very_low, g(th.urgentLow)))
        )
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surfaceRaised)
                .semantics { contentDescription = zones.joinToString(", ") { "${it.third}: ${pct(it.first)}" } }
        ) {
            zones.asReversed().forEach { (v, col, _) ->
                if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(col))
            }
        }
        zones.forEach { (v, col, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(col, CircleShape))
                Spacer(Modifier.size(10.dp))
                Text(label, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(pct(v), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = PlexMono)
            }
        }
    }

    // the numbers a clinic asks for
    val unit = stringResource(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        Metric(stringResource(R.string.stats_mean), g(s.mean.toInt()), unit, Modifier.weight(1f))
        Metric(
            stringResource(R.string.stats_gmi),
            String.format(locale, "%.1f%%", s.gmi),
            stringResource(R.string.stats_gmi_hint),
            Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        Metric(
            stringResource(R.string.stats_cv),
            String.format(locale, "%.0f%%", s.cv),
            stringResource(if (s.cv <= 36) R.string.stats_cv_stable else R.string.stats_cv_unstable),
            Modifier.weight(1f)
        )
        Metric(
            stringResource(R.string.stats_hypos),
            s.hypos.toString(),
            stringResource(R.string.stats_hypos_hint, g(th.low)),
            Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        Metric(stringResource(R.string.stats_tir70), pct(s.tir70), stringResource(R.string.stats_tir70_hint), Modifier.weight(1f))
        val readings = androidx.compose.ui.res.pluralStringResource(R.plurals.stats_readings, s.count, s.count)
        // a young history makes coverage look alarming: say when the data starts
        val since = s.first?.takeIf { it - s.from > 6 * 3_600_000L }?.let {
            stringResource(
                R.string.stats_since,
                android.text.format.DateUtils.formatDateTime(
                    androidx.compose.ui.platform.LocalContext.current,
                    it,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or
                        android.text.format.DateUtils.FORMAT_ABBREV_MONTH
                )
            )
        }
        Metric(
            stringResource(R.string.stats_coverage),
            pct(s.coverage),
            listOfNotNull(readings, since).joinToString(", "),
            Modifier.weight(1f)
        )
    }

    if (s.daily.isNotEmpty()) DailyTotals(s)

    Card {
        Text(stringResource(R.string.stats_agp), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        // folding fewer than ~3 days onto one makes a "day" out of yesterday's evening and this morning
        val dataDays = (s.to - (s.first ?: s.from)) / 86_400_000.0
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (dataDays <= 1.0) {
            // everything fits in one day: show it in order, starting from the oldest reading,
            // so yesterday evening comes before this morning instead of after "now"
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = s.first ?: s.from }
            val start = (cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)) / 30 * 30
            Text(stringResource(R.string.stats_agp_short), color = c.textMuted, fontSize = 13.sp)
            Agp(s, units, Modifier.fillMaxWidth().height(220.dp), startMinute = start)
        } else if (dataDays < 3) {
            val since = android.text.format.DateUtils.formatDateTime(
                ctx,
                s.first ?: s.from,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH
            )
            Text(stringResource(R.string.stats_agp_wait, since), color = c.textMuted, fontSize = 14.sp)
        } else {
            Text(stringResource(R.string.stats_agp_hint), color = c.textMuted, fontSize = 13.sp)
            Agp(s, units, Modifier.fillMaxWidth().height(220.dp))
        }
    }
}

/** Insulin and carbs per day: what the doctor asks while doses are being adjusted. */
@Composable
internal fun DailyTotals(s: Stats) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun n(v: Double) = formatAmount(v, locale)
    val basal = com.lumenarcanus.dialife.ui.treatment.basalColor(c.isDark)
    Card {
        Text(stringResource(R.string.stats_daily), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        @Composable
        fun RowOf(day: String, b: String, r: String, g: String, head: Boolean) {
            val w = if (head) FontWeight.Medium else FontWeight.Normal
            val col = if (head) c.textMuted else c.text
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(1.4f))
                Text(
                    b,
                    color = if (head) basal else col,
                    fontSize = 14.sp,
                    fontWeight = w,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    r,
                    color = if (head) c.insulin else col,
                    fontSize = 14.sp,
                    fontWeight = w,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    g,
                    color = if (head) c.carbs else col,
                    fontSize = 14.sp,
                    fontWeight = w,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.1f)
                )
            }
        }
        val u = stringResource(R.string.unit_insulin)
        val gr = stringResource(R.string.unit_carbs)
        RowOf(
            stringResource(R.string.stats_day),
            stringResource(R.string.ins_basal),
            stringResource(R.string.ins_rapid),
            stringResource(R.string.tr_carbs),
            head = true
        )
        s.daily.forEach { d ->
            val day = runCatching {
                val t = java.time.LocalDate.parse(d.date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                android.text.format.DateUtils.formatDateTime(
                    ctx,
                    t,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or
                        android.text.format.DateUtils.FORMAT_ABBREV_MONTH
                )
            }.getOrDefault(d.date)
            RowOf(
                day,
                if (d.basal >
                    0
                ) {
                    "${n(d.basal)} $u"
                } else {
                    "—"
                },
                if (d.rapid >
                    0
                ) {
                    "${n(d.rapid)} $u"
                } else {
                    "—"
                },
                if (d.carbs > 0) "${n(d.carbs)} $gr" else "—",
                head = false
            )
        }
        if (s.daily.size > 1) {
            val k = s.daily.size.toDouble()
            fun avg(f: (com.lumenarcanus.dialife.data.DayTotal) -> Double) = Math.round(s.daily.sumOf(f) / k * 10) / 10.0
            RowOf(
                stringResource(R.string.stats_avg),
                "${n(
                    avg {
                        it.basal
                    }
                )} $u",
                "${n(avg { it.rapid })} $u",
                "${n(avg { it.carbs })} $gr",
                head = true
            )
        }
        Text(stringResource(R.string.stats_daily_hint), color = c.textFaint, fontSize = 12.sp)
    }
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(22.dp))
            .border(1.dp, c.line, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
internal fun Metric(label: String, value: String, hint: String, modifier: Modifier) {
    val c = DiaTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(c.surface, RoundedCornerShape(18.dp))
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = c.textMuted, fontSize = 13.sp)
        Text(value, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(hint, color = c.textFaint, fontSize = 12.sp)
    }
}

/** The ambulatory glucose profile: every day folded onto one, median with 25–75 and 5–95 bands. */
@Composable
internal fun Agp(s: Stats, units: Units, modifier: Modifier, startMinute: Int = 0) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val th = s.thresholds
    Canvas(modifier) {
        if (s.agp.isEmpty()) return@Canvas
        val labelH = 18.dp.toPx()
        val labelW = 32.dp.toPx()
        val w = size.width - labelW
        val h = size.height - labelH
        val lo = 40f
        // the scale follows the data: 100 above the highest value, rounded up to 50
        val top = maxOf(s.agp.maxOf { it.p95 }, th.high) + 100
        val hi = (((top + 49) / 50) * 50).toFloat()

        // startMinute rotates the day: 0 is the classic 00–24 axis
        fun pos(minute: Int) = ((minute - startMinute) % 1440 + 1440) % 1440
        fun x(minute: Int) = pos(minute) / 1440f * w
        val topPad = 10.dp.toPx() // the scale's top is drawn, not the frame's edge
        fun y(v: Int) = topPad + (h - topPad) * (1 - (v - lo) / (hi - lo))
        val label = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = c.textFaint)

        drawRect(c.inRange.copy(alpha = 0.07f), Offset(0f, y(th.high)), androidx.compose.ui.geometry.Size(w, y(th.low) - y(th.high)))
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
        drawLine(c.high.copy(alpha = 0.5f), Offset(0f, y(th.high)), Offset(w, y(th.high)), 1.dp.toPx(), pathEffect = dash)
        drawLine(c.low.copy(alpha = 0.55f), Offset(0f, y(th.low)), Offset(w, y(th.low)), 1.dp.toPx(), pathEffect = dash)
        // the top of the scale, so it is clear nothing is hidden above
        drawLine(c.line, Offset(0f, y(hi.toInt())), Offset(w, y(hi.toInt())), 1.dp.toPx())
        for (v in listOf(hi.toInt(), th.high, th.low)) {
            val m = measurer.measure(formatGlucose(v, units, locale), label.copy(color = c.textMuted))
            drawText(m, topLeft = Offset(size.width - m.size.width, y(v) - m.size.height / 2f))
        }
        val ticks = if (startMinute == 0) {
            listOf(0, 6, 12, 18, 24).map { it * 60 }
        } else {
            (0..3).map { ((startMinute + 59) / 60 * 60 + it * 360) } // whole hours, every 6 h from the start
        }
        for (tick in ticks) {
            val hour = tick / 60
            val tx = if (startMinute == 0) tick / 1440f * w else x(tick % 1440)
            if (startMinute != 0 && pos(tick % 1440) < pos(((startMinute + 59) / 60 * 60) % 1440)) continue
            drawLine(c.line, Offset(tx, y(hi.toInt())), Offset(tx, h), 1.dp.toPx())
            val m = measurer.measure(String.format(Locale.ROOT, "%02d:00", hour % 24), label)
            drawText(m, topLeft = Offset((tx - m.size.width / 2f).coerceIn(0f, w - m.size.width), h + 4.dp.toPx()))
        }

        // slots are 30 min; draw at their middle. Hours without data break the bands:
        // a straight line across them would invent a day nobody saw.
        val runs = mutableListOf<MutableList<com.lumenarcanus.dialife.data.AgpPoint>>()
        for (a in s.agp.sortedBy { pos(it.minute) }) {
            val last = runs.lastOrNull()?.last()
            if (last != null && pos(a.minute) - pos(last.minute) == 30) runs.last().add(a) else runs.add(mutableListOf(a))
        }
        clipRect(right = w, bottom = h) {
            for (pts in runs) {
                if (pts.size == 1) { // a lone slot: a dot rather than nothing
                    drawCircle(c.inRange, 3.dp.toPx(), Offset((x(pts[0].minute) + 15f / 1440f * w), y(pts[0].p50)))
                    continue
                }
                fun band(
                    top: (com.lumenarcanus.dialife.data.AgpPoint) -> Int,
                    bottom: (com.lumenarcanus.dialife.data.AgpPoint) -> Int,
                    color: Color
                ) {
                    val p = Path()
                    pts.forEachIndexed { i, a ->
                        if (i ==
                            0
                        ) {
                            p.moveTo((x(a.minute) + 15f / 1440f * w), y(top(a)))
                        } else {
                            p.lineTo((x(a.minute) + 15f / 1440f * w), y(top(a)))
                        }
                    }
                    pts.asReversed().forEach { a -> p.lineTo((x(a.minute) + 15f / 1440f * w), y(bottom(a))) }
                    p.close()
                    drawPath(p, color)
                }
                band({ it.p95 }, { it.p5 }, c.inRange.copy(alpha = 0.14f))
                band({ it.p75 }, { it.p25 }, c.inRange.copy(alpha = 0.30f))
                val median = Path()
                pts.forEachIndexed { i, a ->
                    if (i ==
                        0
                    ) {
                        median.moveTo((x(a.minute) + 15f / 1440f * w), y(a.p50))
                    } else {
                        median.lineTo((x(a.minute) + 15f / 1440f * w), y(a.p50))
                    }
                }
                drawPath(median, c.inRange, style = Stroke(2.5.dp.toPx()))
            }
        }
    }
}

/** Sensor vs glucometer: how far apart they usually are, overall, on this sensor, by level. */
