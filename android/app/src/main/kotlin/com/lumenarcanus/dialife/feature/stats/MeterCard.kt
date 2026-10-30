package com.lumenarcanus.dialife.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.PlexMono

@Composable
internal fun MeterCard(m: com.lumenarcanus.dialife.data.MeterReport, units: Units) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun g(v: Int) = formatGlucose(v, units, locale)
    fun signed(v: Double, unit: String) = (
        if (v > 0) {
            "+"
        } else if (v < 0) {
            "\u2212"
        } else {
            ""
        }
        ) + String.format(locale, "%.0f", kotlin.math.abs(v)) + unit
    fun date(t: Long) = android.text.format.DateUtils.formatDateTime(
        ctx,
        t,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or
            android.text.format.DateUtils.FORMAT_ABBREV_MONTH
    )
    Card {
        Text(stringResource(R.string.meter_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (m.used == 0) {
            Text(stringResource(R.string.meter_empty), color = c.textMuted, fontSize = 14.sp)
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(signed(m.biasPct, "%"), color = c.text, fontSize = 40.sp, fontWeight = FontWeight.Light)
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
        @Composable
        fun Line(label: String, sub: String?, value: String, valueSub: String?) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = c.text, fontSize = 14.sp)
                    sub?.let { Text(it, color = c.textFaint, fontSize = 12.sp) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(value, color = c.text, fontSize = 14.sp, fontFamily = com.lumenarcanus.dialife.core.ui.theme.PlexMono)
                    valueSub?.let { Text(it, color = c.textFaint, fontSize = 12.sp) }
                }
            }
        }
        m.current?.let { cur ->
            Line(
                stringResource(R.string.meter_current),
                stringResource(R.string.meter_current_sub, date(cur.since), cur.day),
                if (cur.n > 0) signed(cur.biasPct, "%") else "—",
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
                    formatGlucose(kotlin.math.round(kotlin.math.abs(b.bias)).toInt(), units, locale) +
                    (if (units == Units.MgDl) " mg/dL" else "")
            } else {
                signed(b.bias, "%")
            }
            Line(label, null, value, stringResource(R.string.meter_n, b.n))
        }
        Line(stringResource(R.string.meter_mard), stringResource(R.string.meter_mard_sub), String.format(locale, "%.1f%%", m.mard), null)
        if (m.total > m.used) Text(stringResource(R.string.meter_fast, m.total - m.used), color = c.textFaint, fontSize = 12.sp)
        if (!m.ready) Text(stringResource(R.string.meter_wait, m.need, m.used), color = c.textMuted, fontSize = 13.sp)
        MeterScatter(m, units, Modifier.fillMaxWidth().height(240.dp))
        // which colour is which sensor
        val inks = sensorInks()
        val current = m.starts.size
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            m.pairs.map { it.sensorNo }.distinct().sorted().forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
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
                            k == current -> stringResource(R.string.meter_legend_current, date(m.starts[k - 1]))
                            else -> stringResource(R.string.meter_legend, k, date(m.starts[k - 1]))
                        },
                        color = c.textMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun sensorInks(): List<Color> {
    val c = DiaTheme.colors
    return listOf(c.textMuted, com.lumenarcanus.dialife.feature.entry.basalColor(c.isDark), c.carbs, c.inRange, c.insulin, c.high)
}

internal fun diamondPath(at: androidx.compose.ui.geometry.Offset, half: Float) = androidx.compose.ui.graphics.Path().apply {
    moveTo(at.x, at.y - half)
    lineTo(at.x + half, at.y)
    lineTo(at.x, at.y + half)
    lineTo(at.x - half, at.y)
    close()
}

/** Each pair as a point: sensor across, meter up; on the diagonal they agree. */
@Composable
internal fun MeterScatter(m: com.lumenarcanus.dialife.data.MeterReport, units: Units, modifier: Modifier) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val inks = sensorInks()
    val est = com.lumenarcanus.dialife.feature.home.meterInk(c.isDark)
    val axisSensor = stringResource(R.string.meter_axis_sensor)
    val axisMeter = stringResource(R.string.meter_axis_meter)
    val current = m.starts.size
    androidx.compose.foundation.Canvas(modifier) {
        val label = androidx.compose.ui.text.TextStyle(
            fontFamily = com.lumenarcanus.dialife.core.ui.theme.PlexMono,
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
                androidx.compose.ui.geometry.Offset(left, sy(v.toDouble())),
                androidx.compose.ui.geometry.Offset(left + w, sy(v.toDouble())),
                1.dp.toPx()
            )
            val t = measurer.measure(formatGlucose(v, units, locale), label)
            drawText(
                t,
                topLeft = androidx.compose.ui.geometry.Offset(
                    left - t.size.width - 6.dp.toPx(),
                    sy(v.toDouble()) - t.size.height / 2f
                )
            )
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(sx(v.toDouble()) - t.size.width / 2f, size.height - t.size.height))
            v += 50
        }
        val axis = label.copy(fontFamily = com.lumenarcanus.dialife.core.ui.theme.Onest, color = c.textMuted)
        drawText(measurer.measure(axisMeter, axis), topLeft = androidx.compose.ui.geometry.Offset(left + 4.dp.toPx(), 0f))
        val xs = measurer.measure(axisSensor, axis)
        drawText(
            xs,
            topLeft = androidx.compose.ui.geometry.Offset(
                left + w - xs.size.width,
                size.height - bottom - xs.size.height - 2.dp.toPx()
            )
        )
        clipRect(left, top, left + w, top + h) {
            // agreement, and the fitted relation once there is one
            drawLine(
                c.textFaint,
                androidx.compose.ui.geometry.Offset(sx(lo), sy(lo)),
                androidx.compose.ui.geometry.Offset(sx(hi), sy(hi)),
                1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            )
            m.model?.let { f ->
                val a = 60.0
                val b = hi - 20
                fun fit(s: Double) = s * (1 + f.b) + f.a
                drawLine(
                    est,
                    androidx.compose.ui.geometry.Offset(sx(a), sy(fit(a).coerceIn(lo, hi))),
                    androidx.compose.ui.geometry.Offset(sx(b), sy(fit(b).coerceIn(lo, hi))),
                    1.6.dp.toPx()
                )
            }
            m.pairs.forEach { p ->
                val at = androidx.compose.ui.geometry.Offset(sx(p.sensor), sy(p.meter.toDouble()))
                val ink = inks[p.sensorNo % inks.size].copy(alpha = if (p.sensorNo == current) 1f else 0.7f)
                if (p.used) {
                    drawPath(diamondPath(at, 5.dp.toPx()), ink)
                } else {
                    drawPath(diamondPath(at, 5.dp.toPx()), ink, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
                }
            }
        }
    }
}
