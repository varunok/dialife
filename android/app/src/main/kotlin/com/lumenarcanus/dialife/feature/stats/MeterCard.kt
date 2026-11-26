package com.lumenarcanus.dialife.feature.stats

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.info.InfoButton
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.Onest
import com.lumenarcanus.dialife.core.ui.theme.PlexMono
import com.lumenarcanus.dialife.data.MeterReport
import com.lumenarcanus.dialife.feature.entry.basalColor
import com.lumenarcanus.dialife.feature.home.meterInk
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

@Composable
internal fun MeterCard(m: MeterReport, units: Units) {
    val c = DiaTheme.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.meter_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            InfoButton("meter-estimate")
        }
        if (m.used == 0) {
            Text(stringResource(R.string.meter_empty), color = c.textMuted, fontSize = 14.sp)
            return@Card
        }
        Bias(m)
        MeterLines(m, units)
        if (m.total > m.used) Text(stringResource(R.string.meter_fast, m.total - m.used), color = c.textFaint, fontSize = 12.sp)
        if (!m.ready) Text(stringResource(R.string.meter_wait, m.need, m.used), color = c.textMuted, fontSize = 13.sp)
        MeterScatter(m, units, Modifier.fillMaxWidth().height(240.dp))
        // which colour is which sensor
        SensorLegend(m)
    }
}

/** "+6%" with whether the sensor reads higher or lower, from how many checks and sensors. */
@Composable
private fun Bias(m: MeterReport) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(signed(m.biasPct, "%", locale), color = c.text, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Column {
            Text(
                stringResource(
                    if (m.biasPct < 0) {
                        R.string.meter_lower
                    } else {
                        R.string.meter_higher
                    }
                ),
                color = c.textMuted,
                fontSize = 13.sp
            )
            Text(stringResource(R.string.meter_counts, m.used, m.sensors), color = c.textFaint, fontSize = 12.sp)
        }
    }
}

/** The current sensor, each range of values, and the typical difference (MARD). */
@Composable
private fun MeterLines(m: MeterReport, units: Units) {
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun g(v: Int) = formatGlucose(v, units, locale)
    m.current?.let { cur ->
        Line(
            stringResource(R.string.meter_current),
            stringResource(R.string.meter_current_sub, shortDate(ctx, cur.since), cur.day),
            if (cur.n > 0) signed(cur.biasPct, "%", locale) else "—",
            stringResource(R.string.meter_n, cur.n)
        )
    }
    m.bands.forEach { b ->
        val label = when {
            b.from == 0 -> stringResource(R.string.meter_band_low, g(b.to) + if (units == Units.MgDl) " mg/dL" else "")
            b.to == 0 -> stringResource(R.string.meter_band_high, g(b.from))
            else -> stringResource(R.string.meter_band_mid, g(b.from), g(b.to))
        }
        val value = if (b.unit == "mgdl") {
            (
                if (b.bias > 0) {
                    "+"
                } else if (b.bias < 0) {
                    "\u2212"
                } else {
                    ""
                }
                ) +
                formatGlucose(round(abs(b.bias)).toInt(), units, locale) +
                (if (units == Units.MgDl) " mg/dL" else "")
        } else {
            signed(b.bias, "%", locale)
        }
        Line(label, null, value, stringResource(R.string.meter_n, b.n))
    }
    Line(stringResource(R.string.meter_mard), stringResource(R.string.meter_mard_sub), String.format(locale, "%.1f%%", m.mard), null)
}

@Composable
private fun Line(label: String, sub: String?, value: String, valueSub: String?) {
    val c = DiaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = c.text, fontSize = 14.sp)
            sub?.let { Text(it, color = c.textFaint, fontSize = 12.sp) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, color = c.text, fontSize = 14.sp, fontFamily = PlexMono)
            valueSub?.let { Text(it, color = c.textFaint, fontSize = 12.sp) }
        }
    }
}

/** Which colour is which sensor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SensorLegend(m: MeterReport) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val inks = sensorInks()
    val current = m.starts.size
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        m.pairs.map { it.sensorNo }.distinct().sorted().forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(8.dp)) {
                    drawPath(
                        diamondPath(center, size.width / 2),
                        inks[
                            k %
                                inks.size
                        ]
                    )
                }
                Text(
                    when {
                        k == 0 -> stringResource(R.string.meter_legend_before)
                        k == current -> stringResource(R.string.meter_legend_current, shortDate(ctx, m.starts[k - 1]))
                        else -> stringResource(R.string.meter_legend, k, shortDate(ctx, m.starts[k - 1]))
                    },
                    color = c.textMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** "+6%", "−3 mg/dL": with its sign, no decimals. */
private fun signed(v: Double, unit: String, locale: Locale) = (
    if (v > 0) {
        "+"
    } else if (v < 0) {
        "\u2212"
    } else {
        ""
    }
    ) + String.format(locale, "%.0f", abs(v)) + unit

private fun shortDate(ctx: Context, t: Long) = DateUtils.formatDateTime(
    ctx,
    t,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or
        DateUtils.FORMAT_ABBREV_MONTH
)

@Composable
internal fun sensorInks(): List<Color> {
    val c = DiaTheme.colors
    return listOf(c.textMuted, basalColor(c.isDark), c.carbs, c.inRange, c.insulin, c.high)
}

internal fun diamondPath(at: Offset, half: Float) = Path().apply {
    moveTo(at.x, at.y - half)
    lineTo(at.x + half, at.y)
    lineTo(at.x, at.y + half)
    lineTo(at.x - half, at.y)
    close()
}

/** Each pair as a point: sensor across, meter up; on the diagonal they agree. */
@Composable
internal fun MeterScatter(m: MeterReport, units: Units, modifier: Modifier) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val inks = sensorInks()
    val est = meterInk(c.isDark)
    val axisSensor = stringResource(R.string.meter_axis_sensor)
    val axisMeter = stringResource(R.string.meter_axis_meter)
    val current = m.starts.size
    Canvas(modifier) {
        val label = TextStyle(
            fontFamily = PlexMono,
            fontSize = 10.sp,
            color = c.textFaint
        )
        val left = 34.dp.toPx()
        val bottom = 22.dp.toPx()
        val top = 18.dp.toPx()
        val w = size.width - left - 4.dp.toPx()
        val h = size.height - bottom - top
        val all = m.pairs.flatMap { listOf(it.sensor, it.meter.toDouble()) }
        val lo = 50.0
        val hi = maxOf(300.0, (all.maxOrNull() ?: 0.0) + 20)
        fun sx(v: Double) = (left + (v - lo) / (hi - lo) * w).toFloat()
        fun sy(v: Double) = (top + h - (v - lo) / (hi - lo) * h).toFloat()
        var v = 100
        while (v < hi) {
            drawLine(
                c.line,
                Offset(left, sy(v.toDouble())),
                Offset(left + w, sy(v.toDouble())),
                1.dp.toPx()
            )
            val t = measurer.measure(formatGlucose(v, units, locale), label)
            drawText(
                t,
                topLeft = Offset(
                    left - t.size.width - 6.dp.toPx(),
                    sy(v.toDouble()) - t.size.height / 2f
                )
            )
            drawText(t, topLeft = Offset(sx(v.toDouble()) - t.size.width / 2f, size.height - t.size.height))
            v += 50
        }
        val axis = label.copy(fontFamily = Onest, color = c.textMuted)
        drawText(measurer.measure(axisMeter, axis), topLeft = Offset(left + 4.dp.toPx(), 0f))
        val xs = measurer.measure(axisSensor, axis)
        drawText(
            xs,
            topLeft = Offset(
                left + w - xs.size.width,
                size.height - bottom - xs.size.height - 2.dp.toPx()
            )
        )
        clipRect(left, top, left + w, top + h) {
            // agreement, and the fitted relation once there is one
            drawLine(
                c.textFaint,
                Offset(sx(lo), sy(lo)),
                Offset(sx(hi), sy(hi)),
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            )
            m.model?.let { f ->
                val a = 60.0
                val b = hi - 20
                fun fit(s: Double) = s * (1 + f.b) + f.a
                drawLine(
                    est,
                    Offset(sx(a), sy(fit(a).coerceIn(lo, hi))),
                    Offset(sx(b), sy(fit(b).coerceIn(lo, hi))),
                    1.6.dp.toPx()
                )
            }
            m.pairs.forEach { p ->
                val at = Offset(sx(p.sensor), sy(p.meter.toDouble()))
                val ink = inks[p.sensorNo % inks.size].copy(alpha = if (p.sensorNo == current) 1f else 0.7f)
                if (p.used) {
                    drawPath(diamondPath(at, 5.dp.toPx()), ink)
                } else {
                    drawPath(diamondPath(at, 5.dp.toPx()), ink, style = Stroke(1.2.dp.toPx()))
                }
            }
        }
    }
}
