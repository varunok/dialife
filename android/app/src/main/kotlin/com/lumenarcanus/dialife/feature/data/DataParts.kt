package com.lumenarcanus.dialife.feature.data

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.feature.entry.basalColor
import com.lumenarcanus.dialife.feature.entry.basalContainer
import com.lumenarcanus.dialife.feature.home.Origin
import com.lumenarcanus.dialife.feature.home.originOf
import com.lumenarcanus.dialife.feature.questions.QuestionNotifier

// What the upright list and the wide table share: the controls, the colours and the entry chips.

@Composable
internal fun <T> Segments(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    onPick: (T) -> Unit
) {
    val c = DiaTheme.colors
    Row(modifier.background(c.surface, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Text(
                label,
                color = if (on) c.text else c.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .background(if (on) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = on, role = Role.Tab) { onPick(v) }
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 8.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
internal fun StepSegments(step: Int, onPick: (Int) -> Unit) = Segments(
    listOf(
        5 to stringResource(R.string.data_step_min, 5),
        15 to stringResource(R.string.data_step_min, 15),
        60 to stringResource(R.string.chart_range, 1)
    ),
    step
) { onPick(it) }

@Composable
internal fun HeadCell(text: String, width: Dp?, color: Color = DiaTheme.colors.textFaint) {
    Text(text, color = color, fontSize = 11.sp, maxLines = 1, modifier = if (width != null) Modifier.width(width) else Modifier)
}
internal fun valueColor(v: Int, th: Thresholds, low: Color, ok: Color, high: Color) = if (v < th.low) {
    low
} else if (v > th.high) {
    high
} else {
    ok
}

internal fun arrowOf(delta: Int?): String = when {
    delta == null -> ""
    delta >= 10 -> "↑"
    delta >= 5 -> "↗"
    delta <= -10 -> "↓"
    delta <= -5 -> "↘"
    else -> "→"
}

/** The colours of a kind of entry: the same as its marker on the chart. */
@Composable
internal fun inkOf(k: EntryKind): Pair<Color, Color> {
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
internal fun entryText(e: Entry, units: Units): String {
    val locale = LocalConfiguration.current.locales[0]
    val u = stringResource(R.string.unit_insulin)
    val tr = e.treatment
    return when (e.kind) {
        EntryKind.Rapid, EntryKind.Basal -> {
            val type = if (e.kind == EntryKind.Basal) InsulinType.Basal else InsulinType.Rapid
            (
                tr?.insulinName ?: Prefs.nameOf(type)
                    ?: stringResource(if (type == InsulinType.Basal) R.string.ins_basal else R.string.ins_rapid)
                ) +
                " " +
                formatAmount(tr?.insulin ?: 0.0, locale) +
                " " +
                u
        }
        EntryKind.Carbs -> formatAmount(tr?.carbs ?: 0.0, locale) + " " + stringResource(R.string.unit_carbs) +
            ((tr?.foodText() ?: tr?.notes?.takeIf { it.isNotBlank() })?.let { " · $it" } ?: "")
        EntryKind.Meter -> "◆ " + formatGlucose(e.meter ?: 0, units, locale)
        EntryKind.Activity -> stringResource(R.string.tr_activity) + " " + stringResource(R.string.data_step_min, tr?.duration ?: 0)
        EntryKind.Note -> tr?.notes.orEmpty()
        EntryKind.Suspect -> suspectText(e, units)
    }
}

/** "? підйом 13:50–14:20 · 129 → 171, нічого не записано": a suspect carries its own span. */
@Composable
internal fun suspectText(e: Entry, units: Units): String {
    val q = e.question ?: return "?"
    val ctx = LocalContext.current
    if (q.kind == "bolus") return stringResource(R.string.data_suspect_bolus, formatClock(ctx, q.from))
    if (q.kind == "double") {
        return stringResource(
            R.string.data_suspect_double,
            QuestionNotifier.doubleInsulin(ctx, q),
            formatClock(ctx, q.from),
            formatClock(ctx, q.to)
        )
    }
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(
        if (q.kind == "fall") R.string.data_suspect_fall else R.string.data_suspect_rise,
        formatClock(ctx, q.from),
        formatClock(ctx, q.to),
        formatGlucose(q.sgvFrom, units, locale),
        formatGlucose(q.sgvTo, units, locale)
    )
}

/** Who added it: a parent's name, "xDrip", or the meter that sent it; null when unknown. */
@Composable
internal fun whoOf(e: Entry): Pair<String, Boolean>? {
    val tr = e.treatment
    if (tr != null) {
        return when (val o = originOf(tr)) {
            is Origin.Here -> o.who.takeIf { it.isNotBlank() }?.let { it to false }
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
internal fun EntryChip(e: Entry, units: Units, onEdit: (Treatment) -> Unit, showTime: Boolean = false) {
    val ctx = LocalContext.current
    val (ink, bg) = inkOf(e.kind)
    val who = whoOf(e)
    Row(
        Modifier
            .background(bg, RoundedCornerShape(12.dp))
            .then(if (e.treatment != null && e.kind != EntryKind.Suspect) Modifier.clickable { onEdit(e.treatment) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // in an interval, say when exactly; a suspect already carries its span
        val at = if (showTime && e.kind != EntryKind.Suspect) " · " + formatClock(ctx, e.t) else ""
        Text(
            entryText(e, units) + at + (
                who?.let {
                    " · " + it.first
                } ?: ""
                ),
            color = ink,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
        if (who?.second == true) {
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(5.dp).background(DiaTheme.colors.textMuted, CircleShape))
        }
    }
}

/** The time column: "10:46", or "10:46 AM" on a 12-hour clock, on one line. */
@Composable
internal fun timeColumn() = if (DateFormat.is24HourFormat(LocalContext.current)) 50.dp else 68.dp
