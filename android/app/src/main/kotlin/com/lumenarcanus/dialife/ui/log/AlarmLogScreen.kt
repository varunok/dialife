package com.lumenarcanus.dialife.ui.log

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.LogEpisode
import com.lumenarcanus.dialife.data.LogEvent
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.PlexMono

private val Periods = listOf(1, 7, 30)

/** What happened at night: every episode, who took it and how fast, what was asked, how it ended. */
@Composable
fun AlarmLogScreen(creds: Credentials, units: Units, onBack: () -> Unit, api: DiaLifeApi = DiaLifeApi()) {
    val c = DiaTheme.colors
    var days by rememberSaveable { mutableIntStateOf(7) }
    var episodes by remember { mutableStateOf<List<LogEpisode>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(days, attempt) {
        failed = false
        episodes = null
        api.alarmLog(creds, days).onSuccess { episodes = it.episodes }.onFailure { failed = true }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(c.background).safeDrawingPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp)) {
                TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
                Text(stringResource(R.string.log_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                PeriodChips(days) { days = it }
            }
        }
        when {
            failed -> item {
                Column {
                    Text(stringResource(R.string.err_network), color = c.high)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.stats_retry)) }
                }
            }
            episodes == null -> item { Text(stringResource(R.string.home_loading), color = c.textMuted) }
            episodes!!.isEmpty() -> item { Text(stringResource(R.string.log_empty), color = c.textMuted) }
            else -> items(episodes!!, key = { it.id }) { EpisodeCard(it, units) }
        }
        item { Spacer(Modifier.heightIn(min = 24.dp)) }
    }
}

@Composable
private fun PeriodChips(days: Int, onPick: (Int) -> Unit) {
    val c = DiaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Periods.forEach { d ->
            val selected = d == days
            Text(
                pluralStringResource(R.plurals.stats_days, d, d),
                color = if (selected) c.text else c.textMuted,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onPick(d) })
                    .heightIn(min = 40.dp)
                    .padding(vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun EpisodeCard(e: LogEpisode, units: Units) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val accent = when {
        e.kind == "stale" -> c.textMuted
        e.kind.endsWith("high") -> c.high
        e.level == "urgent" -> c.urgent
        else -> c.low
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(18.dp))
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .clickable(enabled = e.events.isNotEmpty(), onClickLabel = stringResource(R.string.log_details)) { open = !open }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                AlarmText.kind(ctx, e.asView()) + (e.triggerSgv?.takeIf { e.kind != "stale" }?.let { " · " + formatGlucose(it, units, ctx.resources.configuration.locales[0]) } ?: ""),
                color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            if (e.state != "cleared") Text(stringResource(R.string.log_live), color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(when_(ctx, e), color = c.textMuted, fontSize = 14.sp)
        summary(ctx, e).takeIf { it.isNotBlank() }?.let { Text(it, color = c.text, fontSize = 14.sp) }
        if (open) {
            Spacer(Modifier.heightIn(min = 4.dp))
            e.events.forEach { ev ->
                Row {
                    Text(AlarmText.clock(ctx, ev.t), color = c.textFaint, fontSize = 13.sp, fontFamily = PlexMono, modifier = Modifier.width(56.dp))
                    Text(step(ctx, ev, units), color = c.text, fontSize = 14.sp)
                }
            }
        } else if (e.events.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.log_show_steps, e.events.size, e.events.size), color = c.textFaint, fontSize = 13.sp)
        }
    }
}

/** "сьогодні 02:14–02:48 · 34 хв" */
private fun when_(ctx: Context, e: LogEpisode): String {
    val day = DateUtils.getRelativeTimeSpanString(e.startedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_MONTH)
    val start = AlarmText.clock(ctx, e.startedAt)
    val end = e.clearedAt ?: return ctx.getString(R.string.log_since, day, start)
    val min = ((end - e.startedAt) / 60_000).toInt()
    return ctx.getString(R.string.log_span, day, start, AlarmText.clock(ctx, end)) + " · " +
        ctx.resources.getQuantityString(R.plurals.minutes_short, min, min)
}

/** "взяли за 1 хв · будили всіх ще 1 раз · вів(ла) Тато" */
private fun summary(ctx: Context, e: LogEpisode): String {
    val first = e.events.firstOrNull { it.type == "alarm" }?.t ?: e.startedAt
    val taken = e.events.firstOrNull { it.type == "handled" }
    val rerings = e.events.count { it.type == "alarm" && it.reason != null && it.reason != "new" }
    return buildList {
        taken?.let {
            val m = ((it.t - first) / 60_000).toInt()
            add(ctx.resources.getQuantityString(R.plurals.log_taken_in, m, m))
        }
        if (rerings > 0) add(ctx.resources.getQuantityString(R.plurals.log_rerings, rerings, rerings))
        when {
            e.manual && !e.resolvedBy.isNullOrBlank() -> add(ctx.getString(R.string.clear_resolved_by, e.resolvedBy))
            taken?.by != null -> add(ctx.getString(R.string.clear_handled_by, e.handledBy ?: taken.by))
            e.events.isNotEmpty() && taken == null && e.state == "cleared" -> add(ctx.getString(R.string.log_nobody))
        }
    }.joinToString(" · ")
}

private fun step(ctx: Context, ev: LogEvent, units: Units): String {
    val who = ev.by.orEmpty()
    val v = ev.sgv?.let { " · " + formatGlucose(it, units, ctx.resources.configuration.locales[0]) } ?: ""
    return when (ev.type) {
        "alarm" -> ctx.getString(
            when (ev.reason) {
                "repeat" -> R.string.log_alarm_repeat
                "escalated" -> R.string.log_alarm_escalated
                "worse" -> R.string.log_alarm_worse
                "ceiling" -> R.string.log_alarm_ceiling
                "no_response" -> R.string.log_alarm_no_response
                "quiet_over" -> R.string.log_alarm_quiet_over
                else -> R.string.log_alarm_new
            },
            who,
        ) + v
        "handled" -> ctx.getString(R.string.log_handled, who) + v
        "checkin" -> ctx.getString(R.string.checkin_title) + v
        "checkin_ok" -> ctx.getString(R.string.log_checkin_ok, who)
        "help" -> ctx.getString(R.string.help_title, who)
        "coming" -> ctx.getString(R.string.someone_coming, who)
        "clear" -> ctx.getString(R.string.log_clear) + v
        else -> ev.type
    }
}
