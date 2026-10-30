package com.lumenarcanus.dialife.feature.data

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatDelta
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.PlexMono
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment

/** The columns' widths, left to right: time, sensor, change, glucometer, rapid, long, carbs, activity, notes. */
private val Widths = listOf(96.dp, 110.dp, 52.dp, 64.dp, 110.dp, 104.dp, 70.dp, 90.dp, 200.dp)

/** The inks of the entry columns: the same as their marks on the chart. */
private data class Inks(val rapid: Color, val basal: Color, val carbs: Color, val activity: Color)

/** Sideways: one column per kind of data, a totals line at the bottom. */
@Composable
internal fun WideTable(
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
    onChart: () -> Unit
) {
    val c = DiaTheme.colors
    LandscapeWhileShown()
    BackHandler(onBack = onClose)
    val inks =
        Inks(inkOf(EntryKind.Rapid).first, inkOf(EntryKind.Basal).first, inkOf(EntryKind.Carbs).first, inkOf(EntryKind.Activity).first)
    Column(Modifier.fillMaxSize().background(c.background).displayCutoutPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(window, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            StepSegments(step, onStep)
            IconButton(onClick = onChart) {
                Icon(painterResource(R.drawable.ic_chart), stringResource(R.string.data_chart), tint = c.textMuted)
            }
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.chart_close), tint = c.textMuted)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(top = 6.dp)
                .background(c.surface, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .border(1.dp, c.line, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .horizontalScroll(rememberScrollState())
        ) {
            WideHeads(inks)
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { "w${it.t}" }) { r -> WideRow(r, units, th, inks, onEdit) }
            }
            WideTotals(entries, h, from, to, units, inks)
        }
    }
}

/** The table wants the whole screen sideways: landscape, system bars hidden, while it is shown. */
@Composable
private fun LandscapeWhileShown() {
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val bars = activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** One line of the table: each cell in its column's width. */
@Composable
private fun TableLine(cells: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { i, cell -> Box(Modifier.width(Widths[i])) { cell() } }
    }
}

@Composable
private fun WideHeads(inks: Inks) {
    val c = DiaTheme.colors
    val heads = listOf(
        stringResource(R.string.data_time) to c.textFaint,
        stringResource(R.string.data_sensor) to c.textFaint,
        stringResource(R.string.data_delta) to c.textFaint,
        stringResource(R.string.data_meter) to c.text,
        stringResource(R.string.ins_rapid) to inks.rapid,
        stringResource(R.string.ins_basal) to inks.basal,
        stringResource(R.string.tr_carbs) to inks.carbs,
        stringResource(R.string.tr_activity) to inks.activity,
        stringResource(R.string.data_note) to c.textFaint
    )
    TableLine(heads.map { (t, col) -> @Composable { Text(t, color = col, fontSize = 11.sp, maxLines = 1) } })
}

/** A reading (or an interval) with what was logged in it, each kind in its column; a tap on an entry edits it. */
@Composable
private fun WideRow(r: DataRow, units: Units, th: Thresholds, inks: Inks, onEdit: (Treatment) -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val g = stringResource(R.string.unit_carbs)
    fun of(vararg k: EntryKind) = r.entries.filter { it.kind in k }

    @Composable
    fun Cell(es: List<Entry>, ink: Color, text: @Composable (Entry) -> String) = EntryCell(es, ink, withTime = r.end != null, onEdit, text)
    TableLine(
        listOf(
            {
                Text(
                    formatClock(ctx, r.t) + r.end?.let { "–" + formatClock(ctx, it) }.orEmpty(),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    fontFamily = PlexMono
                )
            },
            { r.sgv?.let { v -> SensorCell(r, v, units, th) } },
            {
                Text(
                    r.delta?.let {
                        formatDelta(it, units, locale)
                    }.orEmpty(),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    fontFamily = PlexMono
                )
            },
            {
                Cell(of(EntryKind.Meter), c.text) {
                    formatGlucose(it.meter ?: 0, units, locale) + if (whoOf(it)?.second ==
                        true
                    ) {
                        " •"
                    } else {
                        ""
                    }
                }
            },
            {
                Cell(of(EntryKind.Rapid), inks.rapid) {
                    formatAmount(it.treatment?.insulin ?: 0.0, locale) + " " + (it.treatment?.insulinName ?: Prefs.rapidName ?: "")
                }
            },
            {
                Cell(of(EntryKind.Basal), inks.basal) {
                    formatAmount(it.treatment?.insulin ?: 0.0, locale) + " " + (it.treatment?.insulinName ?: Prefs.basalName ?: "")
                }
            },
            { Cell(of(EntryKind.Carbs), inks.carbs) { formatAmount(it.treatment?.carbs ?: 0.0, locale) + " " + g } },
            { Cell(of(EntryKind.Activity), inks.activity) { stringResource(R.string.data_step_min, it.treatment?.duration ?: 0) } },
            { NotesCell(r, units) }
        ),
        Modifier.then(if (r.entries.isNotEmpty()) Modifier.background(c.surfaceRaised.copy(alpha = 0.5f)) else Modifier)
    )
}

/** The sensor value (with its arrow for one reading, its range for an interval). */
@Composable
private fun SensorCell(r: DataRow, v: Int, units: Units, th: Thresholds) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            formatGlucose(v, units, locale) + if (r.end == null) " " + arrowOf(r.delta) else "",
            color = valueColor(v, th, c.low, c.inRange, c.high),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (r.end != null && r.min != null && r.max != null) {
            Text(
                " " + formatGlucose(r.min, units, locale) + "–" + formatGlucose(r.max, units, locale),
                color = c.textMuted,
                fontSize = 10.5.sp,
                fontFamily = PlexMono
            )
        }
    }
}

/** The entries of one kind in a row, "·" for none; with their times in an interval. */
@Composable
private fun EntryCell(es: List<Entry>, ink: Color, withTime: Boolean, onEdit: (Treatment) -> Unit, text: @Composable (Entry) -> String) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val tr = es.firstOrNull()?.treatment
    Text(
        if (es.isEmpty()) "·" else es.map { text(it) + if (withTime) " · " + formatClock(ctx, it.t) else "" }.joinToString(", "),
        color = if (es.isEmpty()) c.textFaint else ink,
        fontSize = 12.5.sp,
        fontFamily = PlexMono,
        maxLines = 2,
        modifier = if (tr != null) Modifier.clickable { onEdit(tr) } else Modifier
    )
}

/** Suspect marks, notes, dishes and who added them. */
@Composable
private fun NotesCell(r: DataRow, units: Units) {
    val c = DiaTheme.colors
    val notes = r.entries.mapNotNull { e ->
        when (e.kind) {
            EntryKind.Suspect -> suspectText(e, units)
            EntryKind.Note -> e.treatment?.notes
            else -> listOfNotNull(
                e.treatment?.foodText(),
                e.treatment?.notes?.takeIf {
                    it.isNotBlank()
                }
            ).joinToString(" · ").ifBlank { null }
        }
    } + r.entries.mapNotNull { whoOf(it)?.first }.distinct()
    Text(notes.joinToString(" · "), color = c.textMuted, fontSize = 12.sp)
}

/** Totals over the whole window, whatever the step. */
@Composable
private fun WideTotals(entries: List<Entry>, h: History?, from: Long, to: Long, units: Units, inks: Inks) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val u = stringResource(R.string.unit_insulin)
    val g = stringResource(R.string.unit_carbs)
    val vs = h?.sgv?.let { s -> s.t.indices.filter { s.t[it] in from..to }.map { s.v[it] } }.orEmpty()
    fun sum(k: EntryKind, f: (Treatment) -> Double) = entries.filter { it.kind == k }.sumOf { it.treatment?.let(f) ?: 0.0 }
    val suspects = entries.count { it.kind == EntryKind.Suspect }

    @Composable
    fun Total(text: String, color: Color) = Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    TableLine(
        listOf(
            { Text(stringResource(R.string.data_total), color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
            {
                Total(
                    if (vs.isEmpty()) "" else stringResource(R.string.data_avg, formatGlucose(vs.average().toInt(), units, locale)),
                    c.text
                )
            },
            {},
            { Total(entries.count { it.kind == EntryKind.Meter }.toString(), c.text) },
            { Total(formatAmount(sum(EntryKind.Rapid) { it.insulin ?: 0.0 }, locale) + " " + u, inks.rapid) },
            { Total(formatAmount(sum(EntryKind.Basal) { it.insulin ?: 0.0 }, locale) + " " + u, inks.basal) },
            { Total(formatAmount(sum(EntryKind.Carbs) { it.carbs ?: 0.0 }, locale) + " " + g, inks.carbs) },
            {
                Total(
                    stringResource(
                        R.string.data_step_min,
                        sum(EntryKind.Activity) {
                            (it.duration ?: 0).toDouble()
                        }.toInt()
                    ),
                    inks.activity
                )
            },
            { if (suspects > 0) Text(stringResource(R.string.data_suspects, suspects), color = c.high, fontSize = 12.sp) }
        ),
        Modifier.background(c.background.copy(alpha = 0.6f))
    )
}
