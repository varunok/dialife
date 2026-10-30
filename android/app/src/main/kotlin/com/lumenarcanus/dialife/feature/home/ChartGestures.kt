package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import com.lumenarcanus.dialife.data.Treatment
import kotlin.math.abs

/**
 * Taps pick what is under the finger (a glucometer diamond, a suspect mark, a
 * marker, else the nearest reading; tapped again it is let go), a sideways drag
 * scrubs the readings, two fingers pinch and pan the window. The gesture loop
 * outlives recompositions, so it reads the current view and pick through [view]
 * and [pick].
 */
internal suspend fun PointerInputScope.chartGestures(
    view: () -> ChartView,
    pick: () -> Pick?,
    onPick: (Pick?) -> Unit,
    onWindow: (ChartWindow) -> Unit,
    haptics: HapticFeedback
) {
    fun scale() = ChartScale(size.width.toFloat(), size.height.toFloat(), this, view())

    fun nearestReading(s: ChartScale, x: Float): Int? = s.view.visible.minByOrNull { abs(s.x(s.view.ts[it]) - x) }

    // a glucometer diamond under the finger: a bigger target than one sensor dot
    fun meterAt(s: ChartScale, o: Offset): MeterPoint? = s.view.meters
        .filter { abs(s.x(it.t) - o.x) < 16.dp.toPx() && abs(s.y(it.v) - o.y) < 16.dp.toPx() }
        .minByOrNull { abs(s.x(it.t) - o.x) + abs(s.y(it.v) - o.y) }

    // the marker under the finger: same row, nearest in time; an activity bar along the top
    fun markerAt(s: ChartScale, o: Offset): Treatment? = s.view.treatments
        .filter { abs(s.x(it.t) - o.x) < 18.dp.toPx() && abs(s.rowY(it) - o.y) < 11.dp.toPx() }
        .minByOrNull { abs(s.x(it.t) - o.x) }
        ?: s.view.activities.firstOrNull {
            o.y < ACT_TOP.toPx() + ACT_H.toPx() + 6.dp.toPx() &&
                o.x >= s.x(it.t) - 8.dp.toPx() &&
                o.x <= s.x(it.t + (it.duration ?: 0) * 60_000L) + 8.dp.toPx()
        }

    fun suspectAt(s: ChartScale, o: Offset) = s.view.suspects.firstOrNull {
        abs(s.x(it.from) - o.x) < 16.dp.toPx() && abs(s.rowY(it) - o.y) < 12.dp.toPx()
    }

    awaitEachGesture {
        val down = awaitFirstDown()
        val before = pick()
        val atDown = scale()
        var last = nearestReading(atDown, down.position.x)
        var pinching = false
        var scrubbing = false // decided only after the finger moves past the touch slop
        val meter = meterAt(atDown, down.position)
        val mark = if (meter == null) suspectAt(atDown, down.position) else null
        val marker = if (meter == null && mark == null) markerAt(atDown, down.position) else null
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val s = scale()
            if (pressed.size >= 2) {
                // pinch to zoom around the fingers, two-finger drag to pan
                pinching = true
                onPick(null)
                val zoom = ev.calculateZoom()
                val pan = ev.calculatePan().x
                val cx = ev.calculateCentroid().x
                val span = s.view.end - s.view.start
                val newSpan = (span / zoom).toLong().coerceIn(MIN_SPAN, MAX_SPAN)
                val anchor = s.view.start + (cx / s.plotW * span).toLong()
                val maxEnd = s.view.now + FORECAST_SPAN
                val newStart = (anchor - ((cx + pan) / s.plotW * newSpan).toLong())
                    .coerceIn(maxEnd - MAX_SPAN, maxEnd - newSpan)
                onWindow(ChartWindow(newStart, newStart + newSpan, followsNow = newStart + newSpan >= maxEnd))
                ev.changes.forEach { it.consume() }
                continue
            }
            if (pinching || marker != null || meter != null || mark != null) continue
            if (!scrubbing) {
                val d = pressed[0].position - down.position
                if (d.getDistance() < viewConfiguration.touchSlop) continue
                // mostly vertical: it is the page being scrolled, not the chart
                if (abs(d.y) > abs(d.x)) return@awaitEachGesture
                scrubbing = true
                onPick(last?.let { Pick.Reading(it) })
            }
            val i = nearestReading(s, pressed[0].position.x)
            if (i != null && i != last) {
                // a light tick when the crosshair crosses a threshold
                val vs = s.view.vs
                val crossed = last?.let { band(vs[it], s.view.thresholds) != band(vs[i], s.view.thresholds) } ?: false
                if (crossed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                last = i
                onPick(Pick.Reading(i))
            }
            ev.changes.forEach { it.consume() }
        }
        when {
            pinching || scrubbing -> {}
            meter != null -> onPick(if ((before as? Pick.Meter)?.point == meter) null else Pick.Meter(meter))
            mark != null -> onPick(if ((before as? Pick.Suspect)?.question == mark) null else Pick.Suspect(mark))
            marker != null -> onPick(if ((before as? Pick.Event)?.treatment == marker) null else Pick.Event(marker))
            // a tap: show that point, or hide it when tapped again
            before is Pick.Reading && before.index == last -> onPick(null)
            else -> onPick(last?.let { Pick.Reading(it) })
        }
    }
}
