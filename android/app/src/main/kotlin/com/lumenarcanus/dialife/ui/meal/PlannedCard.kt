package com.lumenarcanus.dialife.ui.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Meals
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.live.TreatmentQueue
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.ui.treatment.TimeDialog
import com.lumenarcanus.dialife.ui.treatment.atLabel
import com.lumenarcanus.dialife.ui.treatment.formatAmount
import com.lumenarcanus.dialife.ui.treatment.nearDose

/** What a parent did with a planned dose. */
sealed interface PlannedAction {
    val planned: PlannedDose
    /** Injected: [insulin] is the entry to link, new (to save) or already there. */
    data class Given(override val planned: PlannedDose, val insulin: Treatment, val isNew: Boolean) : PlannedAction
    data class Change(override val planned: PlannedDose, val dose: Double) : PlannedAction
    data class Skip(override val planned: PlannedDose) : PlannedAction
}

private const val LOUD_AFTER_MIN = 30

/**
 * A dose worked out for a meal and not given yet: calm at first, bright after
 * 30 min, and from the hour on it asks whether it was given. "Given" only
 * records what was done, with when; nothing is counted as insulin before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlannedCard(
    p: PlannedDose,
    dose: DoseSettings,
    now: Long,
    treatments: List<Treatment>,
    question: Question?,
    units: Units,
    onPlanned: (PlannedAction) -> Unit,
    onSaveTreatment: (Treatment) -> Unit,
    mealRules: com.lumenarcanus.dialife.data.MealRules = com.lumenarcanus.dialife.data.MealRules(), // the server's: which rapid is a meal's
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun f(x: Double) = formatAmount(x, locale)
    val u = stringResource(R.string.unit_insulin)
    val mealAt = treatments.firstOrNull { it.id == p.mealId }?.t ?: p.createdAt
    val mins = ((now - p.createdAt) / 60_000L).toInt()
    val loud = mins >= LOUD_AFTER_MIN
    var editing by remember { mutableStateOf(false) }
    var newDose by remember { mutableStateOf(p.dose) }
    var okEdit by remember { mutableStateOf(false) }
    var injecting by remember { mutableStateOf(false) }
    var skipping by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("ask") } // the question's chain: ask → recorded → missing
    val line = if (loud) c.high else c.insulin

    Column(
        Modifier.fillMaxWidth()
            .background(if (loud) c.highContainer else c.surface, RoundedCornerShape(20.dp))
            .border(if (loud) 2.dp else 1.5.dp, line, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // the title has the whole width (it is long in some languages), whose meal and who under it
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (loud) stringResource(R.string.planned_unconfirmed, mins) else stringResource(R.string.planned_title),
                color = if (loud) c.high else c.insulin, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.planned_meal_at, AlarmText.clock(ctx, mealAt)) + if (p.by.isNotBlank()) " · " + p.by else "", color = c.textMuted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f(p.dose), style = GlucoseHero.copy(fontSize = 40.sp, lineHeight = 44.sp, color = c.insulin))
            Text(" $u · " + stringResource(R.string.planned_not_given), color = c.textMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            "${p.carbs} " + stringResource(R.string.unit_carbs) + " → " + String.format(locale, "%.2f", p.food).trimEnd('0').trimEnd(',', '.') + " $u" +
                (if (p.corr > 0) " + " + stringResource(R.string.planned_corr, f(p.corr)) + " $u" else "") + " → " + f(p.dose) + " $u",
            color = c.textMuted, fontSize = 12.5.sp,
        )
        // the correction was for the sugar then; before injecting, a fresh reading
        p.meterAt?.let { at ->
            val age = ((now - at) / 60_000L).toInt()
            if (p.corr > 0 && age > dose.freshMin) AlertCard(DoseCalc.Alert.RecentRapid(0.0, 0), units, locale, title = stringResource(R.string.planned_meter_old, age),
                text = stringResource(R.string.planned_meter_old_text, f(p.corr)))
        }

        if (editing) {
            Stepper(newDose, dose.step, u) { newDose = it; okEdit = false }
            val lv = DoseCalc.level(newDose, dose.maxRapid, null)
            LimitAlert(lv, newDose, dose.maxRapid, units, locale)
            if (lv == DoseCalc.Level.High) Confirm(okEdit, stringResource(R.string.meal_ok_big, f(newDose))) { okEdit = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editing = false; newDose = p.dose }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tr_cancel)) }
                Button(
                    onClick = { editing = false; onPlanned(PlannedAction.Change(p, newDose)) },
                    enabled = newDose > 0 && lv != DoseCalc.Level.Absurd && (lv != DoseCalc.Level.High || okEdit),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background),
                ) { Text(stringResource(R.string.planned_change_save)) }
            }
        } else {
            val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { injecting = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = pad,
                    colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)) { Text(stringResource(R.string.planned_given), fontWeight = FontWeight.SemiBold, maxLines = 1) }
                OutlinedButton(onClick = { editing = true; newDose = p.dose }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = pad) { Text(stringResource(R.string.planned_change), color = c.text, maxLines = 1) }
                OutlinedButton(onClick = { skipping = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = pad) { Text(stringResource(R.string.planned_skip), color = c.text, maxLines = 1) }
            }
        }

        if (question != null) Column(
            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).border(1.5.dp, c.high, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step) {
                "ask" -> {
                    Text(stringResource(R.string.q_planned_title, AlarmText.clock(ctx, mealAt)), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { step = "recorded" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.planned_yes)) }
                        OutlinedButton(onClick = { skipping = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.planned_no), color = c.text) }
                    }
                }
                "recorded" -> {
                    Text(stringResource(R.string.planned_recorded_q), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.planned_recorded_hint), color = c.textMuted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val found = nearRapid(treatments, mealAt, now, mealRules)
                            if (found != null) onPlanned(PlannedAction.Given(p, found, isNew = false)) else step = "missing"
                        }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.planned_recorded_yes)) }
                        OutlinedButton(onClick = { injecting = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.planned_recorded_no), color = c.text) }
                    }
                }
                else -> {
                    AlertCard(DoseCalc.Alert.NoMeter(0), units, locale, title = stringResource(R.string.planned_missing), text = stringResource(R.string.planned_missing_text))
                    Button(onClick = { injecting = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)) { Text(stringResource(R.string.planned_record)) }
                }
            }
        }
    }

    if (skipping) AlertDialog(
        onDismissRequest = { skipping = false },
        title = { Text(stringResource(R.string.planned_skip_q)) },
        text = { Text(stringResource(R.string.planned_skip_text, f(p.dose), AlarmText.clock(ctx, mealAt))) },
        confirmButton = { TextButton(onClick = { skipping = false; onPlanned(PlannedAction.Skip(p)) }) { Text(stringResource(R.string.planned_skip_yes)) } },
        dismissButton = { TextButton(onClick = { skipping = false }) { Text(stringResource(R.string.tr_cancel)) } },
    )
    if (injecting) InjectSheet(p, dose, mealAt, treatments, units, onDone = { tr, isNew ->
        injecting = false
        onPlanned(PlannedAction.Given(p, tr, isNew))
    }, onBack = { injecting = false })
}

/** Rapid near a meal that no other meal has: what "it is recorded" refers to. */
private fun nearRapid(treatments: List<Treatment>, mealAt: Long, now: Long, rules: com.lumenarcanus.dialife.data.MealRules): Treatment? =
    treatments.filter { Meals.isRapid(it) && it.insulinRole != "corr" && it.mealId == null && it.t in (mealAt - rules.beforeMs)..now }
        .minByOrNull { kotlin.math.abs(it.t - mealAt) }

/** "Given": how much, and when, within reason; the same-dose guard and the limits as everywhere. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InjectSheet(
    p: PlannedDose,
    dose: DoseSettings,
    mealAt: Long,
    treatments: List<Treatment>,
    units: Units,
    onDone: (Treatment, isNew: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val ctx = LocalContext.current
    fun f(x: Double) = formatAmount(x, locale)
    val u = stringResource(R.string.unit_insulin)
    val opened = remember { System.currentTimeMillis() }
    var amount by remember { mutableStateOf(p.dose) }
    var whenIdx by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf(false) }
    var another by remember { mutableStateOf(false) }
    val at = when (whenIdx) { 0 -> opened; 1 -> opened - 15 * 60_000L; else -> picked ?: opened }
    val earliest = mealAt - 60 * 60_000L
    val err = when {
        at > opened + 60_000L -> stringResource(R.string.tr_time_ahead)
        at < earliest -> stringResource(R.string.planned_too_early)
        else -> null
    }
    val near = nearDose(treatments, InsulinType.Rapid, at, null)?.takeIf { !another && it.dose.mealId != p.mealId }
    val lv = DoseCalc.level(amount, dose.maxRapid, null)
    val needOk = lv == DoseCalc.Level.High || lv == DoseCalc.Level.Absurd

    ModalBottomSheet(onDismissRequest = onBack, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.planned_given_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (near != null) {
                AlertCard(DoseCalc.Alert.NoMeter(0), units, locale,
                    title = stringResource(R.string.planned_same_q, f(near.dose.insulin ?: 0.0), AlarmText.clock(ctx, near.dose.t), near.dose.enteredBy.orEmpty()),
                    text = stringResource(R.string.planned_same_text))
                OutlinedButton(onClick = { onDone(near.dose, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.planned_same_yes), color = c.text) }
                Button(onClick = { another = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)) { Text(stringResource(R.string.planned_same_no)) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tr_cancel)) }
                return@Column
            }
            Text(stringResource(R.string.planned_given_note), color = c.textMuted, fontSize = 13.5.sp)
            Stepper(amount, dose.step, u) { amount = it; ok = false }
            if (amount != p.dose) Text(stringResource(R.string.planned_given_differs, f(p.dose), f(amount)), color = c.high, fontSize = 12.5.sp)
            LimitAlert(lv, amount, dose.maxRapid, units, locale)
            if (needOk) Confirm(ok, stringResource(R.string.planned_ok_given, f(amount))) { ok = it }
            Text(stringResource(R.string.planned_when), color = c.textMuted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = whenIdx == 0, onClick = { whenIdx = 0 }, label = { Text(stringResource(R.string.tr_now)) })
                FilterChip(selected = whenIdx == 1, onClick = { whenIdx = 1 }, label = { Text(stringResource(R.string.tr_minutes_ago, 15)) })
                FilterChip(selected = whenIdx == 2, onClick = { picking = true }, label = { Text(if (whenIdx == 2) atLabel(at) else stringResource(R.string.tr_other_time)) })
            }
            Text(err ?: stringResource(R.string.planned_when_range, AlarmText.clock(ctx, earliest)), color = if (err != null) c.low else c.textFaint, fontSize = 12.sp)
            Button(
                onClick = {
                    onDone(Treatment(
                        id = TreatmentQueue.newId(), t = at, insulin = amount, insulinType = InsulinType.Rapid.wire,
                        insulinName = Prefs.nameOf(InsulinType.Rapid), mealId = p.mealId, insulinRole = "meal",
                    ), true)
                },
                enabled = err == null && amount > 0 && (!needOk || ok),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background),
            ) { Text(stringResource(R.string.planned_given_save, f(amount), AlarmText.clock(ctx, at)), fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tr_cancel)) }
        }
    }
    if (picking) TimeDialog(initial = at, onPick = { picked = it; whenIdx = 2; picking = false }, onDismiss = { picking = false })
}

@Composable
fun Stepper(value: Double, step: Double, unit: String, onChange: (Double) -> Unit) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth().background(c.insulinContainer, RoundedCornerShape(18.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val minus = stringResource(R.string.tr_less)
        val plus = stringResource(R.string.tr_more)
        FilledTonalIconButton(onClick = { onChange((Math.round((value - step) * 10) / 10.0).coerceAtLeast(0.0)) }, modifier = Modifier.size(52.dp).semantics { contentDescription = minus }) {
            Text("−", fontSize = 26.sp, color = c.insulin)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text(formatAmount(value, locale), style = GlucoseHero.copy(fontSize = 48.sp, lineHeight = 52.sp, color = c.text))
            Text(" $unit", color = c.textMuted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        FilledTonalIconButton(onClick = { onChange((Math.round((value + step) * 10) / 10.0).coerceAtMost(200.0)) }, modifier = Modifier.size(52.dp).semantics { contentDescription = plus }) {
            Text("+", fontSize = 26.sp, color = c.insulin)
        }
    }
}

/** The dose limits' card: red over the limit, red "a mistake?" over twice it. */
@Composable
fun LimitAlert(level: DoseCalc.Level, dose: Double, max: Double, units: Units, locale: java.util.Locale) {
    when (level) {
        DoseCalc.Level.Absurd -> AlertCard(DoseCalc.Alert.DoseAbsurd(dose, max), units, locale)
        DoseCalc.Level.High -> AlertCard(DoseCalc.Alert.DoseHigh(dose, max), units, locale)
        else -> {}
    }
}

/** The extra tap before a dose over the limit. */
@Composable
fun Confirm(on: Boolean, text: String, onChange: (Boolean) -> Unit) {
    val c = DiaTheme.colors
    Row(
        Modifier.fillMaxWidth().border(1.5.dp, c.low, RoundedCornerShape(14.dp)).clickable { onChange(!on) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = onChange)
        Text(text, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
