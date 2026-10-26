package com.lumenarcanus.dialife.ui.stats

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.Role
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
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.Stats
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.PlexMono
import java.util.Locale

private val Periods = listOf(1, 7, 14, 30)

private sealed interface Load {
    data object Loading : Load
    data object Failed : Load
    data class Ready(val stats: Stats) : Load
}

/** Statistics for the doctor and for us: time in range, averages, variability, the typical day. */
@Composable
fun StatsScreen(creds: Credentials, units: Units, onBack: () -> Unit, api: DiaLifeApi = DiaLifeApi()) {
    val c = DiaTheme.colors
    var days by rememberSaveable { mutableIntStateOf(14) }
    var load by androidx.compose.runtime.remember { mutableStateOf<Load>(Load.Loading) }
    var attempt by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    LaunchedEffect(days, attempt) {
        load = Load.Loading
        load = api.stats(creds, days).fold({ Load.Ready(it) }, { Load.Failed })
    }
    // sensor vs glucometer covers every check ever made, whatever the period
    var meter by androidx.compose.runtime.remember { mutableStateOf<com.lumenarcanus.dialife.data.MeterReport?>(null) }
    LaunchedEffect(attempt) { api.meter(creds).onSuccess { meter = it } }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
            Spacer(Modifier.weight(1f))
        }
        Text(stringResource(R.string.stats_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        PeriodChips(days) { days = it }
        when (val l = load) {
            Load.Loading -> Text(stringResource(R.string.home_loading), color = c.textMuted)
            Load.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.err_network), color = c.high)
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.stats_retry)) }
            }
            is Load.Ready -> if (l.stats.count == 0) {
                Text(stringResource(R.string.stats_empty), color = c.textMuted)
            } else {
                StatsContent(l.stats, units)
            }
        }
        meter?.let { MeterCard(it, units) }
    }
}

@Composable
private fun PeriodChips(days: Int, onPick: (Int) -> Unit) {
    val c = DiaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Periods.forEach { d ->
            val selected = d == days
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.stats_days, d, d),
                color = if (selected) c.text else c.textMuted,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onPick(d) })
                    .heightIn(min = 40.dp)
                    .padding(vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun StatsContent(s: Stats, units: Units) {
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
            Triple(s.zones.veryLow, Color(0xFFB0123A), stringResource(R.string.stats_zone_very_low, g(th.urgentLow))),
        )
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surfaceRaised)
                .semantics { contentDescription = zones.joinToString(", ") { "${it.third}: ${pct(it.first)}" } },
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
        Metric(stringResource(R.string.stats_gmi), String.format(locale, "%.1f%%", s.gmi), stringResource(R.string.stats_gmi_hint), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        Metric(
            stringResource(R.string.stats_cv), String.format(locale, "%.0f%%", s.cv),
            stringResource(if (s.cv <= 36) R.string.stats_cv_stable else R.string.stats_cv_unstable), Modifier.weight(1f),
        )
        Metric(stringResource(R.string.stats_hypos), s.hypos.toString(), stringResource(R.string.stats_hypos_hint, g(th.low)), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        Metric(stringResource(R.string.stats_tir70), pct(s.tir70), stringResource(R.string.stats_tir70_hint), Modifier.weight(1f))
        val readings = androidx.compose.ui.res.pluralStringResource(R.plurals.stats_readings, s.count, s.count)
        // a young history makes coverage look alarming: say when the data starts
        val since = s.first?.takeIf { it - s.from > 6 * 3_600_000L }?.let {
            stringResource(
                R.string.stats_since,
                android.text.format.DateUtils.formatDateTime(
                    androidx.compose.ui.platform.LocalContext.current, it,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
                ),
            )
        }
        Metric(stringResource(R.string.stats_coverage), pct(s.coverage), listOfNotNull(readings, since).joinToString(", "), Modifier.weight(1f))
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
                ctx, s.first ?: s.from,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
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
private fun DailyTotals(s: Stats) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun n(v: Double) = com.lumenarcanus.dialife.ui.treatment.formatAmount(v, locale)
    val basal = com.lumenarcanus.dialife.ui.treatment.basalColor(c.isDark)
    Card {
        Text(stringResource(R.string.stats_daily), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        @Composable
        fun RowOf(day: String, b: String, r: String, g: String, head: Boolean) {
            val w = if (head) FontWeight.Medium else FontWeight.Normal
            val col = if (head) c.textMuted else c.text
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(1.4f))
                Text(b, color = if (head) basal else col, fontSize = 14.sp, fontWeight = w, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(r, color = if (head) c.insulin else col, fontSize = 14.sp, fontWeight = w, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(g, color = if (head) c.carbs else col, fontSize = 14.sp, fontWeight = w, textAlign = TextAlign.End, modifier = Modifier.weight(1.1f))
            }
        }
        val u = stringResource(R.string.unit_insulin)
        val gr = stringResource(R.string.unit_carbs)
        RowOf(stringResource(R.string.stats_day), stringResource(R.string.ins_basal), stringResource(R.string.ins_rapid), stringResource(R.string.tr_carbs), head = true)
        s.daily.forEach { d ->
            val day = runCatching {
                val t = java.time.LocalDate.parse(d.date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                android.text.format.DateUtils.formatDateTime(ctx, t, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
            }.getOrDefault(d.date)
            RowOf(day, if (d.basal > 0) "${n(d.basal)} $u" else "—", if (d.rapid > 0) "${n(d.rapid)} $u" else "—", if (d.carbs > 0) "${n(d.carbs)} $gr" else "—", head = false)
        }
        if (s.daily.size > 1) {
            val k = s.daily.size.toDouble()
            fun avg(f: (com.lumenarcanus.dialife.data.DayTotal) -> Double) = Math.round(s.daily.sumOf(f) / k * 10) / 10.0
            RowOf(stringResource(R.string.stats_avg), "${n(avg { it.basal })} $u", "${n(avg { it.rapid })} $u", "${n(avg { it.carbs })} $gr", head = true)
        }
        Text(stringResource(R.string.stats_daily_hint), color = c.textFaint, fontSize = 12.sp)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(22.dp))
            .border(1.dp, c.line, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Metric(label: String, value: String, hint: String, modifier: Modifier) {
    val c = DiaTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(c.surface, RoundedCornerShape(18.dp))
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = c.textMuted, fontSize = 13.sp)
        Text(value, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(hint, color = c.textFaint, fontSize = 12.sp)
    }
}

/** The ambulatory glucose profile: every day folded onto one, median with 25–75 and 5–95 bands. */
@Composable
private fun Agp(s: Stats, units: Units, modifier: Modifier, startMinute: Int = 0) {
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
        val ticks = if (startMinute == 0) listOf(0, 6, 12, 18, 24).map { it * 60 }
        else (0..3).map { ((startMinute + 59) / 60 * 60 + it * 360) } // whole hours, every 6 h from the start
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
            fun band(top: (com.lumenarcanus.dialife.data.AgpPoint) -> Int, bottom: (com.lumenarcanus.dialife.data.AgpPoint) -> Int, color: Color) {
                val p = Path()
                pts.forEachIndexed { i, a -> if (i == 0) p.moveTo((x(a.minute) + 15f / 1440f * w), y(top(a))) else p.lineTo((x(a.minute) + 15f / 1440f * w), y(top(a))) }
                pts.asReversed().forEach { a -> p.lineTo((x(a.minute) + 15f / 1440f * w), y(bottom(a))) }
                p.close()
                drawPath(p, color)
            }
            band({ it.p95 }, { it.p5 }, c.inRange.copy(alpha = 0.14f))
            band({ it.p75 }, { it.p25 }, c.inRange.copy(alpha = 0.30f))
            val median = Path()
            pts.forEachIndexed { i, a -> if (i == 0) median.moveTo((x(a.minute) + 15f / 1440f * w), y(a.p50)) else median.lineTo((x(a.minute) + 15f / 1440f * w), y(a.p50)) }
            drawPath(median, c.inRange, style = Stroke(2.5.dp.toPx()))
        }
        }
    }
}

/** Sensor vs glucometer: how far apart they usually are, overall, on this sensor, by level. */
@Composable
internal fun MeterCard(m: com.lumenarcanus.dialife.data.MeterReport, units: Units) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun g(v: Int) = formatGlucose(v, units, locale)
    fun signed(v: Double, unit: String) = (if (v > 0) "+" else if (v < 0) "\u2212" else "") + String.format(locale, "%.0f", kotlin.math.abs(v)) + unit
    fun date(t: Long) = android.text.format.DateUtils.formatDateTime(ctx, t, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
    Card {
        Text(stringResource(R.string.meter_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (m.used == 0) {
            Text(stringResource(R.string.meter_empty), color = c.textMuted, fontSize = 14.sp)
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(signed(m.biasPct, "%"), color = c.text, fontSize = 40.sp, fontWeight = FontWeight.Light)
            Column {
                Text(stringResource(if (m.biasPct < 0) R.string.meter_lower else R.string.meter_higher), color = c.textMuted, fontSize = 13.sp)
                Text(stringResource(R.string.meter_counts, m.used, m.sensors), color = c.textFaint, fontSize = 12.sp)
            }
        }
        @Composable
        fun Line(label: String, sub: String?, value: String, valueSub: String?) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = c.text, fontSize = 14.sp)
                    sub?.let { Text(it, color = c.textFaint, fontSize = 12.sp) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(value, color = c.text, fontSize = 14.sp, fontFamily = com.lumenarcanus.dialife.ui.theme.PlexMono)
                    valueSub?.let { Text(it, color = c.textFaint, fontSize = 12.sp) }
                }
            }
        }
        m.current?.let { cur ->
            Line(
                stringResource(R.string.meter_current), stringResource(R.string.meter_current_sub, date(cur.since), cur.day),
                if (cur.n > 0) signed(cur.biasPct, "%") else "—", stringResource(R.string.meter_n, cur.n),
            )
        }
        m.bands.forEach { b ->
            val label = when {
                b.from == 0 -> stringResource(R.string.meter_band_low, g(b.to) + if (units == Units.MgDl) " mg/dL" else "")
                b.to == 0 -> stringResource(R.string.meter_band_high, g(b.from))
                else -> stringResource(R.string.meter_band_mid, g(b.from), g(b.to))
            }
            val value = if (b.unit == "mgdl") {
                (if (b.bias > 0) "+" else if (b.bias < 0) "\u2212" else "") + formatGlucose(kotlin.math.round(kotlin.math.abs(b.bias)).toInt(), units, locale) +
                    (if (units == Units.MgDl) " mg/dL" else "")
            } else signed(b.bias, "%")
            Line(label, null, value, stringResource(R.string.meter_n, b.n))
        }
        Line(stringResource(R.string.meter_mard), stringResource(R.string.meter_mard_sub), String.format(locale, "%.1f%%", m.mard), null)
        if (m.total > m.used) Text(stringResource(R.string.meter_fast, m.total - m.used), color = c.textFaint, fontSize = 12.sp)
        if (!m.ready) Text(stringResource(R.string.meter_wait, m.need, m.used), color = c.textMuted, fontSize = 13.sp)
        MeterScatter(m, units, Modifier.fillMaxWidth().height(240.dp))
        // which colour is which sensor
        val inks = sensorInks()
        val current = m.starts.size
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.pairs.map { it.sensorNo }.distinct().sorted().forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawPath(diamondPath(center, size.width / 2), inks[k % inks.size]) }
                    Text(
                        when {
                            k == 0 -> stringResource(R.string.meter_legend_before)
                            k == current -> stringResource(R.string.meter_legend_current, date(m.starts[k - 1]))
                            else -> stringResource(R.string.meter_legend, k, date(m.starts[k - 1]))
                        },
                        color = c.textMuted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun sensorInks(): List<Color> {
    val c = DiaTheme.colors
    return listOf(c.textMuted, com.lumenarcanus.dialife.ui.treatment.basalColor(c.isDark), c.carbs, c.inRange, c.insulin, c.high)
}

private fun diamondPath(at: androidx.compose.ui.geometry.Offset, half: Float) = androidx.compose.ui.graphics.Path().apply {
    moveTo(at.x, at.y - half); lineTo(at.x + half, at.y); lineTo(at.x, at.y + half); lineTo(at.x - half, at.y); close()
}

/** Each pair as a point: sensor across, meter up; on the diagonal they agree. */
@Composable
private fun MeterScatter(m: com.lumenarcanus.dialife.data.MeterReport, units: Units, modifier: Modifier) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val inks = sensorInks()
    val est = com.lumenarcanus.dialife.ui.home.meterInk(c.isDark)
    val axisSensor = stringResource(R.string.meter_axis_sensor)
    val axisMeter = stringResource(R.string.meter_axis_meter)
    val current = m.starts.size
    androidx.compose.foundation.Canvas(modifier) {
        val label = androidx.compose.ui.text.TextStyle(fontFamily = com.lumenarcanus.dialife.ui.theme.PlexMono, fontSize = 10.sp, color = c.textFaint)
        val left = 34.dp.toPx(); val bottom = 22.dp.toPx(); val top = 18.dp.toPx()
        val w = size.width - left - 4.dp.toPx(); val h = size.height - bottom - top
        val all = m.pairs.flatMap { listOf(it.sensor, it.meter.toDouble()) }
        val lo = 50.0
        val hi = maxOf(300.0, (all.maxOrNull() ?: 0.0) + 20)
        fun sx(v: Double) = (left + (v - lo) / (hi - lo) * w).toFloat()
        fun sy(v: Double) = (top + h - (v - lo) / (hi - lo) * h).toFloat()
        var v = 100
        while (v < hi) {
            drawLine(c.line, androidx.compose.ui.geometry.Offset(left, sy(v.toDouble())), androidx.compose.ui.geometry.Offset(left + w, sy(v.toDouble())), 1.dp.toPx())
            val t = measurer.measure(formatGlucose(v, units, locale), label)
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(left - t.size.width - 6.dp.toPx(), sy(v.toDouble()) - t.size.height / 2f))
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(sx(v.toDouble()) - t.size.width / 2f, size.height - t.size.height))
            v += 50
        }
        val axis = label.copy(fontFamily = com.lumenarcanus.dialife.ui.theme.Onest, color = c.textMuted)
        drawText(measurer.measure(axisMeter, axis), topLeft = androidx.compose.ui.geometry.Offset(left + 4.dp.toPx(), 0f))
        val xs = measurer.measure(axisSensor, axis)
        drawText(xs, topLeft = androidx.compose.ui.geometry.Offset(left + w - xs.size.width, size.height - bottom - xs.size.height - 2.dp.toPx()))
        clipRect(left, top, left + w, top + h) {
            // agreement, and the fitted relation once there is one
            drawLine(c.textFaint, androidx.compose.ui.geometry.Offset(sx(lo), sy(lo)), androidx.compose.ui.geometry.Offset(sx(hi), sy(hi)), 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
            m.model?.let { f ->
                val a = 60.0; val b = hi - 20
                fun fit(s: Double) = s * (1 + f.b) + f.a
                drawLine(est, androidx.compose.ui.geometry.Offset(sx(a), sy(fit(a).coerceIn(lo, hi))), androidx.compose.ui.geometry.Offset(sx(b), sy(fit(b).coerceIn(lo, hi))), 1.6.dp.toPx())
            }
            m.pairs.forEach { p ->
                val at = androidx.compose.ui.geometry.Offset(sx(p.sensor), sy(p.meter.toDouble()))
                val ink = inks[p.sensorNo % inks.size].copy(alpha = if (p.sensorNo == current) 1f else 0.7f)
                if (p.used) drawPath(diamondPath(at, 5.dp.toPx()), ink)
                else drawPath(diamondPath(at, 5.dp.toPx()), ink, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
            }
        }
    }
}
