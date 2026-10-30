package com.lumenarcanus.dialife.feature.planned

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.AlertCard
import com.lumenarcanus.dialife.core.ui.Confirm
import com.lumenarcanus.dialife.core.ui.LimitAlert
import com.lumenarcanus.dialife.core.ui.Stepper
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.PlannedDose

/**
 * A dose worked out for a meal and not given yet: calm at first, bright after
 * 30 min, and from the hour on it asks whether it was given. "Given" only
 * records what was done, with when; nothing is counted as insulin before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlannedCard(ui: PlannedCardUiState, units: Units, onAction: (PlannedAction) -> Unit) {
    val p = ui.dose
    val dose = ui.settings
    val now = ui.now
    val mealAt = ui.mealAt
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun f(x: Double) = formatAmount(x, locale)
    val u = stringResource(R.string.unit_insulin)
    val mins = ui.minutes
    val loud = ui.loud
    var editing by remember { mutableStateOf(false) }
    var injecting by remember { mutableStateOf(false) }
    var skipping by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("ask") } // the question's chain: ask → recorded → missing
    val line = if (loud) c.high else c.insulin

    Column(
        Modifier.fillMaxWidth()
            .background(if (loud) c.highContainer else c.surface, RoundedCornerShape(20.dp))
            .border(if (loud) 2.dp else 1.5.dp, line, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PlannedSummary(ui, units)
        if (editing) {
            ChangeDose(p, dose, units, onCancel = { editing = false }, onSave = { d ->
                editing = false
                onAction(PlannedAction.Change(p, d))
            })
        } else {
            PlannedButtons(onGiven = { injecting = true }, onChange = { editing = true }, onSkip = { skipping = true })
        }
        if (ui.question != null) {
            DidYouInject(ui, units, step, onStep = { step = it }, onAction = onAction, onSkip = { skipping = true }, onRecord = {
                injecting =
                    true
            })
        }
    }

    if (skipping) {
        AlertDialog(
            onDismissRequest = { skipping = false },
            title = { Text(stringResource(R.string.planned_skip_q)) },
            text = { Text(stringResource(R.string.planned_skip_text, f(p.dose), formatClock(ctx, mealAt))) },
            confirmButton = {
                TextButton(onClick = {
                    skipping = false
                    onAction(PlannedAction.Skip(p))
                }) { Text(stringResource(R.string.planned_skip_yes)) }
            },
            dismissButton = { TextButton(onClick = { skipping = false }) { Text(stringResource(R.string.tr_cancel)) } }
        )
    }
    if (injecting) {
        InjectSheet(p, dose, mealAt, ui.treatments, units, onDone = { tr, isNew ->
            injecting = false
            onAction(PlannedAction.Given(p, tr, isNew))
        }, onBack = { injecting = false })
    }
}

/** Whose meal and who planned it, the dose not given yet, how it was worked out, and a correction made on an old reading. */
@Composable
private fun PlannedSummary(ui: PlannedCardUiState, units: Units) {
    val p = ui.dose
    val dose = ui.settings
    val now = ui.now
    val mealAt = ui.mealAt
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun f(x: Double) = formatAmount(x, locale)
    val u = stringResource(R.string.unit_insulin)
    val mins = ui.minutes
    val loud = ui.loud
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (loud) stringResource(R.string.planned_unconfirmed, mins) else stringResource(R.string.planned_title),
            color = if (loud) c.high else c.insulin,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.planned_meal_at, formatClock(ctx, mealAt)) + if (p.by.isNotBlank()) " · " + p.by else "",
            color = c.textMuted,
            fontSize = 12.sp
        )
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(f(p.dose), style = GlucoseHero.copy(fontSize = 40.sp, lineHeight = 44.sp, color = c.insulin))
        Text(
            " $u · " + stringResource(R.string.planned_not_given),
            color = c.textMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
    Text(
        "${p.carbs} " + stringResource(R.string.unit_carbs) + " → " +
            String.format(locale, "%.2f", p.food).trimEnd('0').trimEnd(',', '.') +
            " $u" +
            (if (p.corr > 0) " + " + stringResource(R.string.planned_corr, f(p.corr)) + " $u" else "") + " → " + f(p.dose) + " $u",
        color = c.textMuted,
        fontSize = 12.5.sp
    )
    // the correction was for the sugar then; before injecting, a fresh reading
    p.meterAt?.let { at ->
        val age = ((now - at) / 60_000L).toInt()
        if (p.corr > 0 && age > dose.freshMin) {
            AlertCard(
                DoseCalc.Alert.RecentRapid(0.0, 0),
                units,
                locale,
                title = stringResource(R.string.planned_meter_old, age),
                text = stringResource(R.string.planned_meter_old_text, f(p.corr))
            )
        }
    }
}

/** Another dose instead: the stepper, its limit with a confirm over it, cancel or save. */
@Composable
private fun ChangeDose(p: PlannedDose, dose: DoseSettings, units: Units, onCancel: () -> Unit, onSave: (Double) -> Unit) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    fun f(x: Double) = formatAmount(x, locale)
    val u = stringResource(R.string.unit_insulin)
    var newDose by remember { mutableStateOf(p.dose) }
    var okEdit by remember { mutableStateOf(false) }
    Stepper(newDose, dose.step, u) {
        newDose = it
        okEdit = false
    }
    val lv = DoseCalc.level(newDose, dose.maxRapid, null)
    LimitAlert(lv, newDose, dose.maxRapid, units, locale)
    if (lv == DoseCalc.Level.High) Confirm(okEdit, stringResource(R.string.meal_ok_big, f(newDose))) { okEdit = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tr_cancel)) }
        Button(
            onClick = { onSave(newDose) },
            enabled = newDose > 0 && lv != DoseCalc.Level.Absurd && (lv != DoseCalc.Level.High || okEdit),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)
        ) { Text(stringResource(R.string.planned_change_save)) }
    }
}

/** Given, change, skip. */
@Composable
private fun PlannedButtons(onGiven: () -> Unit, onChange: () -> Unit, onSkip: () -> Unit) {
    val c = DiaTheme.colors
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = onGiven,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)
        ) {
            Text(stringResource(R.string.planned_given), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        OutlinedButton(onClick = onChange, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = pad) {
            Text(stringResource(R.string.planned_change), color = c.text, maxLines = 1)
        }
        OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = pad) {
            Text(stringResource(R.string.planned_skip), color = c.text, maxLines = 1)
        }
    }
}

/** "Did you inject for the meal at 12:40?", then "is it recorded?", then "record it". */
@Composable
private fun DidYouInject(
    ui: PlannedCardUiState,
    units: Units,
    step: String,
    onStep: (String) -> Unit,
    onAction: (PlannedAction) -> Unit,
    onSkip: () -> Unit,
    onRecord: () -> Unit
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val p = ui.dose
    val mealAt = ui.mealAt
    Column(
        Modifier.fillMaxWidth().background(
            c.surface,
            RoundedCornerShape(14.dp)
        ).border(1.5.dp, c.high, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (step) {
            "ask" -> {
                Text(
                    stringResource(R.string.q_planned_title, formatClock(ctx, mealAt)),
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onStep("recorded") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.planned_yes)) }
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.planned_no), color = c.text)
                    }
                }
            }
            "recorded" -> {
                Text(
                    stringResource(R.string.planned_recorded_q),
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(R.string.planned_recorded_hint), color = c.textMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val found = ui.recorded
                        if (found != null) onAction(PlannedAction.Given(p, found, isNew = false)) else onStep("missing")
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.planned_recorded_yes)) }
                    OutlinedButton(onClick = onRecord, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.planned_recorded_no), color = c.text)
                    }
                }
            }
            else -> {
                AlertCard(
                    DoseCalc.Alert.NoMeter(0),
                    units,
                    locale,
                    title = stringResource(R.string.planned_missing),
                    text = stringResource(R.string.planned_missing_text)
                )
                Button(
                    onClick = onRecord,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)
                ) {
                    Text(stringResource(R.string.planned_record))
                }
            }
        }
    }
}
