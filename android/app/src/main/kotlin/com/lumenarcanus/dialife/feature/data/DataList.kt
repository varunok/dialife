package com.lumenarcanus.dialife.feature.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatDelta
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.PlexMono
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment

// The upright table: rows grouped by hour, each with what was logged around it.

internal fun hourOf(t: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = t
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

@Composable
internal fun HourHeader(hourStart: Long, vs: List<Int>, units: Units) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Row(
        Modifier.fillMaxWidth().background(c.background.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatClock(ctx, hourStart) + "–" + formatClock(ctx, hourStart + HOUR),
            color = c.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (vs.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.data_hour,
                    formatGlucose(vs.average().toInt(), units, locale),
                    formatGlucose(vs.min(), units, locale),
                    formatGlucose(vs.max(), units, locale)
                ),
                color = c.textMuted,
                fontSize = 11.sp,
                fontFamily = PlexMono
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListRow(r: DataRow, units: Units, th: Thresholds, onEdit: (Treatment) -> Unit) {
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatClock(ctx, r.t) + (r.end?.let { "\n" + formatClock(ctx, it) } ?: ""),
            color = c.textMuted,
            fontSize = 12.sp,
            fontFamily = PlexMono,
            lineHeight = 14.sp,
            modifier = Modifier.width(timeColumn())
        )
        Column(Modifier.width(74.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                r.sgv?.let { v ->
                    Text(
                        formatGlucose(v, units, locale),
                        color = valueColor(v, th, c.low, c.inRange, c.high),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (r.end == null) Text(" " + arrowOf(r.delta), color = c.textMuted, fontSize = 12.sp)
                }
            }
            if (r.end != null && r.min != null && r.max != null) {
                Row {
                    Text(
                        formatGlucose(r.min, units, locale),
                        color = if (r.min <
                            th.low
                        ) {
                            c.low
                        } else {
                            c.textMuted
                        },
                        fontSize = 10.5.sp,
                        fontFamily = PlexMono
                    )
                    Text("–", color = c.textFaint, fontSize = 10.5.sp, fontFamily = PlexMono)
                    Text(
                        formatGlucose(r.max, units, locale),
                        color = if (r.max >
                            th.high
                        ) {
                            c.high
                        } else {
                            c.textMuted
                        },
                        fontSize = 10.5.sp,
                        fontFamily = PlexMono
                    )
                }
            }
        }
        Text(
            r.delta?.let {
                formatDelta(it, units, locale)
            }.orEmpty(),
            color = c.textMuted,
            fontSize = 12.sp,
            fontFamily = PlexMono,
            modifier = Modifier.width(44.dp)
        )
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            r.entries.forEach { e -> EntryChip(e, units, onEdit, showTime = r.end != null) }
        }
    }
}
