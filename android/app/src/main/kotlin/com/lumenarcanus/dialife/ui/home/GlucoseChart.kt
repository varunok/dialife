package com.lumenarcanus.dialife.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.Forecast
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.Onest
import com.lumenarcanus.dialife.ui.theme.PlexMono
import java.util.Date
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

val ChartRanges = listOf(3, 6, 12, 24)
private const val HOUR = 3_600_000L
private const val FORECAST_SPAN = 30 * 60_000L
private const val MIN_SPAN = HOUR // pinch in no further than an hour
private val ACT_TOP = 4.dp // activity bars run along the top of the plot
private val ACT_H = 12.dp
private const val MAX_SPAN = 24 * HOUR + FORECAST_SPAN // the history the screen holds

/**
 * A pinched or panned time window; null means "the chosen range, ending now".
 * A window pushed against "now" keeps following it as new readings arrive.
 */
data class ChartWindow(val start: Long, val end: Long, val followsNow: Boolean = false) {
    fun at(now: Long): ChartWindow =
        if (followsNow) ChartWindow(now + FORECAST_SPAN - (end - start), now + FORECAST_SPAN, true) else this
}

private sealed interface Pick {
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
private data class MeterPoint(val t: Long, val v: Int, val treatment: Treatment? = null, val src: String? = null) {
    val origin: Origin
        get() = when {
            treatment != null -> originOf(treatment)
            src.isNullOrBlank() || src == "manual" -> Origin.XDrip
            else -> Origin.Device(src.substringBefore(' ')) // "Roche 00:00:5E:…": the brand is enough
        }
}

@Composable
fun ChartCard(
    history: History?,
    forecast: Forecast?,
    thresholds: Thresholds,
    units: Units,
    rangeHours: Int,
    now: Long,
    onRange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (Treatment) -> Unit = {},
    onDelete: (Treatment) -> Unit = {},
    highlightFrom: Long? = null, // a live episode: shade it from its start to now
    expanded: Boolean = false, // the whole screen: the chart takes all the height left
    onExpand: () -> Unit = {}, // the corner button: open full screen, or close it when expanded
    meter: com.lumenarcanus.dialife.data.MeterReport? = null, // sensor vs glucometer: the "≈ meter" switch
    meterLine: Boolean = com.lumenarcanus.dialife.data.Prefs.meterLine,
    suspects: List<com.lumenarcanus.dialife.data.Question> = emptyList(), // unanswered questions: "?" marks
    onDismissSuspect: (Long) -> Unit = {},
    onOpenTable: (() -> Unit)? = null, // the same data as a table
    dose: com.lumenarcanus.dialife.data.DoseSettings = com.lumenarcanus.dialife.data.DoseSettings(), // for a meal's worked-out dose
    planned: List<com.lumenarcanus.dialife.data.PlannedDose> = emptyList(),
    mealGap: Int = com.lumenarcanus.dialife.data.Meals.DEFAULT_GAP_MIN, // the server's: food entries this close are one meal
) {
    val c = DiaTheme.colors
    val context = LocalContext.current
    var window by remember { mutableStateOf<ChartWindow?>(null) }
    var selected by remember { mutableStateOf<Treatment?>(null) }
    var suspect by remember { mutableStateOf<com.lumenarcanus.dialife.data.Question?>(null) }
    var whyNot by remember { mutableStateOf(false) } // the switch tapped before there is enough data
    val estimate = meter?.model?.takeIf { meter.ready && meterLine }
    Column(
        modifier
            .background(c.surface, RoundedCornerShape(22.dp))
            .border(1.dp, c.line, RoundedCornerShape(22.dp))
            .padding(start = 14.dp, end = 14.dp, top = if (expanded) 8.dp else 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (expanded) 6.dp else 10.dp),
    ) {
        val title = window?.at(now)?.let { w ->
            val f = android.text.format.DateFormat.getTimeFormat(context)
            stringResource(R.string.chart_window, f.format(Date(w.start)), f.format(Date(min(w.end, now))))
        } ?: stringResource(R.string.chart_last_hours, rangeHours)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            // wide enough when expanded (landscape): the ranges share the title's row
            if (expanded) RangeTabs(window == null, rangeHours, Modifier.width(320.dp)) { window = null; onRange(it) }
            MeterSwitch(on = estimate != null, ready = meter?.ready == true) {
                if (meter?.ready == true) {
                    com.lumenarcanus.dialife.data.Prefs.chooseMeterLine(!com.lumenarcanus.dialife.data.Prefs.meterLine)
                } else whyNot = !whyNot
            }
            onOpenTable?.let { open ->
                androidx.compose.material3.IconButton(onClick = open, modifier = Modifier.size(40.dp)) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_table), stringResource(R.string.chart_table), tint = c.textMuted)
                }
            }
            val what = stringResource(if (expanded) R.string.chart_close else R.string.chart_expand)
            androidx.compose.material3.IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(if (expanded) R.drawable.ic_close else R.drawable.ic_fullscreen),
                    contentDescription = what, tint = c.textMuted,
                )
            }
        }
        if (whyNot && meter?.ready != true) {
            val used = meter?.used ?: 0
            val need = meter?.need ?: 8
            Text(
                stringResource(R.string.meter_not_ready, used, need, (need - used).coerceAtLeast(1)),
                color = c.textMuted, fontSize = 12.5.sp,
                modifier = Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        // own full-width row: "Останні 24 год" plus four "N год" chips do not fit side by side
        if (!expanded) RangeTabs(window == null, rangeHours, Modifier.fillMaxWidth()) { window = null; onRange(it) }
        GlucoseChart(
            history, forecast, thresholds, units, rangeHours, now,
            window = window, onWindow = { window = it },
            onEventPicked = { selected = it },
            suspects = suspects,
            onSuspectPicked = { suspect = it },
            highlightFrom = highlightFrom,
            estimate = estimate,
            planned = planned,
            entryTooltip = expanded,
            modifier = if (expanded) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(230.dp),
        )
        // the tapped marker's actions: the canvas tooltip says what it is, these say what to do
        // a suspect mark: anyone may clear it, no questions asked
        suspect?.let { q ->
            androidx.compose.material3.OutlinedButton(onClick = { suspect = null; onDismissSuspect(q.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.q_dismiss), color = c.text)
            }
        }
        // full screen: the entry says what it is over the chart, like a reading; no editing there
        selected?.takeIf { !expanded }?.let { tr ->
            com.lumenarcanus.dialife.ui.meal.EntryDetail(tr, history?.treatments.orEmpty(), dose, units, mealGap)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedButton(onClick = { onEdit(tr) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tr_edit), color = c.text)
                }
                androidx.compose.material3.OutlinedButton(onClick = { selected = null; onDelete(tr) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tr_delete), color = c.low)
                }
            }
        }
    }
}

/** "≈ Meter": filled when the line is on, faded while there are too few checks for it. */
@Composable
private fun MeterSwitch(on: Boolean, ready: Boolean, onClick: () -> Unit) {
    val c = DiaTheme.colors
    val ink = if (on) meterInk(c.isDark) else c.textMuted
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .background(if (on) c.insulinContainer else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, if (on) Color.Transparent else c.line, RoundedCornerShape(50))
            .selectable(selected = on, role = Role.Switch, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .then(if (ready) Modifier else Modifier.alpha(0.55f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(10.dp)) {
            val d = Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height / 2); lineTo(size.width / 2, size.height); lineTo(0f, size.height / 2); close() }
            if (on) drawPath(d, ink) else drawPath(d, ink, style = Stroke(1.4.dp.toPx()))
        }
        Text(stringResource(R.string.meter_toggle), color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The estimate's own ink: the insulin violet, lighter, so it never reads as a sensor line. */
fun meterInk(dark: Boolean) = if (dark) Color(0xFFE8D9FF) else Color(0xFF5B3FD1)

@Composable
private fun RangeTabs(followsRange: Boolean, rangeHours: Int, modifier: Modifier, onRange: (Int) -> Unit) {
    val c = DiaTheme.colors
    Row(
        modifier.background(c.background, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChartRanges.forEach { h ->
            val selected = followsRange && h == rangeHours
            Text(
                stringResource(R.string.chart_range, h),
                color = if (selected) c.text else c.textMuted,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onRange(h) })
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 4.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
fun GlucoseChart(
    history: History?,
    forecast: Forecast?,
    thresholds: Thresholds,
    units: Units,
    rangeHours: Int,
    now: Long,
    window: ChartWindow? = null,
    onWindow: (ChartWindow) -> Unit = {},
    onEventPicked: (Treatment?) -> Unit = {},
    suspects: List<com.lumenarcanus.dialife.data.Question> = emptyList(),
    onSuspectPicked: (com.lumenarcanus.dialife.data.Question?) -> Unit = {},
    highlightFrom: Long? = null,
    estimate: com.lumenarcanus.dialife.data.MeterModel? = null, // draw "≈ meter" from it; null: off
    planned: List<com.lumenarcanus.dialife.data.PlannedDose> = emptyList(), // not given yet: a dashed mark
    entryTooltip: Boolean = false, // a tapped entry is told over the chart; false: the card under it does
    modifier: Modifier = Modifier,
) {
    val c = DiaTheme.colors
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val timeFormat = remember(locale) { android.text.format.DateFormat.getTimeFormat(context) }
    fun clock(t: Long): String = timeFormat.format(Date(t))

    val w = window?.at(now)
    val start = w?.start ?: (now - rangeHours * HOUR)
    val end = w?.end ?: (now + FORECAST_SPAN)
    val spanHours = (end - start).toFloat() / HOUR
    val ts = history?.sgv?.t.orEmpty()
    val vs = history?.sgv?.v.orEmpty()
    val visible = ts.indices.filter { ts[it] in start..min(end, now) }
    val inView = history?.treatments.orEmpty().filter { it.t in start..min(end, now) }
    // glucometer checks sit on the glucose scale; everything else is a marker along the bottom
    val treatments = inView.filter { it.glucose == null && it.duration == null }
    // activity lasts: one that began before the window still shows its part
    val activities = history?.treatments.orEmpty().filter { a -> a.duration != null && a.t <= min(end, now) && a.t + a.duration * 60_000L >= start }
    val marks = suspects.filter { it.state == "unknown" && it.from in start..min(end, now) }
    val mbg = history?.mbg ?: com.lumenarcanus.dialife.data.Series()
    val meters = inView.mapNotNull { tr -> tr.glucose?.let { MeterPoint(tr.t, it, tr) } } +
        mbg.t.indices.filter { mbg.t[it] in start..min(end, now) }.map { MeterPoint(mbg.t[it], mbg.v[it], src = mbg.d.getOrNull(it)) }
    val maxV = max(visible.maxOfOrNull { vs[it] } ?: 0, meters.maxOfOrNull { it.v } ?: 0)
    val lo = 40f
    val hi = max(300, min(400, maxV + 20)).toFloat()

    val insulinGlyph = stringResource(R.string.glyph_insulin)
    val carbsGlyph = stringResource(R.string.glyph_carbs)
    val a11y = if (visible.isEmpty()) stringResource(R.string.chart_a11y_empty, rangeHours) else stringResource(
        R.string.chart_a11y, rangeHours,
        formatGlucose(vs[visible.first()], units, locale), formatGlucose(vs[visible.last()], units, locale),
    )
    val insulinLabel = stringResource(R.string.event_insulin)
    val carbsLabel = stringResource(R.string.event_carbs)
    val noteLabel = stringResource(R.string.tr_note)
    val corrLabel = stringResource(R.string.detail_for_corr)
    val unitCarbs = stringResource(R.string.unit_carbs)
    val pendingLabel = stringResource(R.string.tr_pending)
    val noteGlyph = stringResource(R.string.glyph_note)
    val basalGlyph = stringResource(R.string.glyph_basal)
    val unitInsulin = stringResource(R.string.unit_insulin)
    val basalInk = com.lumenarcanus.dialife.ui.treatment.basalColor(c.isDark)
    val basalFill = com.lumenarcanus.dialife.ui.treatment.basalContainer(c.isDark)
    val meterLabel = stringResource(R.string.meter_label)
    val meterSensor = stringResource(R.string.meter_sensor)
    val meterEstimate = stringResource(R.string.meter_estimate)
    val suspectRise = stringResource(R.string.suspect_rise)
    val suspectFall = stringResource(R.string.suspect_fall)
    val suspectBolus = stringResource(R.string.suspect_bolus)
    val suspectDouble = stringResource(R.string.suspect_double)
    val suspectHint = stringResource(R.string.suspect_hint)
    val suspectGlyph = stringResource(R.string.glyph_suspect)
    val activityGlyph = stringResource(R.string.glyph_activity)
    val activityLabel = stringResource(R.string.event_activity)
    val intensities = mapOf(
        "light" to stringResource(R.string.act_light), "moderate" to stringResource(R.string.act_moderate), "vigorous" to stringResource(R.string.act_vigorous),
    )
    fun intensityLabel(i: String?) = intensities[i] ?: intensities.getValue("moderate")
    val typedBy = stringResource(R.string.origin_typed)
    val autoBy = stringResource(R.string.origin_auto)
    fun originText(o: Origin): String? = when (o) {
        is Origin.Here -> o.who.takeIf { it.isNotBlank() }?.let { typedBy.format(locale, it) }
        Origin.XDrip -> typedBy.format(locale, "xDrip")
        is Origin.Device -> autoBy.format(locale, o.name)
    }

    var pick by remember(history, rangeHours) { mutableStateOf<Pick?>(null) }
    androidx.compose.runtime.LaunchedEffect(pick) { onSuspectPicked((pick as? Pick.Suspect)?.question) }
    androidx.compose.runtime.LaunchedEffect(pick) {
        onEventPicked((pick as? Pick.Event)?.treatment ?: (pick as? Pick.Meter)?.point?.treatment)
    }
    // the gesture loop outlives recompositions; it reads the current view through these
    val view by rememberUpdatedState(ChartView(start, end, now, visible, treatments, thresholds, meters, lo, hi, activities, marks))

    Canvas(
        modifier
            .semantics { contentDescription = a11y }
            .pointerInput(history, rangeHours) { // same keys as `pick`, or taps write to a forgotten state
                val labelW = 34.dp.toPx()
                val plotW = size.width - labelW
                val plotH = size.height - 24.dp.toPx()
                fun xOf(t: Long) = (t - view.start).toFloat() / (view.end - view.start) * plotW
                fun nearestReading(x: Float): Int? = view.visible.minByOrNull { abs(xOf(ts[it]) - x) }
                fun yOf(v: Int) = plotH - (v - view.lo) / (view.hi - view.lo) * plotH
                // a glucometer diamond under the finger: a bigger target than one sensor dot
                fun meterAt(o: Offset): MeterPoint? = view.meters
                    .filter { abs(xOf(it.t) - o.x) < 16.dp.toPx() && abs(yOf(it.v) - o.y) < 16.dp.toPx() }
                    .minByOrNull { abs(xOf(it.t) - o.x) + abs(yOf(it.v) - o.y) }
                fun rowY(t: Treatment): Float {
                    val ins = (t.insulin ?: 0.0) > 0
                    val basal = ins && com.lumenarcanus.dialife.data.InsulinType.of(t.insulinType) == com.lumenarcanus.dialife.data.InsulinType.Basal
                    return plotH - if (basal) 48.dp.toPx() else if (ins) 30.dp.toPx() else 12.dp.toPx()
                }
                // the marker under the finger: same row, nearest in time; an activity bar along the top
                fun markerAt(o: Offset): Treatment? = view.treatments
                    .filter { abs(xOf(it.t) - o.x) < 18.dp.toPx() && abs(rowY(it) - o.y) < 11.dp.toPx() }
                    .minByOrNull { abs(xOf(it.t) - o.x) }
                    ?: view.activities.firstOrNull {
                        o.y < ACT_TOP.toPx() + ACT_H.toPx() + 6.dp.toPx() &&
                            o.x >= xOf(it.t) - 8.dp.toPx() && o.x <= xOf(it.t + (it.duration ?: 0) * 60_000L) + 8.dp.toPx()
                    }
                fun suspectAt(o: Offset) = view.suspects.firstOrNull {
                    abs(xOf(it.from) - o.x) < 16.dp.toPx() && abs(plotH - (if (it.kind == "rise") 12.dp else 30.dp).toPx() - o.y) < 12.dp.toPx()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val before = pick
                    var last = nearestReading(down.position.x)
                    var moved = false
                    var pinching = false
                    var scrubbing = false // decided only after the finger moves past the touch slop
                    val meter = meterAt(down.position)
                    val mark = if (meter == null) suspectAt(down.position) else null
                    val marker = if (meter == null && mark == null) markerAt(down.position) else null
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // pinch to zoom around the fingers, two-finger drag to pan
                            pinching = true
                            pick = null
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan().x
                            val cx = ev.calculateCentroid().x
                            val span = view.end - view.start
                            val newSpan = (span / zoom).toLong().coerceIn(MIN_SPAN, MAX_SPAN)
                            val anchor = view.start + (cx / plotW * span).toLong()
                            val maxEnd = view.now + FORECAST_SPAN
                            val newStart = (anchor - ((cx + pan) / plotW * newSpan).toLong())
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
                            pick = last?.let { Pick.Reading(it) }
                        }
                        val i = nearestReading(pressed[0].position.x)
                        if (i != null && i != last) {
                            moved = true
                            // a light tick when the crosshair crosses a threshold
                            val crossed = last?.let { band(vs[it], view.thresholds) != band(vs[i], view.thresholds) } ?: false
                            if (crossed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            last = i
                            pick = Pick.Reading(i)
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    when {
                        pinching || scrubbing -> {}
                        meter != null -> pick = if ((before as? Pick.Meter)?.point == meter) null else Pick.Meter(meter)
                        mark != null -> pick = if ((before as? Pick.Suspect)?.question == mark) null else Pick.Suspect(mark)
                        marker != null -> pick = if ((before as? Pick.Event)?.treatment == marker) null else Pick.Event(marker)
                        // a tap: show that point, or hide it when tapped again
                        before is Pick.Reading && before.index == last -> pick = null
                        else -> pick = last?.let { Pick.Reading(it) }
                    }

                }
            },
    ) {
        val labelW = 34.dp.toPx()
        val plotW = size.width - labelW
        val plotH = size.height - 24.dp.toPx()
        fun x(t: Long) = (t - start).toFloat() / (end - start) * plotW
        fun y(v: Number) = plotH - (v.toFloat() - lo) / (hi - lo) * plotH
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
        val label = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = c.textMuted)

        highlightFrom?.let { from ->
            val hx = x(from).coerceIn(0f, plotW)
            drawRect(c.high.copy(alpha = 0.10f), Offset(hx, 0f), Size((x(now).coerceIn(0f, plotW) - hx).coerceAtLeast(0f), plotH))
        }
        // target band and threshold lines
        val nowX = x(now)
        drawRect(c.inRange.copy(alpha = 0.07f), Offset(0f, y(thresholds.high)), Size(nowX.coerceIn(0f, plotW), y(thresholds.low) - y(thresholds.high)))
        drawLine(c.high.copy(alpha = 0.5f), Offset(0f, y(thresholds.high)), Offset(plotW, y(thresholds.high)), 1.dp.toPx(), pathEffect = dash)
        drawLine(c.low.copy(alpha = 0.55f), Offset(0f, y(thresholds.low)), Offset(plotW, y(thresholds.low)), 1.dp.toPx(), pathEffect = dash)
        for (v in listOf(thresholds.high, thresholds.low)) {
            val m = measurer.measure(formatGlucose(v, units, locale), label)
            drawText(m, topLeft = Offset(size.width - m.size.width, y(v) - m.size.height / 2f))
        }

        // hour ticks on local wall-clock boundaries
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
        while (tick <= min(now, end)) {
            val tx = x(tick)
            drawLine(c.line, Offset(tx, 0f), Offset(tx, plotH), 1.dp.toPx())
            val m = measurer.measure(clock(tick), label.copy(color = c.textFaint))
            val left = (tx - m.size.width / 2f).coerceIn(0f, plotW - m.size.width)
            if (left >= labelEnd + 4.dp.toPx()) {
                drawText(m, topLeft = Offset(left, plotH + 6.dp.toPx()))
                labelEnd = left + m.size.width
            }
            tick += step
        }

        // everything time-based stays inside the plot when the window is pinched into the past
        clipRect(right = plotW, bottom = plotH + 1f) {
        // forecast: cone and line from the last reading
        forecast?.takeIf { it.cone.isNotEmpty() && visible.isNotEmpty() && visible.last() == ts.lastIndex }?.let { f ->
            val lastT = ts[visible.last()]
            val lastV = vs[visible.last()]
            val cone = Path().apply {
                moveTo(x(lastT), y(lastV))
                f.cone.forEach { lineTo(x(it.t).coerceAtMost(plotW), y(it.hi.coerceAtMost(hi.toInt()))) }
                f.cone.asReversed().forEach { lineTo(x(it.t).coerceAtMost(plotW), y(it.lo.coerceAtLeast(lo.toInt()))) }
                close()
            }
            drawPath(cone, c.inRange.copy(alpha = 0.12f))
            val line = Path().apply {
                moveTo(x(lastT), y(lastV))
                f.predicted.forEach { lineTo(x(it.t), y(it.sgv)) }
            }
            drawPath(line, c.inRange.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))))
        }
        drawLine(c.textFaint, Offset(nowX, 0f), Offset(nowX, plotH), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))

        // "≈ meter": a faint band and a dashed line under the sensor dots, broken where the sensor is
        estimate?.let { m ->
            val ink = meterInk(c.isDark)
            var run = mutableListOf<Int>()
            fun flushRun() {
                if (run.size >= 2) {
                    val band = Path().apply {
                        run.forEachIndexed { k, i -> val (e, s) = m.estimate(vs[i]); val px = x(ts[i]); val py = y((e + s).coerceAtMost(hi.toInt())); if (k == 0) moveTo(px, py) else lineTo(px, py) }
                        run.asReversed().forEach { i -> val (e, s) = m.estimate(vs[i]); lineTo(x(ts[i]), y((e - s).coerceAtLeast(lo.toInt()))) }
                        close()
                    }
                    drawPath(band, ink.copy(alpha = 0.10f))
                    val line = Path().apply {
                        run.forEachIndexed { k, i -> val e = m.estimate(vs[i]).first.coerceIn(lo.toInt(), hi.toInt()); if (k == 0) moveTo(x(ts[i]), y(e)) else lineTo(x(ts[i]), y(e)) }
                    }
                    drawPath(line, ink.copy(alpha = 0.75f), style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                }
                run = mutableListOf()
            }
            visible.forEach { i ->
                if (run.isNotEmpty() && ts[i] - ts[run.last()] > 12 * 60_000L) flushRun()
                run.add(i)
            }
            flushRun()
        }

        // readings
        val r = when {
            spanHours <= 3.6f -> 3.2f
            spanHours <= 7f -> 2.4f
            spanHours <= 13f -> 1.8f
            else -> 1.4f
        }.dp.toPx()
        visible.forEach { i -> drawCircle(colorFor(vs[i], thresholds, c.low, c.inRange, c.high), r, Offset(x(ts[i]), y(vs[i]))) }
        visible.lastOrNull()?.takeIf { it == ts.lastIndex }?.let { i -> // ring only on the current reading
            drawCircle(c.background, r + 4.dp.toPx(), Offset(x(ts[i]), y(vs[i])))
            drawCircle(colorFor(vs[i], thresholds, c.low, c.inRange, c.high), r + 2.dp.toPx(), Offset(x(ts[i]), y(vs[i])))
        }

        // treatment markers along the bottom
        val glyph = TextStyle(fontFamily = Onest, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        treatments.forEach { tr ->
            val isInsulin = (tr.insulin ?: 0.0) > 0
            val isBasal = isInsulin && com.lumenarcanus.dialife.data.InsulinType.of(tr.insulinType) == com.lumenarcanus.dialife.data.InsulinType.Basal
            // long-acting gets its own colour and letter: it never reads as a meal dose
            val ink = if (isBasal) basalInk else if (isInsulin) c.insulin else c.carbs
            val fill = if (isBasal) basalFill else if (isInsulin) c.insulinContainer else c.carbsContainer
            // three rows, so a dose given minutes after another stays visible: carbs, rapid, long
            val cy = plotH - if (isBasal) 48.dp.toPx() else if (isInsulin) 30.dp.toPx() else 12.dp.toPx()
            val selected = (pick as? Pick.Event)?.treatment == tr
            if (tr.pending) { // not on the server yet: an outline until it is
                drawCircle(c.surface, 10.dp.toPx(), Offset(x(tr.t), cy))
                drawCircle(ink, 9.dp.toPx(), Offset(x(tr.t), cy), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
            } else {
                drawCircle(fill, 10.dp.toPx(), Offset(x(tr.t), cy))
            }
            if (selected) drawCircle(c.text, 10.dp.toPx(), Offset(x(tr.t), cy), style = Stroke(2.dp.toPx()))
            val isNote = !isInsulin && (tr.carbs ?: 0.0) <= 0
            val m = measurer.measure(if (isBasal) basalGlyph else if (isInsulin) insulinGlyph else if (isNote) noteGlyph else carbsGlyph, glyph.copy(color = ink))
            drawText(m, topLeft = Offset(x(tr.t) - m.size.width / 2f, cy - m.size.height / 2f))
            if (originOf(tr) !is Origin.Here) syncedDot(Offset(x(tr.t) + 8.dp.toPx(), cy - 8.dp.toPx()), c.textMuted, c.surface)
        }

        // a planned dose: a dashed ring in the rapid row, at the meal it is for; it is not insulin yet
        planned.filter { it.state == "planned" }.forEach { p ->
            val at = treatments.firstOrNull { it.id == p.mealId }?.t ?: p.createdAt
            val cy = plotH - 30.dp.toPx()
            drawCircle(c.surface, 10.dp.toPx(), Offset(x(at), cy))
            drawCircle(c.insulin, 9.dp.toPx(), Offset(x(at), cy), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
            val m = measurer.measure(insulinGlyph, glyph.copy(color = c.insulin.copy(alpha = 0.7f)))
            drawText(m, topLeft = Offset(x(at) - m.size.width / 2f, cy - m.size.height / 2f))
        }

        // activity: a bar along the top, as long as it lasted, darker the harder it was
        activities.forEach { a ->
            val x0 = x(a.t).coerceAtLeast(0f)
            val x1 = max(x0 + 6.dp.toPx(), x(a.t + (a.duration ?: 0) * 60_000L).coerceAtMost(plotW))
            val alpha = when (a.intensity) { "vigorous" -> 0.85f; "moderate" -> 0.55f; else -> 0.3f }
            val top = ACT_TOP.toPx()
            drawRoundRect(c.inRange.copy(alpha = alpha), Offset(x0, top), Size(x1 - x0, ACT_H.toPx()), CornerRadius(ACT_H.toPx() / 2))
            if ((pick as? Pick.Event)?.treatment == a) {
                drawRoundRect(c.text, Offset(x0, top), Size(x1 - x0, ACT_H.toPx()), CornerRadius(ACT_H.toPx() / 2), style = Stroke(1.5.dp.toPx()))
            }
            val m = measurer.measure(activityGlyph, glyph.copy(color = if (alpha > 0.5f) c.background else c.text, fontSize = 9.sp))
            if (x1 - x0 > m.size.width + 6.dp.toPx()) drawText(m, topLeft = Offset(x0 + 4.dp.toPx(), top + (ACT_H.toPx() - m.size.height) / 2))
            if (originOf(a) !is Origin.Here) syncedDot(Offset(x1, top), c.textMuted, c.surface)
        }

        // suspect marks: nobody answered "did you forget to log?" in time
        marks.forEach { q ->
            val cy = plotH - (if (q.kind == "rise") 12.dp else 30.dp).toPx()
            val at = Offset(x(q.from), cy)
            drawCircle(c.surface, 10.dp.toPx(), at)
            drawCircle(c.high, 9.dp.toPx(), at, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
            if ((pick as? Pick.Suspect)?.question == q) drawCircle(c.text, 10.dp.toPx(), at, style = Stroke(2.dp.toPx()))
            val m = measurer.measure(suspectGlyph, glyph.copy(color = c.high))
            drawText(m, topLeft = Offset(at.x - m.size.width / 2f, cy - m.size.height / 2f))
        }

        // glucometer readings: a diamond, so they never pass for a sensor dot; over the markers, a low one sits among them
        meters.forEach { m ->
            val cx = x(m.t); val cy = y(m.v)
            fun diamond(half: Float) = Path().apply {
                moveTo(cx, cy - half); lineTo(cx + half, cy); lineTo(cx, cy + half); lineTo(cx - half, cy); close()
            }
            val selected = (pick as? Pick.Meter)?.point == m
            drawPath(diamond(9.dp.toPx()), c.background)
            if (m.treatment?.pending == true) {
                drawPath(diamond(7.dp.toPx()), c.text, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
            } else {
                drawPath(diamond(7.dp.toPx()), c.text)
            }
            drawPath(diamond(3.5.dp.toPx()), colorFor(m.v, thresholds, c.low, c.inRange, c.high))
            if (selected) drawPath(diamond(11.dp.toPx()), c.text, style = Stroke(1.5.dp.toPx()))
            if (m.origin !is Origin.Here) syncedDot(Offset(cx + 7.dp.toPx(), cy - 7.dp.toPx()), c.textMuted, c.surface)
        }

        }

        // selection: crosshair + tooltip
        when (val p = pick) {
            is Pick.Reading -> if (p.index in ts.indices) {
                val px = x(ts[p.index]); val v = vs[p.index]
                val col = colorFor(v, thresholds, c.low, c.inRange, c.high)
                drawLine(c.textMuted, Offset(px, 0f), Offset(px, plotH), 1.dp.toPx())
                drawCircle(c.background, 8.dp.toPx(), Offset(px, y(v)))
                drawCircle(col, 6.dp.toPx(), Offset(px, y(v)))
                val delta = previous(ts, vs, p.index)?.let { formatDelta(v - it, units, locale) }
                val lines = listOfNotNull(
                    formatGlucose(v, units, locale) + " " + (if (units == Units.MgDl) "mg/dL" else ""),
                    estimate?.estimate(v)?.let { (e, s) ->
                        meterEstimate.format(locale, formatGlucose(e, units, locale), formatGlucose(s, units, locale))
                    },
                    delta?.let { "$it · ${clock(ts[p.index])}" } ?: clock(ts[p.index]),
                )
                tooltip(measurer, lines, px, y(v), plotW, plotH, c.background, if (v < thresholds.low || v > thresholds.high) col else c.line, c.text, c.textMuted)
            }
            is Pick.Meter -> {
                val m = p.point
                // the sensor at that moment, to see how far apart the two are
                val near = ts.indices.minByOrNull { abs(ts[it] - m.t) }?.takeIf { abs(ts[it] - m.t) <= 6 * 60_000L }
                val unit = if (units == Units.MgDl) " mg/dL" else ""
                val who = originText(m.origin)
                val lines = listOfNotNull(
                    meterLabel.format(locale, formatGlucose(m.v, units, locale) + unit),
                    listOfNotNull(
                        clock(m.t),
                        near?.let { meterSensor.format(locale, formatGlucose(vs[it], units, locale)) },
                        who,
                        pendingLabel.takeIf { m.treatment?.pending == true },
                    ).joinToString(" · "),
                    m.treatment?.notes?.takeIf { it.isNotBlank() },
                )
                tooltip(measurer, lines, x(m.t), y(m.v), plotW, plotH, c.background, c.line, c.text, c.textMuted)
            }
            is Pick.Suspect -> {
                val q = p.question
                val lines = listOf(
                    if (q.kind == "double") suspectDouble.format(locale, com.lumenarcanus.dialife.live.QuestionNotifier.doubleInsulin(context, q), com.lumenarcanus.dialife.ui.treatment.gapText(context, q.to - q.from))
                    else if (q.kind == "bolus") suspectBolus.format(locale, clock(q.from))
                    else (if (q.kind == "rise") suspectRise else suspectFall).format(locale, formatGlucose(q.sgvFrom, units, locale), formatGlucose(q.sgvTo, units, locale)),
                    clock(q.from) + " · " + suspectHint,
                )
                tooltip(measurer, lines, x(q.from), plotH - 40.dp.toPx(), plotW, plotH, c.background, c.high, c.text, c.textMuted)
            }
            // full screen: over the chart, every dish whole; otherwise the card under the chart tells it
            is Pick.Event -> if (entryTooltip) {
                val tr = p.treatment
                val amounts = listOfNotNull(
                    tr.insulin?.takeIf { it > 0 }?.let { u ->
                        // "Lantus 10 од" when the brand is known, else "Інсулін 10 од"
                        tr.insulinName?.let { "$it " + String.format(locale, "%s %s", com.lumenarcanus.dialife.ui.treatment.formatAmount(u, locale), unitInsulin) }
                            ?: insulinLabel.format(locale, u)
                    },
                    tr.carbs?.takeIf { it > 0 }?.let { carbsLabel.format(locale, it.toInt()) },
                ).joinToString(" · ")
                val what = tr.duration?.let { d -> activityLabel.format(locale, intensityLabel(tr.intensity), d) }
                    ?: amounts.ifBlank { tr.notes ?: noteLabel }
                val dishes = tr.foodItems.orEmpty().map { f ->
                    listOfNotNull(f.name, f.portion?.takeIf { it.isNotBlank() }).joinToString(", ") +
                        (f.carbs?.let { " — " + (if (f.suggested) "≈ " else "") + kotlin.math.round(it).toInt() + " $unitCarbs" } ?: "")
                }
                val lines = listOfNotNull(what) + dishes + listOfNotNull(
                    corrLabel.takeIf { tr.insulinRole == "corr" && (tr.insulin ?: 0.0) > 0 },
                    tr.notes?.takeIf { amounts.isNotBlank() && it.isNotBlank() },
                    listOfNotNull(clock(tr.t), originText(originOf(tr)), pendingLabel.takeIf { tr.pending }).joinToString(" · "),
                )
                val ty = if (tr.duration != null) ACT_TOP.toPx() + ACT_H.toPx() else plotH - 40.dp.toPx()
                tooltip(measurer, lines, x(tr.t), ty, plotW, plotH, c.background, c.line, c.text, c.textMuted)
            }
            null -> {}
        }
    }
}

private data class ChartView(
    val start: Long,
    val end: Long,
    val now: Long,
    val visible: List<Int>,
    val treatments: List<Treatment>,
    val thresholds: Thresholds,
    val meters: List<MeterPoint>,
    val lo: Float,
    val hi: Float,
    val activities: List<Treatment>,
    val suspects: List<com.lumenarcanus.dialife.data.Question>,
)

/** The small dot on a mark that came in from xDrip+ rather than typed in DiaLife. */
private fun DrawScope.syncedDot(at: Offset, ink: Color, halo: Color) {
    drawCircle(halo, 3.6.dp.toPx(), at)
    drawCircle(ink, 2.4.dp.toPx(), at)
}

private fun DrawScope.tooltip(
    measurer: androidx.compose.ui.text.TextMeasurer,
    lines: List<String>,
    ax: Float, ay: Float, plotW: Float, plotH: Float,
    bg: Color, border: Color, text: Color, muted: Color,
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
    ms.forEach { m -> drawText(m, topLeft = Offset(left + pad, yy)); yy += m.size.height + 2.dp.toPx() }
}

private fun band(v: Int, th: Thresholds) = when {
    v < th.low -> -1
    v > th.high -> 1
    else -> 0
}

private fun colorFor(v: Int, th: Thresholds, low: Color, ok: Color, high: Color) = when (band(v, th)) {
    -1 -> low
    1 -> high
    else -> ok
}

/** The reading just before index, if it is within 10 minutes. */
private fun previous(ts: List<Long>, vs: List<Int>, i: Int): Int? =
    if (i > 0 && ts[i] - ts[i - 1] <= 10 * 60_000L) vs[i - 1] else null
