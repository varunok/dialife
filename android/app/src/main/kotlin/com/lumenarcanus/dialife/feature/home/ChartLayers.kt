package com.lumenarcanus.dialife.feature.home

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaColors
import com.lumenarcanus.dialife.core.ui.theme.Onest
import com.lumenarcanus.dialife.core.ui.theme.PlexMono
import com.lumenarcanus.dialife.data.Forecast
import com.lumenarcanus.dialife.data.MeterModel
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.feature.entry.basalColor
import com.lumenarcanus.dialife.feature.entry.basalContainer
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// The chart's layers, drawn bottom to top in the order GlucoseChart calls them.

private fun DrawScope.dashed(on: Float, off: Float) = PathEffect.dashPathEffect(floatArrayOf(on.dp.toPx(), off.dp.toPx()))

private fun labelStyle(c: DiaColors) = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = c.textMuted)

private val glyphStyle = TextStyle(fontFamily = Onest, fontSize = 10.sp, fontWeight = FontWeight.Bold)

private fun DrawScope.ringOutline(ink: Color, at: Offset) {
    drawCircle(ink, 9.dp.toPx(), at, style = Stroke(1.5.dp.toPx(), pathEffect = dashed(3f, 3f)))
}

private fun DrawScope.glyphAt(measurer: TextMeasurer, glyph: String, style: TextStyle, at: Offset) {
    val m = measurer.measure(glyph, style)
    drawText(m, topLeft = Offset(at.x - m.size.width / 2f, at.y - m.size.height / 2f))
}

/** The stretch an open alarm is about. */
internal fun DrawScope.drawHighlight(s: ChartScale, c: DiaColors, from: Long) {
    val hx = s.x(from).coerceIn(0f, s.plotW)
    drawRect(
        c.high.copy(alpha = 0.10f),
        Offset(hx, 0f),
        Size((s.x(s.view.now).coerceIn(0f, s.plotW) - hx).coerceAtLeast(0f), s.plotH)
    )
}

/** Where the sensor is not believed (a shower and half an hour after): hatched, labelled at the top. */
internal fun DrawScope.drawUntrusted(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText) {
    val gap = 8.dp.toPx()
    s.view.untrusted.forEach { w ->
        val x0 = s.x(w.from).coerceIn(0f, s.plotW)
        val x1 = s.x(w.until).coerceIn(0f, s.plotW)
        if (x1 <= x0) return@forEach
        drawRect(c.high.copy(alpha = 0.08f), Offset(x0, 0f), Size(x1 - x0, s.plotH))
        clipRect(left = x0, right = x1, bottom = s.plotH) {
            var x = x0 - s.plotH
            while (x < x1) {
                drawLine(c.high.copy(alpha = 0.22f), Offset(x, s.plotH), Offset(x + s.plotH, 0f), 1.dp.toPx())
                x += gap
            }
        }
        drawLine(c.high.copy(alpha = 0.6f), Offset(x0, 0f), Offset(x0, s.plotH), 1.dp.toPx(), pathEffect = dashed(3f, 3f))
        val m = measurer.measure("⚠ " + text.untrustedLabel, labelStyle(c).copy(color = c.high, fontWeight = FontWeight.SemiBold))
        if (x1 - x0 > m.size.width + 4.dp.toPx()) {
            drawText(m, topLeft = Offset(x0 + 3.dp.toPx(), ACT_TOP.toPx() + ACT_H.toPx() + 3.dp.toPx()))
        }
    }
}

/**
 * When the "no sensor" mode was on: a calm band with its name at the top, no
 * hatching and no amber, as nothing is wrong: there was simply no sensor.
 */
internal fun DrawScope.drawSensorless(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText) {
    s.view.sensorless.forEach { p ->
        val x0 = s.x(p.from).coerceIn(0f, s.plotW)
        val x1 = s.x(min(p.end(s.view.now), s.view.now)).coerceIn(0f, s.plotW)
        if (x1 <= x0) return@forEach
        drawRect(c.sensorless.copy(alpha = 0.10f), Offset(x0, 0f), Size(x1 - x0, s.plotH))
        drawLine(c.sensorless.copy(alpha = 0.55f), Offset(x0, 0f), Offset(x0, s.plotH), 1.dp.toPx())
        if (p.until > 0) drawLine(c.sensorless.copy(alpha = 0.55f), Offset(x1, 0f), Offset(x1, s.plotH), 1.dp.toPx())
        val m = measurer.measure(text.sensorlessLabel, labelStyle(c).copy(color = c.sensorless, fontWeight = FontWeight.SemiBold))
        if (x1 - x0 > m.size.width + 4.dp.toPx()) {
            drawText(m, topLeft = Offset(x0 + 3.dp.toPx(), ACT_TOP.toPx() + ACT_H.toPx() + 3.dp.toPx()))
        }
    }
}

/** The target band up to now and the two threshold lines, labelled on the right. */
internal fun DrawScope.drawThresholds(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText) {
    val th = s.view.thresholds
    drawRect(
        c.inRange.copy(alpha = 0.07f),
        Offset(0f, s.y(th.high)),
        Size(s.x(s.view.now).coerceIn(0f, s.plotW), s.y(th.low) - s.y(th.high))
    )
    val dash = dashed(3f, 5f)
    drawLine(c.high.copy(alpha = 0.5f), Offset(0f, s.y(th.high)), Offset(s.plotW, s.y(th.high)), 1.dp.toPx(), pathEffect = dash)
    drawLine(c.low.copy(alpha = 0.55f), Offset(0f, s.y(th.low)), Offset(s.plotW, s.y(th.low)), 1.dp.toPx(), pathEffect = dash)
    for (v in listOf(th.high, th.low)) {
        val m = measurer.measure(formatGlucose(v, text.units, text.locale), labelStyle(c))
        drawText(m, topLeft = Offset(size.width - m.size.width, s.y(v) - m.size.height / 2f))
    }
}

/** Hour ticks on local wall-clock boundaries, labelled below. */
internal fun DrawScope.drawHourGrid(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText) {
    val start = s.view.start
    val spanHours = (s.view.end - start).toFloat() / HOUR
    val step = when {
        spanHours <= 1.6f -> HOUR / 4
        spanHours <= 3.6f -> HOUR / 2
        spanHours <= 7f -> HOUR
        spanHours <= 13f -> 2 * HOUR
        else -> 4 * HOUR
    }
    val tz = TimeZone.getDefault()
    var tick = ceil((start + tz.getOffset(start)).toDouble() / step).toLong() * step - tz.getOffset(start)
    var labelEnd = Float.NEGATIVE_INFINITY // right edge of the last label: never let two overlap
    val style = labelStyle(c).copy(color = c.textFaint)
    while (tick <= min(s.view.now, s.view.end)) {
        val tx = s.x(tick)
        drawLine(c.line, Offset(tx, 0f), Offset(tx, s.plotH), 1.dp.toPx())
        val m = measurer.measure(text.clock(tick), style)
        val left = (tx - m.size.width / 2f).coerceIn(0f, s.plotW - m.size.width)
        if (left >= labelEnd + 4.dp.toPx()) {
            drawText(m, topLeft = Offset(left, s.plotH + 6.dp.toPx()))
            labelEnd = left + m.size.width
        }
        tick += step
    }
}

/** The forecast cone and line from the last reading; only when that reading is the latest. */
internal fun DrawScope.drawForecast(s: ChartScale, c: DiaColors, forecast: Forecast?) {
    val v = s.view
    // none from a reading that is not believed (a shower): it would only carry the error forward
    val f = forecast?.takeIf {
        it.cone.isNotEmpty() && v.visible.isNotEmpty() && v.visible.last() == v.ts.lastIndex && v.trusted(v.visible.last())
    } ?: return
    val lastT = v.ts[v.visible.last()]
    val lastV = v.vs[v.visible.last()]
    val cone = Path().apply {
        moveTo(s.x(lastT), s.y(lastV))
        f.cone.forEach { lineTo(s.x(it.t).coerceAtMost(s.plotW), s.y(it.hi.coerceAtMost(v.hi.toInt()))) }
        f.cone.asReversed().forEach { lineTo(s.x(it.t).coerceAtMost(s.plotW), s.y(it.lo.coerceAtLeast(v.lo.toInt()))) }
        close()
    }
    drawPath(cone, c.inRange.copy(alpha = 0.12f))
    val line = Path().apply {
        moveTo(s.x(lastT), s.y(lastV))
        f.predicted.forEach { lineTo(s.x(it.t), s.y(it.sgv)) }
    }
    drawPath(line, c.inRange.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx(), pathEffect = dashed(2f, 4f)))
}

internal fun DrawScope.drawNowLine(s: ChartScale, c: DiaColors) {
    val nowX = s.x(s.view.now)
    drawLine(c.textFaint, Offset(nowX, 0f), Offset(nowX, s.plotH), 1.dp.toPx(), pathEffect = dashed(2f, 3f))
}

/** "≈ meter": a faint band and a dashed line under the sensor dots, broken where the sensor is. */
internal fun DrawScope.drawMeterEstimate(s: ChartScale, c: DiaColors, m: MeterModel) {
    val v = s.view
    val ink = meterInk(c.isDark)
    fun drawRun(run: List<Int>) {
        if (run.size < 2) return
        val band = Path().apply {
            run.forEachIndexed { k, i ->
                val (e, sd) = m.estimate(v.vs[i])
                val px = s.x(v.ts[i])
                val py = s.y((e + sd).coerceAtMost(v.hi.toInt()))
                if (k == 0) moveTo(px, py) else lineTo(px, py)
            }
            run.asReversed().forEach { i ->
                val (e, sd) = m.estimate(v.vs[i])
                lineTo(s.x(v.ts[i]), s.y((e - sd).coerceAtLeast(v.lo.toInt())))
            }
            close()
        }
        drawPath(band, ink.copy(alpha = 0.10f))
        val line = Path().apply {
            run.forEachIndexed { k, i ->
                val e = m.estimate(v.vs[i]).first.coerceIn(v.lo.toInt(), v.hi.toInt())
                if (k == 0) moveTo(s.x(v.ts[i]), s.y(e)) else lineTo(s.x(v.ts[i]), s.y(e))
            }
        }
        drawPath(line, ink.copy(alpha = 0.75f), style = Stroke(1.4.dp.toPx(), pathEffect = dashed(5f, 4f)))
    }
    var run = mutableListOf<Int>()
    v.visible.forEach { i ->
        if (!v.trusted(i)) { // the sensor is not believed here: nothing to estimate from
            drawRun(run)
            run = mutableListOf()
            return@forEach
        }
        if (run.isNotEmpty() && v.ts[i] - v.ts[run.last()] > 12 * 60_000L) {
            drawRun(run)
            run = mutableListOf()
        }
        run.add(i)
    }
    drawRun(run)
}

/** Sensor dots, smaller the longer the window; a ring on the current reading. */
internal fun DrawScope.drawReadings(s: ChartScale, c: DiaColors) {
    val v = s.view
    val spanHours = (v.end - v.start).toFloat() / HOUR
    val r = when {
        spanHours <= 3.6f -> 3.2f
        spanHours <= 7f -> 2.4f
        spanHours <= 13f -> 1.8f
        else -> 1.4f
    }.dp.toPx()
    fun ink(i: Int) = colorFor(v.vs[i], v.thresholds, c.low, c.inRange, c.high)
    v.visible.forEach { i ->
        val at = Offset(s.x(v.ts[i]), s.y(v.vs[i]))
        // not believed: a faint hollow dot, so no one reads it as the sugar
        if (v.trusted(i)) drawCircle(ink(i), r, at) else drawCircle(ink(i).copy(alpha = 0.5f), r, at, style = Stroke(1.dp.toPx()))
    }
    v.visible.lastOrNull()?.takeIf { it == v.ts.lastIndex }?.let { i ->
        val at = Offset(s.x(v.ts[i]), s.y(v.vs[i]))
        drawCircle(c.background, r + 4.dp.toPx(), at)
        if (v.trusted(
                i
            )
        ) {
            drawCircle(ink(i), r + 2.dp.toPx(), at)
        } else {
            drawCircle(ink(i), r + 2.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Insulin, carbs and notes along the bottom, each in its row. */
internal fun DrawScope.drawMarkers(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText, pick: Pick?) {
    val basalInk = basalColor(c.isDark)
    val basalFill = basalContainer(c.isDark)
    s.view.treatments.forEach { tr ->
        val insulin = isInsulin(tr)
        val basal = isBasal(tr)
        // long-acting gets its own colour and letter: it never reads as a meal dose
        val ink = when {
            basal -> basalInk
            insulin -> c.insulin
            else -> c.carbs
        }
        val fill = when {
            basal -> basalFill
            insulin -> c.insulinContainer
            else -> c.carbsContainer
        }
        val at = Offset(s.x(tr.t), s.rowY(tr))
        if (tr.pending) { // not on the server yet: an outline until it is
            drawCircle(c.surface, 10.dp.toPx(), at)
            ringOutline(ink, at)
        } else {
            drawCircle(fill, 10.dp.toPx(), at)
        }
        if ((pick as? Pick.Event)?.treatment == tr) drawCircle(c.text, 10.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        val glyph = when {
            basal -> text.basalGlyph
            insulin -> text.insulinGlyph
            (tr.carbs ?: 0.0) <= 0 -> text.noteGlyph
            else -> text.carbsGlyph
        }
        glyphAt(measurer, glyph, glyphStyle.copy(color = ink), at)
        if (originOf(tr) !is Origin.Here) syncedDot(Offset(at.x + 8.dp.toPx(), at.y - 8.dp.toPx()), c.textMuted, c.surface)
    }
}

/** A planned dose: a dashed ring in the rapid row, at the meal it is for; it is not insulin yet. */
internal fun DrawScope.drawPlanned(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText, planned: List<PlannedDose>) {
    planned.filter { it.state == "planned" }.forEach { p ->
        val t = s.view.treatments.firstOrNull { it.id == p.mealId }?.t ?: p.createdAt
        val at = Offset(s.x(t), s.plotH - RAPID_ROW.toPx())
        drawCircle(c.surface, 10.dp.toPx(), at)
        ringOutline(c.insulin, at)
        glyphAt(measurer, text.insulinGlyph, glyphStyle.copy(color = c.insulin.copy(alpha = 0.7f)), at)
    }
}

/** Activity: a bar along the top, as long as it lasted, darker the harder it was. */
internal fun DrawScope.drawActivities(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText, pick: Pick?) {
    s.view.activities.forEach { a ->
        val x0 = s.x(a.t).coerceAtLeast(0f)
        val x1 = max(x0 + 6.dp.toPx(), s.x(a.t + (a.duration ?: 0) * 60_000L).coerceAtMost(s.plotW))
        val alpha = when {
            a.isShower -> 0.55f
            a.intensity == "vigorous" -> 0.85f
            a.intensity == "moderate" -> 0.55f
            else -> 0.3f
        }
        val top = ACT_TOP.toPx()
        val barSize = Size(x1 - x0, ACT_H.toPx())
        val corner = CornerRadius(ACT_H.toPx() / 2)
        drawRoundRect((if (a.isShower) c.high else c.inRange).copy(alpha = alpha), Offset(x0, top), barSize, corner)
        if ((pick as? Pick.Event)?.treatment == a) {
            drawRoundRect(c.text, Offset(x0, top), barSize, corner, style = Stroke(1.5.dp.toPx()))
        }
        val m = measurer.measure(text.activityGlyph, glyphStyle.copy(color = if (alpha > 0.5f) c.background else c.text, fontSize = 9.sp))
        if (x1 - x0 > m.size.width + 6.dp.toPx()) {
            drawText(m, topLeft = Offset(x0 + 4.dp.toPx(), top + (ACT_H.toPx() - m.size.height) / 2))
        }
        if (originOf(a) !is Origin.Here) syncedDot(Offset(x1, top), c.textMuted, c.surface)
    }
}

/** Suspect marks: nobody answered "did you forget to log?" in time. */
internal fun DrawScope.drawSuspects(s: ChartScale, c: DiaColors, measurer: TextMeasurer, text: ChartText, pick: Pick?) {
    s.view.suspects.forEach { q ->
        val at = Offset(s.x(q.from), s.rowY(q))
        drawCircle(c.surface, 10.dp.toPx(), at)
        ringOutline(c.high, at)
        if ((pick as? Pick.Suspect)?.question == q) drawCircle(c.text, 10.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        glyphAt(measurer, text.suspectGlyph, glyphStyle.copy(color = c.high), at)
    }
}

/** Glucometer readings: a diamond, so they never pass for a sensor dot; over the markers, a low one sits among them. */
internal fun DrawScope.drawMeters(s: ChartScale, c: DiaColors, pick: Pick?) {
    s.view.meters.forEach { m ->
        val cx = s.x(m.t)
        val cy = s.y(m.v)
        fun diamond(half: Float) = Path().apply {
            moveTo(cx, cy - half)
            lineTo(cx + half, cy)
            lineTo(cx, cy + half)
            lineTo(cx - half, cy)
            close()
        }
        drawPath(diamond(9.dp.toPx()), c.background)
        if (m.treatment?.pending == true) {
            drawPath(diamond(7.dp.toPx()), c.text, style = Stroke(1.5.dp.toPx(), pathEffect = dashed(3f, 3f)))
        } else {
            drawPath(diamond(7.dp.toPx()), c.text)
        }
        drawPath(diamond(3.5.dp.toPx()), colorFor(m.v, s.view.thresholds, c.low, c.inRange, c.high))
        if ((pick as? Pick.Meter)?.point == m) drawPath(diamond(11.dp.toPx()), c.text, style = Stroke(1.5.dp.toPx()))
        if (m.origin !is Origin.Here) syncedDot(Offset(cx + 7.dp.toPx(), cy - 7.dp.toPx()), c.textMuted, c.surface)
    }
}
