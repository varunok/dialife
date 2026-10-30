package com.lumenarcanus.dialife.feature.home

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Treatment

/**
 * Where a time and a value land on the canvas. The gestures and every drawing
 * layer share it, so a tap finds exactly what was drawn under the finger.
 */
internal class ChartScale(width: Float, height: Float, density: Density, val view: ChartView) : Density by density {
    val plotW = width - 34.dp.toPx() // the threshold labels sit to the right
    val plotH = height - 24.dp.toPx() // the hour labels sit below

    fun x(t: Long) = (t - view.start).toFloat() / (view.end - view.start) * plotW

    fun y(v: Number) = plotH - (v.toFloat() - view.lo) / (view.hi - view.lo) * plotH

    fun rowY(tr: Treatment) = plotH - markerRow(tr).toPx()

    fun rowY(q: Question) = plotH - suspectRow(q).toPx()
}

internal fun isInsulin(tr: Treatment) = (tr.insulin ?: 0.0) > 0

internal fun isBasal(tr: Treatment) = isInsulin(tr) && InsulinType.of(tr.insulinType) == InsulinType.Basal

/** Three rows along the bottom, so a dose given minutes after another stays visible: carbs, rapid, long. */
internal fun markerRow(tr: Treatment): Dp = when {
    isBasal(tr) -> 48.dp
    isInsulin(tr) -> 30.dp
    else -> 12.dp
}

/** A rise sits in the carbs row, every other question in the rapid row. */
internal fun suspectRow(q: Question): Dp = if (q.kind == "rise") 12.dp else 30.dp

internal val RAPID_ROW = 30.dp
