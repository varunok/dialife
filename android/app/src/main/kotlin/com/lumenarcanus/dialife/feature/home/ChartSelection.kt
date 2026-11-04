package com.lumenarcanus.dialife.feature.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatDelta
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaColors
import com.lumenarcanus.dialife.data.MeterModel
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Treatment
import kotlin.math.abs
import kotlin.math.round

/**
 * What is picked, told over the chart: a crosshair and a tooltip for a reading,
 * a tooltip for a glucometer check or a suspect mark, and for an entry only
 * when [entryTooltip] (full screen); otherwise the card under the chart tells it.
 */
internal fun DrawScope.drawSelection(
    s: ChartScale,
    c: DiaColors,
    measurer: TextMeasurer,
    text: ChartText,
    pick: Pick?,
    estimate: MeterModel?,
    entryTooltip: Boolean
) {
    fun tip(lines: List<String>, ax: Float, ay: Float, border: Color) =
        tooltip(measurer, lines, ax, ay, s.plotW, s.plotH, c.background, border, c.text, c.textMuted)

    when (pick) {
        is Pick.Reading -> if (pick.index in s.view.ts.indices) {
            val px = s.x(s.view.ts[pick.index])
            val v = s.view.vs[pick.index]
            val col = colorFor(v, s.view.thresholds, c.low, c.inRange, c.high)
            drawLine(c.textMuted, Offset(px, 0f), Offset(px, s.plotH), 1.dp.toPx())
            drawCircle(c.background, 8.dp.toPx(), Offset(px, s.y(v)))
            drawCircle(col, 6.dp.toPx(), Offset(px, s.y(v)))
            tip(readingLines(s.view, pick.index, estimate, text), px, s.y(v), if (band(v, s.view.thresholds) != 0) col else c.line)
        }
        is Pick.Meter -> tip(meterLines(s.view, pick.point, text), s.x(pick.point.t), s.y(pick.point.v), c.line)
        is Pick.Suspect -> tip(suspectLines(pick.question, text), s.x(pick.question.from), s.plotH - 40.dp.toPx(), c.high)
        is Pick.Event -> if (entryTooltip) {
            val tr = pick.treatment
            val ty = if (tr.duration != null) ACT_TOP.toPx() + ACT_H.toPx() else s.plotH - 40.dp.toPx()
            tip(entryLines(tr, text), s.x(tr.t), ty, c.line)
        }
        null -> {}
    }
}

private fun readingLines(view: ChartView, i: Int, estimate: MeterModel?, text: ChartText): List<String> {
    val v = view.vs[i]
    val locale = text.locale
    val delta = previous(view.ts, view.vs, i)?.let { formatDelta(v - it, text.units, locale) }
    val at = text.clock(view.ts[i])
    return listOfNotNull(
        formatGlucose(v, text.units, locale) + " " + (if (text.units == Units.MgDl) "mg/dL" else ""),
        estimate?.estimate(v)?.let { (e, sd) ->
            text.meterEstimate.format(locale, formatGlucose(e, text.units, locale), formatGlucose(sd, text.units, locale))
        },
        delta?.let { "$it · $at" } ?: at
    )
}

private fun meterLines(view: ChartView, m: MeterPoint, text: ChartText): List<String> {
    val locale = text.locale
    // the sensor at that moment, to see how far apart the two are
    val near = view.ts.indices.minByOrNull { abs(view.ts[it] - m.t) }?.takeIf { abs(view.ts[it] - m.t) <= 6 * 60_000L }
    val unit = if (text.units == Units.MgDl) " mg/dL" else ""
    return listOfNotNull(
        text.meterLabel.format(locale, formatGlucose(m.v, text.units, locale) + unit),
        listOfNotNull(
            text.clock(m.t),
            near?.let { text.meterSensor.format(locale, formatGlucose(view.vs[it], text.units, locale)) },
            text.originText(m.origin),
            text.pendingLabel.takeIf { m.treatment?.pending == true }
        ).joinToString(" · "),
        m.treatment?.notes?.takeIf { it.isNotBlank() }
    )
}

private fun suspectLines(q: Question, text: ChartText): List<String> {
    val locale = text.locale
    val what = when (q.kind) {
        "double" -> text.suspectDouble.format(locale, text.doubleInsulin(q), text.gap(q.to - q.from))
        "bolus" -> text.suspectBolus.format(locale, text.clock(q.from))
        else -> (if (q.kind == "rise") text.suspectRise else text.suspectFall)
            .format(locale, formatGlucose(q.sgvFrom, text.units, locale), formatGlucose(q.sgvTo, text.units, locale))
    }
    return listOf(what, text.clock(q.from) + " · " + text.suspectHint)
}

private fun entryLines(tr: Treatment, text: ChartText): List<String> {
    val locale = text.locale
    val amounts = listOfNotNull(
        tr.insulin?.takeIf { it > 0 }?.let { u ->
            // "Lantus 10 од" when the brand is known, else "Інсулін 10 од"
            tr.insulinName?.let { "$it " + String.format(locale, "%s %s", formatAmount(u, locale), text.unitInsulin) }
                ?: text.insulinLabel.format(locale, u)
        },
        tr.carbs?.takeIf { it > 0 }?.let { text.carbsLabel.format(locale, it.toInt()) }
    ).joinToString(" · ")
    val what = tr.duration?.let { d ->
        if (tr.isShower) text.showerLabel.format(locale, d) else text.activityLabel.format(locale, text.intensityLabel(tr.intensity), d)
    }
        ?: amounts.ifBlank { tr.notes ?: text.noteLabel }
    val dishes = tr.foodItems.orEmpty().map { f ->
        listOfNotNull(f.name, f.portion?.takeIf { it.isNotBlank() }).joinToString(", ") +
            (f.carbs?.let { " — " + (if (f.suggested) "≈ " else "") + round(it).toInt() + " ${text.unitCarbs}" } ?: "")
    }
    return listOfNotNull(what) + dishes + listOfNotNull(
        text.corrLabel.takeIf { tr.insulinRole == "corr" && (tr.insulin ?: 0.0) > 0 },
        tr.notes?.takeIf { amounts.isNotBlank() && it.isNotBlank() },
        listOfNotNull(text.clock(tr.t), text.originText(originOf(tr)), text.pendingLabel.takeIf { tr.pending }).joinToString(" · ")
    )
}
