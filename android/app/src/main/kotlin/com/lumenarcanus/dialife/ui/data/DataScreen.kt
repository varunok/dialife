package com.lumenarcanus.dialife.ui.data

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatDelta
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.PlexMono
import com.lumenarcanus.dialife.ui.treatment.basalColor
import com.lumenarcanus.dialife.ui.treatment.basalContainer
import com.lumenarcanus.dialife.ui.treatment.formatAmount

enum class EntryKind { Rapid, Basal, Carbs, Meter, Activity, Note, Suspect }

/** One thing logged or measured, as the table shows it. */
data class Entry(
    val t: Long,
    val kind: EntryKind,
    val treatment: Treatment? = null, // editable when set
    val meter: Int? = null,
    val src: String? = null, // a glucometer reading from xDrip+: "Roche 00:00:5E:…" or "manual"
    val question: Question? = null,
)

/**
 * A row: one reading (5 minutes), or a clock-aligned interval from [t] to [end]
 * with the mean of its readings and their range. [span]: a suspect rise or
 * fall that began earlier runs on through it.
 */
data class DataRow(
    val t: Long,
    val sgv: Int?,
    val delta: Int?,
    val entries: List<Entry>,
    val end: Long? = null,
    val min: Int? = null,
    val max: Int? = null,
    val span: Boolean = false,
)

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/** Everything in [from, to], one entry per treatment part, glucometer check and suspect mark, oldest first. */
fun entriesOf(h: History?, questions: List<Question>, from: Long, to: Long): List<Entry> {
    val out = mutableListOf<Entry>()
    h?.treatments.orEmpty().filter { it.t in from..to }.forEach { tr ->
        val ins = (tr.insulin ?: 0.0) > 0
        when {
            tr.glucose != null -> out += Entry(tr.t, EntryKind.Meter, tr, meter = tr.glucose)
            tr.duration != null -> out += Entry(tr.t, EntryKind.Activity, tr)
            else -> {
                if (ins) out += Entry(tr.t, if (InsulinType.of(tr.insulinType) == InsulinType.Basal) EntryKind.Basal else EntryKind.Rapid, tr)
                if ((tr.carbs ?: 0.0) > 0) out += Entry(tr.t, EntryKind.Carbs, tr)
                if (!ins && (tr.carbs ?: 0.0) <= 0 && !tr.notes.isNullOrBlank()) out += Entry(tr.t, EntryKind.Note, tr)
            }
        }
    }
    h?.mbg?.let { m -> m.t.indices.filter { m.t[it] in from..to }.forEach { out += Entry(m.t[it], EntryKind.Meter, meter = m.v[it], src = m.d.getOrNull(it)) } }
    questions.filter { it.from in from..to && (it.state == "unknown" || it.state == "open") }.forEach { out += Entry(it.from, EntryKind.Suspect, question = it) }
    return out.sortedBy { it.t }
}

/**
 * Rows newest first. 5 minutes: every reading, each with the entries since the
 * one before. Longer: intervals on the clock (10:00–10:15…), every reading in
 * them averaged, every entry in the interval it falls in.
 */
fun rowsOf(h: History?, entries: List<Entry>, from: Long, to: Long, stepMin: Int, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<DataRow> {
    val ts = h?.sgv?.t.orEmpty()
    val vs = h?.sgv?.v.orEmpty()
    val idx = ts.indices.filter { ts[it] in from..to }
    val suspects = entries.filter { it.kind == EntryKind.Suspect }.mapNotNull { it.question }
    if (stepMin <= 5) {
        if (idx.isEmpty()) return entries.reversed().map { DataRow(it.t, null, null, listOf(it)) }
        val rows = idx.reversed().map { i ->
            val delta = if (i > 0 && ts[i] - ts[i - 1] <= 10 * MIN) vs[i] - vs[i - 1] else null
            DataRow(ts[i], vs[i], delta, emptyList(), span = suspects.any { q -> q.from < ts[i] && q.to >= ts[i] })
        }.toMutableList()
        for (e in entries) {
            // the oldest row at or after the entry; past the newest reading: the newest row
            val k = rows.indexOfLast { it.t >= e.t }.takeIf { it >= 0 } ?: 0
            rows[k] = rows[k].copy(entries = rows[k].entries + e)
        }
        return rows
    }
    val step = stepMin * MIN
    fun bucket(t: Long): Long { val off = zone.getOffset(t); return Math.floorDiv(t + off, step) * step - off }
    val values = idx.groupBy({ bucket(ts[it]) }, { vs[it] })
    val logged = entries.groupBy { bucket(it.t) }
    return (values.keys + logged.keys).distinct().sortedDescending().map { b ->
        val v = values[b].orEmpty()
        DataRow(
            t = b, end = b + step,
            sgv = if (v.isEmpty()) null else Math.round(v.average()).toInt(),
            min = v.minOrNull(), max = v.maxOrNull(),
            delta = if (v.size > 1) v.last() - v.first() else null,
            entries = logged[b].orEmpty(),
            span = suspects.any { q -> q.from < b && q.to >= b },
        )
    }
}

enum class DataFilter { All, Entries, Out }

/**
 * The chart as a table: every reading (or one per step) with what was logged
 * around it. Upright, a list grouped by hour; "full screen" turns the phone
 * sideways for one column per kind of data, with totals.
 */
@Composable
fun DataScreen(
    history: History?, // the live day, with unsent entries
    questions: List<Question>,
    thresholds: Thresholds,
    units: Units,
    spanHours: Int, // the chart's range when opened: the window this screen pages by
    now: Long,
    loadHistory: suspend (from: Long, to: Long) -> History?, // for windows older than the live day
    onSaveTreatment: (Treatment) -> Unit,
    onBack: () -> Unit,
    onOpenMeal: (Treatment) -> Unit = {}, // food has a screen of its own
) {
    val c = DiaTheme.colors
    // a tapped entry opens the same sheet as on the chart; food its own screen
    var editing by remember { mutableStateOf<Treatment?>(null) }
    editing?.takeIf { (it.carbs ?: 0.0) > 0 && (it.insulin ?: 0.0) <= 0 && it.glucose == null && it.duration == null }?.let { tr ->
        LaunchedEffect(tr.id) { editing = null; onOpenMeal(tr) }
    }
    editing?.takeIf { !((it.carbs ?: 0.0) > 0 && (it.insulin ?: 0.0) <= 0 && it.glucose == null && it.duration == null) }?.let { tr ->
        com.lumenarcanus.dialife.ui.treatment.TreatmentSheet(
            com.lumenarcanus.dialife.ui.treatment.TreatmentKind.Insulin, tr,
            onSave = { onSaveTreatment(it); editing = null },
            onDismiss = { editing = null },
            recent = history?.treatments.orEmpty(),
            units = units,
        )
    }
    val onEdit: (Treatment) -> Unit = { editing = it }
    var page by rememberSaveable { mutableIntStateOf(0) } // 0: ending now; 1: the span before; …
    var filter by rememberSaveable { mutableStateOf(DataFilter.All) }
    var step by rememberSaveable { mutableIntStateOf(5) }
    var wide by rememberSaveable { mutableStateOf(false) }
    val span = spanHours * HOUR
    val to = now - page * span
    val from = to - span
    var older by remember { mutableStateOf<History?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(page, spanHours) {
        older = null
        if (from < now - 24 * HOUR) {
            loading = true
            older = loadHistory(from, to)
            loading = false
        }
    }
    val h = if (from < now - 24 * HOUR) older else history
    val entries = entriesOf(h, questions, from, to)
    val rows = rowsOf(h, entries, from, to, step).filter { r ->
        when (filter) {
            DataFilter.All -> true
            DataFilter.Entries -> r.entries.isNotEmpty()
            DataFilter.Out -> (r.min ?: r.sgv)?.let { it < thresholds.low } == true || (r.max ?: r.sgv)?.let { it > thresholds.high } == true
        }
    }
    val ctx = LocalContext.current
    val day = if (android.text.format.DateUtils.isToday(to)) stringResource(R.string.data_today) else
        android.text.format.DateUtils.formatDateTime(ctx, to, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
    val window = stringResource(R.string.data_window, day, AlarmText.clock(ctx, from), AlarmText.clock(ctx, to))

    if (wide) {
        WideTable(rows, entries, h, from, to, window, units, thresholds, step, { step = it }, onEdit, onClose = { wide = false }, onChart = onBack)
        return
    }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(c.background).safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_chart), stringResource(R.string.data_chart), tint = c.textMuted) }
            IconButton(onClick = { wide = true }) { Icon(painterResource(R.drawable.ic_fullscreen), stringResource(R.string.data_wide), tint = c.textMuted) }
        }
        Text(stringResource(R.string.data_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page++ }) { Text("‹ " + stringResource(R.string.data_prev), color = c.textMuted) }
            Text(window, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text(stringResource(R.string.data_next) + " ›", color = if (page > 0) c.textMuted else c.textFaint) }
        }
        // two rows: "Лише записи" and "Поза межами" do not fit beside the step
        Segments(
            listOf(DataFilter.All to stringResource(R.string.data_all), DataFilter.Entries to stringResource(R.string.data_events), DataFilter.Out to stringResource(R.string.data_out)),
            filter, Modifier.fillMaxWidth(), fill = true,
        ) { filter = it }
        StepSegments(step) { step = it }
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .background(c.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .border(1.dp, c.line, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeadCell(stringResource(R.string.data_time), timeColumn())
                HeadCell(if (units == Units.MgDl) "mg/dL" else stringResource(R.string.unit_mmol), 74.dp)
                HeadCell(stringResource(R.string.data_delta), 44.dp)
                HeadCell(stringResource(R.string.data_entries), null)
            }
            when {
                loading -> Text(stringResource(R.string.data_loading), color = c.textMuted, modifier = Modifier.padding(14.dp))
                rows.isEmpty() -> Text(stringResource(R.string.data_empty), color = c.textMuted, modifier = Modifier.padding(14.dp))
                else -> {
                    val groups = rows.groupBy { hourOf(it.t) }
                    val readings = h?.sgv
                    LazyColumn(Modifier.fillMaxSize()) {
                        groups.forEach { (hourStart, rs) ->
                            item(key = "h$hourStart") {
                                val vs = readings?.t.orEmpty().indices.filter { readings!!.t[it] in maxOf(hourStart, from)..minOf(hourStart + HOUR, to) }.map { readings!!.v[it] }
                                HourHeader(hourStart, vs, units)
                            }
                            items(rs, key = { "r${it.t}" }) { r -> ListRow(r, units, thresholds, onEdit) }
                        }
                    }
                }
            }
        }
    }
}

private fun hourOf(t: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = t
        set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, fill: Boolean = false, onPick: (T) -> Unit) {
    val c = DiaTheme.colors
    Row(modifier.background(c.surface, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Text(
                label, color = if (on) c.text else c.textMuted, fontSize = 12.sp, maxLines = 1,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .background(if (on) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = on, role = Role.Tab) { onPick(v) }
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 8.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun StepSegments(step: Int, onPick: (Int) -> Unit) = Segments(
    listOf(5 to stringResource(R.string.data_step_min, 5), 15 to stringResource(R.string.data_step_min, 15), 60 to stringResource(R.string.chart_range, 1)),
    step,
) { onPick(it) }

@Composable
private fun HeadCell(text: String, width: Dp?, color: Color = DiaTheme.colors.textFaint) {
    Text(text, color = color, fontSize = 11.sp, maxLines = 1, modifier = if (width != null) Modifier.width(width) else Modifier)
}

@Composable
private fun HourHeader(hourStart: Long, vs: List<Int>, units: Units) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Row(
        Modifier.fillMaxWidth().background(c.background.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(AlarmText.clock(ctx, hourStart) + "–" + AlarmText.clock(ctx, hourStart + HOUR), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (vs.isNotEmpty()) {
            Text(
                stringResource(R.string.data_hour, formatGlucose(vs.average().toInt(), units, locale), formatGlucose(vs.min(), units, locale), formatGlucose(vs.max(), units, locale)),
                color = c.textMuted, fontSize = 11.sp, fontFamily = PlexMono,
            )
        }
    }
}

private fun valueColor(v: Int, th: Thresholds, low: Color, ok: Color, high: Color) = if (v < th.low) low else if (v > th.high) high else ok

private fun arrowOf(delta: Int?): String = when {
    delta == null -> ""
    delta >= 10 -> "↑"
    delta >= 5 -> "↗"
    delta <= -10 -> "↓"
    delta <= -5 -> "↘"
    else -> "→"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListRow(r: DataRow, units: Units, th: Thresholds, onEdit: (Treatment) -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    // a suspect rise or fall: an orange edge on the rows it spans
    val stripe = r.span || r.entries.any { it.kind == EntryKind.Suspect }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp)
            .drawBehind { if (stripe) drawRect(c.high, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            AlarmText.clock(ctx, r.t) + (r.end?.let { "\n" + AlarmText.clock(ctx, it) } ?: ""),
            color = c.textMuted, fontSize = 12.sp, fontFamily = PlexMono, lineHeight = 14.sp, modifier = Modifier.width(timeColumn()),
        )
        Column(Modifier.width(74.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                r.sgv?.let { v ->
                    Text(formatGlucose(v, units, locale), color = valueColor(v, th, c.low, c.inRange, c.high), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (r.end == null) Text(" " + arrowOf(r.delta), color = c.textMuted, fontSize = 12.sp)
                }
            }
            if (r.end != null && r.min != null && r.max != null) {
                Row {
                    Text(formatGlucose(r.min, units, locale), color = if (r.min < th.low) c.low else c.textMuted, fontSize = 10.5.sp, fontFamily = PlexMono)
                    Text("–", color = c.textFaint, fontSize = 10.5.sp, fontFamily = PlexMono)
                    Text(formatGlucose(r.max, units, locale), color = if (r.max > th.high) c.high else c.textMuted, fontSize = 10.5.sp, fontFamily = PlexMono)
                }
            }
        }
        Text(r.delta?.let { formatDelta(it, units, locale) }.orEmpty(), color = c.textMuted, fontSize = 12.sp, fontFamily = PlexMono, modifier = Modifier.width(44.dp))
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            r.entries.forEach { e -> EntryChip(e, units, onEdit, showTime = r.end != null) }
        }
    }
}

/** The colours of a kind of entry: the same as its marker on the chart. */
@Composable
private fun inkOf(k: EntryKind): Pair<Color, Color> {
    val c = DiaTheme.colors
    return when (k) {
        EntryKind.Rapid -> c.insulin to c.insulinContainer
        EntryKind.Basal -> basalColor(c.isDark) to basalContainer(c.isDark)
        EntryKind.Carbs -> c.carbs to c.carbsContainer
        EntryKind.Meter -> c.text to c.surfaceRaised
        EntryKind.Activity -> c.inRange to c.inRangeContainer
        EntryKind.Note -> c.textMuted to c.surfaceRaised
        EntryKind.Suspect -> c.high to c.highContainer
    }
}

/** "Lantus 18 од", "20 г", "◆ 212", "Активність 40 хв"…; who added it is separate. */
@Composable
private fun entryText(e: Entry, units: Units): String {
    val locale = LocalConfiguration.current.locales[0]
    val u = stringResource(R.string.unit_insulin)
    val tr = e.treatment
    return when (e.kind) {
        EntryKind.Rapid, EntryKind.Basal -> {
            val type = if (e.kind == EntryKind.Basal) InsulinType.Basal else InsulinType.Rapid
            (tr?.insulinName ?: Prefs.nameOf(type) ?: stringResource(if (type == InsulinType.Basal) R.string.ins_basal else R.string.ins_rapid)) + " " + formatAmount(tr?.insulin ?: 0.0, locale) + " " + u
        }
        EntryKind.Carbs -> formatAmount(tr?.carbs ?: 0.0, locale) + " " + stringResource(R.string.unit_carbs) + ((tr?.foodText() ?: tr?.notes?.takeIf { it.isNotBlank() })?.let { " · $it" } ?: "")
        EntryKind.Meter -> "◆ " + formatGlucose(e.meter ?: 0, units, locale)
        EntryKind.Activity -> stringResource(R.string.tr_activity) + " " + stringResource(R.string.data_step_min, tr?.duration ?: 0)
        EntryKind.Note -> tr?.notes.orEmpty()
        EntryKind.Suspect -> suspectText(e, units)
    }
}

/** "? підйом 13:50–14:20 · 129 → 171, нічого не записано": a suspect carries its own span. */
@Composable
private fun suspectText(e: Entry, units: Units): String {
    val q = e.question ?: return "?"
    val ctx = LocalContext.current
    if (q.kind == "bolus") return stringResource(R.string.data_suspect_bolus, AlarmText.clock(ctx, q.from))
    if (q.kind == "double") return stringResource(R.string.data_suspect_double, com.lumenarcanus.dialife.live.QuestionNotifier.doubleInsulin(ctx, q), AlarmText.clock(ctx, q.from), AlarmText.clock(ctx, q.to))
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(
        if (q.kind == "fall") R.string.data_suspect_fall else R.string.data_suspect_rise,
        AlarmText.clock(ctx, q.from), AlarmText.clock(ctx, q.to), formatGlucose(q.sgvFrom, units, locale), formatGlucose(q.sgvTo, units, locale),
    )
}

/** Who added it: a parent's name, "xDrip", or the meter that sent it; null when unknown. */
@Composable
private fun whoOf(e: Entry): Pair<String, Boolean>? {
    val tr = e.treatment
    if (tr != null) {
        return when (val o = com.lumenarcanus.dialife.ui.home.originOf(tr)) {
            is com.lumenarcanus.dialife.ui.home.Origin.Here -> o.who.takeIf { it.isNotBlank() }?.let { it to false }
            else -> "xDrip" to true
        }
    }
    if (e.kind == EntryKind.Meter) {
        val s = e.src
        return if (s.isNullOrBlank() || s == "manual") "xDrip" to true else s.substringBefore(' ') to true
    }
    return null
}

@Composable
private fun EntryChip(e: Entry, units: Units, onEdit: (Treatment) -> Unit, showTime: Boolean = false) {
    val ctx = LocalContext.current
    val (ink, bg) = inkOf(e.kind)
    val who = whoOf(e)
    Row(
        Modifier
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .then(if (e.treatment != null && e.kind != EntryKind.Suspect) Modifier.clickable { onEdit(e.treatment) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // in an interval, say when exactly; a suspect already carries its span
        val at = if (showTime && e.kind != EntryKind.Suspect) " · " + AlarmText.clock(ctx, e.t) else ""
        Text(entryText(e, units) + at + (who?.let { " · " + it.first } ?: ""), color = ink, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
        if (who?.second == true) {
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(5.dp).background(DiaTheme.colors.textMuted, CircleShape))
        }
    }
}

/** Sideways: one column per kind of data, a totals line at the bottom. */
@Composable
private fun WideTable(
    rows: List<DataRow>,
    entries: List<Entry>,
    h: History?,
    from: Long,
    to: Long,
    window: String,
    units: Units,
    th: Thresholds,
    step: Int,
    onStep: (Int) -> Unit,
    onEdit: (Treatment) -> Unit,
    onClose: () -> Unit,
    onChart: () -> Unit,
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val activity = ctx as? android.app.Activity
    DisposableEffect(activity) {
        val window2 = activity?.window
        val bars = window2?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(onBack = onClose)
    val u = stringResource(R.string.unit_insulin)
    val g = stringResource(R.string.unit_carbs)
    val widths = listOf(96.dp, 110.dp, 52.dp, 64.dp, 110.dp, 104.dp, 70.dp, 90.dp, 200.dp)
    Column(Modifier.fillMaxSize().background(c.background).displayCutoutPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(window, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            StepSegments(step, onStep)
            IconButton(onClick = onChart) { Icon(painterResource(R.drawable.ic_chart), stringResource(R.string.data_chart), tint = c.textMuted) }
            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.chart_close), tint = c.textMuted) }
        }
        Column(
            Modifier.fillMaxSize().padding(top = 6.dp)
                .background(c.surface, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .border(1.dp, c.line, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .horizontalScroll(rememberScrollState()),
        ) {
            @Composable
            fun Line(cells: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
                Row(modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    cells.forEachIndexed { i, cell -> Box(Modifier.width(widths[i])) { cell() } }
                }
            }
            val (rapidInk, _) = inkOf(EntryKind.Rapid)
            val (basalInk, _) = inkOf(EntryKind.Basal)
            val (carbsInk, _) = inkOf(EntryKind.Carbs)
            val (actInk, _) = inkOf(EntryKind.Activity)
            val heads = listOf(
                stringResource(R.string.data_time) to c.textFaint,
                stringResource(R.string.data_sensor) to c.textFaint,
                stringResource(R.string.data_delta) to c.textFaint,
                stringResource(R.string.data_meter) to c.text,
                stringResource(R.string.ins_rapid) to rapidInk,
                stringResource(R.string.ins_basal) to basalInk,
                stringResource(R.string.tr_carbs) to carbsInk,
                stringResource(R.string.tr_activity) to actInk,
                stringResource(R.string.data_note) to c.textFaint,
            )
            Line(heads.map { (t, col) -> @Composable { Text(t, color = col, fontSize = 11.sp, maxLines = 1) } })
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { "w${it.t}" }) { r ->
                    fun of(vararg k: EntryKind) = r.entries.filter { it.kind in k }
                    @Composable
                    fun Cell(es: List<Entry>, ink: Color, text: @Composable (Entry) -> String) {
                        val tr = es.firstOrNull()?.treatment
                        Text(
                            if (es.isEmpty()) "·" else es.map { text(it) + if (r.end != null) " · " + AlarmText.clock(ctx, it.t) else "" }.joinToString(", "),
                            color = if (es.isEmpty()) c.textFaint else ink, fontSize = 12.5.sp, fontFamily = PlexMono, maxLines = 2,
                            modifier = if (tr != null) Modifier.clickable { onEdit(tr) } else Modifier,
                        )
                    }
                    Line(
                        listOf(
                            { Text(AlarmText.clock(ctx, r.t) + (r.end?.let { "–" + AlarmText.clock(ctx, it) } ?: ""), color = c.textMuted, fontSize = 12.sp, fontFamily = PlexMono) },
                            {
                                r.sgv?.let { v ->
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(formatGlucose(v, units, locale) + if (r.end == null) " " + arrowOf(r.delta) else "", color = valueColor(v, th, c.low, c.inRange, c.high), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        if (r.end != null && r.min != null && r.max != null) {
                                            Text(" " + formatGlucose(r.min, units, locale) + "–" + formatGlucose(r.max, units, locale), color = c.textMuted, fontSize = 10.5.sp, fontFamily = PlexMono)
                                        }
                                    }
                                }
                            },
                            { Text(r.delta?.let { formatDelta(it, units, locale) }.orEmpty(), color = c.textMuted, fontSize = 12.sp, fontFamily = PlexMono) },
                            { Cell(of(EntryKind.Meter), c.text) { formatGlucose(it.meter ?: 0, units, locale) + if (whoOf(it)?.second == true) " •" else "" } },
                            { Cell(of(EntryKind.Rapid), rapidInk) { formatAmount(it.treatment?.insulin ?: 0.0, locale) + " " + (it.treatment?.insulinName ?: Prefs.rapidName ?: "") } },
                            { Cell(of(EntryKind.Basal), basalInk) { formatAmount(it.treatment?.insulin ?: 0.0, locale) + " " + (it.treatment?.insulinName ?: Prefs.basalName ?: "") } },
                            { Cell(of(EntryKind.Carbs), carbsInk) { formatAmount(it.treatment?.carbs ?: 0.0, locale) + " " + g } },
                            { Cell(of(EntryKind.Activity), actInk) { stringResource(R.string.data_step_min, it.treatment?.duration ?: 0) } },
                            {
                                val notes = r.entries.mapNotNull { e ->
                                    when (e.kind) {
                                        EntryKind.Suspect -> suspectText(e, units)
                                        EntryKind.Note -> e.treatment?.notes
                                        else -> listOfNotNull(e.treatment?.foodText(), e.treatment?.notes?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }
                                    }
                                } + r.entries.mapNotNull { whoOf(it)?.first }.distinct()
                                Text(notes.joinToString(" · "), color = c.textMuted, fontSize = 12.sp)
                            },
                        ),
                        Modifier.then(if (r.entries.isNotEmpty()) Modifier.background(c.surfaceRaised.copy(alpha = 0.5f)) else Modifier),
                    )
                }
            }
            // totals over the whole window, whatever the step
            val vs = h?.sgv?.let { s -> s.t.indices.filter { s.t[it] in from..to }.map { s.v[it] } }.orEmpty()
            fun sum(k: EntryKind, f: (Treatment) -> Double) = entries.filter { it.kind == k }.sumOf { it.treatment?.let(f) ?: 0.0 }
            val suspects = entries.count { it.kind == EntryKind.Suspect }
            Line(
                listOf(
                    { Text(stringResource(R.string.data_total), color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    { Text(if (vs.isEmpty()) "" else stringResource(R.string.data_avg, formatGlucose(vs.average().toInt(), units, locale)), color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    {},
                    { Text(entries.count { it.kind == EntryKind.Meter }.toString(), color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    { Text(formatAmount(sum(EntryKind.Rapid) { it.insulin ?: 0.0 }, locale) + " " + u, color = rapidInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    { Text(formatAmount(sum(EntryKind.Basal) { it.insulin ?: 0.0 }, locale) + " " + u, color = basalInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    { Text(formatAmount(sum(EntryKind.Carbs) { it.carbs ?: 0.0 }, locale) + " " + g, color = carbsInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    { Text(stringResource(R.string.data_step_min, sum(EntryKind.Activity) { (it.duration ?: 0).toDouble() }.toInt()), color = actInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    { if (suspects > 0) Text(stringResource(R.string.data_suspects, suspects), color = c.high, fontSize = 12.sp) },
                ),
                Modifier.background(c.background.copy(alpha = 0.6f)),
            )
        }
    }
}

/** The time column: "10:46", or "10:46 AM" on a 12-hour clock, on one line. */
@Composable
private fun timeColumn() = if (android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)) 50.dp else 68.dp
