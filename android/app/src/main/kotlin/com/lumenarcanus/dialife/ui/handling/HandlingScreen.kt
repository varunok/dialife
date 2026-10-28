package com.lumenarcanus.dialife.ui.handling

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.EpisodeRules
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.ActionReceiver
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.live.Notifications
import com.lumenarcanus.dialife.live.TreatmentQueue
import com.lumenarcanus.dialife.ui.home.ChartWindow
import com.lumenarcanus.dialife.ui.home.GlucoseChart
import com.lumenarcanus.dialife.ui.home.HomeUi
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatDelta
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.ui.treatment.TreatmentKind
import com.lumenarcanus.dialife.ui.treatment.TreatmentSheet

private val QuickCarbs = listOf(10.0, 15.0, 20.0)

/**
 * "You are handling it": everything the person at the child's bed needs —
 * how it moves since they took it, the question to answer, carbs in one tap,
 * and exactly what would wake the others.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandlingScreen(
    ui: HomeUi,
    alarmId: Long,
    units: Units,
    onAct: (action: String, alarmId: Long) -> Unit,
    onSaveTreatment: (Treatment) -> Unit,
    onBack: () -> Unit,
    onOpenMeal: () -> Unit = {}, // "another amount": the food screen, without a dose
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = ctx.resources.configuration.locales[0]
    val haptics = LocalHapticFeedback.current
    val a = ui.state?.alarms?.firstOrNull { it.id == alarmId }
    // the episode ended or someone else took it over: nothing to handle here any more
    LaunchedEffect(a?.state, a?.handledById, ui.state == null) {
        if (ui.state != null && (a == null || a.handledById != ui.myId)) onBack()
    }
    if (a == null) return
    val rules = ui.state.episode ?: EpisodeRules()
    val bg = ui.state.bg
    var sheet by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var lastCarbs by remember { mutableStateOf<Double?>(null) }

    if (sheet) {
        LaunchedEffect(Unit) { sheet = false; onOpenMeal() }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.act_resolve)) },
            text = { Text(stringResource(R.string.hd_end_body)) },
            confirmButton = { TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); confirmEnd = false; onAct(ActionReceiver.RESOLVE, a.id) }) { Text(stringResource(R.string.hd_end_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.tr_cancel)) } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
        Text(stringResource(R.string.you_handle, AlarmText.kind(ctx, a)), color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        // now, and how it moved since you took it
        val now = bg?.sgv ?: a.sgv
        Row(verticalAlignment = Alignment.Bottom) {
            Text(now?.let { formatGlucose(it, units, locale) } ?: "—", style = GlucoseHero.copy(fontSize = 88.sp, lineHeight = 88.sp, color = c.text))
            Spacer(Modifier.width(10.dp))
            Text(Notifications.arrowOf(bg?.direction), color = c.textMuted, fontSize = 40.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        if (a.handledSgv != null && now != null) {
            Text(
                stringResource(R.string.hd_since_taken, formatGlucose(a.handledSgv, units, locale), formatDelta(now - a.handledSgv, units, locale)),
                color = c.text, fontSize = 16.sp,
            )
        }
        a.handledAt?.let {
            val min = ((ui.now - it) / 60_000).toInt().coerceAtLeast(0)
            Text(stringResource(R.string.hd_taken_at, AlarmText.clock(ctx, it), ctx.resources.getQuantityString(R.plurals.minutes_short, min, min)), color = c.textMuted, fontSize = 14.sp)
        }
        when {
            a.comingAt != null -> Text(stringResource(R.string.someone_coming, a.comingBy.orEmpty()), color = c.inRange, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            a.helpAt != null -> Text(stringResource(R.string.hd_help_asked), color = c.high, fontSize = 15.sp)
        }

        // the question, when it is asked; otherwise when it will be
        if (a.checkinPending) {
            Column(
                Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(20.dp)).padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.checkin_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                a.checkinSentAt?.let {
                    Text(stringResource(R.string.hd_checkin_deadline, AlarmText.clock(ctx, it + rules.checkinTimeoutMin * 60_000L)), color = c.high, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAct(ActionReceiver.OK, a.id) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.inRange, contentColor = c.background),
                    ) { Text(stringResource(R.string.act_ok), fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAct(ActionReceiver.HELP, a.id) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.high, contentColor = c.background),
                    ) { Text(stringResource(R.string.act_help), fontWeight = FontWeight.SemiBold) }
                }
            }
        } else {
            a.nextCheckinAt?.takeIf { it > ui.now }?.let {
                Text(stringResource(R.string.hd_next_checkin, AlarmText.clock(ctx, it)), color = c.textMuted, fontSize = 14.sp)
            }
        }

        // carbs in one tap: the most common thing during a hypo
        if (a.kind == "low" || a.kind == "predicted_low") {
            Card {
                Text(stringResource(R.string.hd_carbs_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickCarbs.forEach { g ->
                        AssistChip(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                onSaveTreatment(Treatment(TreatmentQueue.newId(), System.currentTimeMillis(), carbs = g))
                                lastCarbs = g
                            },
                            label = { Text(stringResource(R.string.hd_carbs_g, g.toInt()), fontSize = 15.sp) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = c.carbsContainer, labelColor = c.carbs),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                    AssistChip(onClick = { sheet = true }, label = { Text(stringResource(R.string.hd_carbs_other)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
                lastCarbs?.let {
                    Text(stringResource(R.string.hd_carbs_saved, it.toInt()), color = c.inRange, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }

        // the episode on a chart, from a little before you took it
        val from = (a.handledAt ?: a.startedAt) - 15 * 60_000L
        Card {
            GlucoseChart(
                history = ui.history, forecast = ui.state.ar2, thresholds = ui.state.thresholds, units = units,
                rangeHours = 3, now = ui.now,
                window = ChartWindow(from, maxOf(ui.now + 30 * 60_000L, from + 3_600_000L), followsNow = true),
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }

        // exactly what wakes the others, so nobody is surprised
        Card {
            Text(stringResource(R.string.hd_wake_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (a.handledSgv != null && (a.kind == "low" || a.kind == "high")) {
                val limit = if (a.kind == "low") a.handledSgv - rules.worseBy else a.handledSgv + rules.worseBy
                Bullet(stringResource(if (a.kind == "low") R.string.hd_wake_worse_low else R.string.hd_wake_worse_high, formatGlucose(limit, units, locale)))
            }
            if (a.level == "urgent" && a.handledAt != null) {
                Bullet(stringResource(R.string.hd_wake_ceiling, AlarmText.clock(ctx, a.handledAt + rules.ceilingMin * 60_000L), rules.ceilingMin))
            }
            Bullet(stringResource(R.string.hd_wake_no_answer, rules.checkinTimeoutMin))
            val others = ui.state.caregivers.filter { it.id != ui.myId }.map { it.name }
            if (others.isNotEmpty()) Text(stringResource(R.string.hd_wake_who, others.joinToString(", ")), color = c.textMuted, fontSize = 13.sp)
        }

        if (a.helpAt == null) {
            OutlinedButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAct(ActionReceiver.HELP, a.id) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.act_help), color = c.high)
            }
        }
        Button(
            onClick = { confirmEnd = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.surfaceRaised, contentColor = c.text),
        ) { Text(stringResource(R.string.act_resolve), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Bullet(text: String) {
    val c = DiaTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = c.textMuted, fontSize = 14.sp, modifier = Modifier.width(16.dp))
        Text(text, color = c.text, fontSize = 14.sp)
    }
}
