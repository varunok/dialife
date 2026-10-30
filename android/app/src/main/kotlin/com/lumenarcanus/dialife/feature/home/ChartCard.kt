package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.Forecast
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import java.util.Date
import kotlin.math.min

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
    // the "≈ meter" switch turned on or off
    onMeterLine: (Boolean) -> Unit = {},
    suspects: List<com.lumenarcanus.dialife.data.Question> = emptyList(), // unanswered questions: "?" marks
    onDismissSuspect: (Long) -> Unit = {},
    onOpenTable: (() -> Unit)? = null, // the same data as a table
    dose: com.lumenarcanus.dialife.data.DoseSettings = com.lumenarcanus.dialife.data.DoseSettings(), // for a meal's worked-out dose
    planned: List<com.lumenarcanus.dialife.data.PlannedDose> = emptyList(),
    // the server's: what makes one meal
    mealRules: com.lumenarcanus.dialife.data.MealRules = com.lumenarcanus.dialife.data.MealRules()
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
        verticalArrangement = Arrangement.spacedBy(if (expanded) 6.dp else 10.dp)
    ) {
        val title = window?.at(now)?.let { w ->
            val f = android.text.format.DateFormat.getTimeFormat(context)
            stringResource(R.string.chart_window, f.format(Date(w.start)), f.format(Date(min(w.end, now))))
        } ?: stringResource(R.string.chart_last_hours, rangeHours)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            // wide enough when expanded (landscape): the ranges share the title's row
            if (expanded) {
                RangeTabs(window == null, rangeHours, Modifier.width(320.dp)) {
                    window = null
                    onRange(it)
                }
            }
            MeterSwitch(on = estimate != null, ready = meter?.ready == true) {
                if (meter?.ready == true) {
                    onMeterLine(!meterLine)
                } else {
                    whyNot = !whyNot
                }
            }
            onOpenTable?.let { open ->
                androidx.compose.material3.IconButton(onClick = open, modifier = Modifier.size(40.dp)) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_table),
                        stringResource(R.string.chart_table),
                        tint = c.textMuted
                    )
                }
            }
            val what = stringResource(if (expanded) R.string.chart_close else R.string.chart_expand)
            androidx.compose.material3.IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(if (expanded) R.drawable.ic_close else R.drawable.ic_fullscreen),
                    contentDescription = what,
                    tint = c.textMuted
                )
            }
        }
        if (whyNot && meter?.ready != true) {
            val used = meter?.used ?: 0
            val need = meter?.need ?: 8
            Text(
                stringResource(R.string.meter_not_ready, used, need, (need - used).coerceAtLeast(1)),
                color = c.textMuted,
                fontSize = 12.5.sp,
                modifier = Modifier.fillMaxWidth().background(
                    c.surfaceRaised,
                    RoundedCornerShape(12.dp)
                ).padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        // own full-width row: "Останні 24 год" plus four "N год" chips do not fit side by side
        if (!expanded) {
            RangeTabs(window == null, rangeHours, Modifier.fillMaxWidth()) {
                window = null
                onRange(it)
            }
        }
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
            modifier = if (expanded) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(230.dp)
        )
        // the tapped marker's actions: the canvas tooltip says what it is, these say what to do
        // a suspect mark: anyone may clear it, no questions asked
        suspect?.let { q ->
            androidx.compose.material3.OutlinedButton(onClick = {
                suspect = null
                onDismissSuspect(q.id)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.q_dismiss), color = c.text)
            }
        }
        // full screen: the entry says what it is over the chart, like a reading; no editing there
        selected?.takeIf { !expanded }?.let { tr ->
            com.lumenarcanus.dialife.feature.meal.EntryDetail(
                tr,
                history?.treatments.orEmpty(),
                dose,
                units,
                mealRules,
                mbg = history?.mbg ?: com.lumenarcanus.dialife.data.Series()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedButton(onClick = { onEdit(tr) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tr_edit), color = c.text)
                }
                androidx.compose.material3.OutlinedButton(onClick = {
                    selected = null
                    onDelete(tr)
                }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tr_delete), color = c.low)
                }
            }
        }
    }
}

/** "≈ Meter": filled when the line is on, faded while there are too few checks for it. */
@Composable
internal fun MeterSwitch(on: Boolean, ready: Boolean, onClick: () -> Unit) {
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
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(Modifier.size(10.dp)) {
            val d = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width, size.height / 2)
                lineTo(size.width / 2, size.height)
                lineTo(
                    0f,
                    size.height / 2
                )
                close()
            }
            if (on) drawPath(d, ink) else drawPath(d, ink, style = Stroke(1.4.dp.toPx()))
        }
        Text(stringResource(R.string.meter_toggle), color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The estimate's own ink: the insulin violet, lighter, so it never reads as a sensor line. */
fun meterInk(dark: Boolean) = if (dark) Color(0xFFE8D9FF) else Color(0xFF5B3FD1)

@Composable
internal fun RangeTabs(followsRange: Boolean, rangeHours: Int, modifier: Modifier, onRange: (Int) -> Unit) {
    val c = DiaTheme.colors
    Row(
        modifier.background(c.background, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    .padding(horizontal = 4.dp, vertical = 9.dp)
            )
        }
    }
}
