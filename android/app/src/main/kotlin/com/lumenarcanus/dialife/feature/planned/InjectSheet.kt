package com.lumenarcanus.dialife.feature.planned

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.feature.entry.NearDose
import com.lumenarcanus.dialife.feature.entry.TimeDialog
import com.lumenarcanus.dialife.feature.entry.atLabel
import com.lumenarcanus.dialife.feature.entry.nearDose
import com.lumenarcanus.dialife.live.TreatmentQueue

/** "Given": how much, and when, within reason; the same-dose guard and the limits as everywhere. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun InjectSheet(
    p: PlannedDose,
    dose: DoseSettings,
    mealAt: Long,
    treatments: List<Treatment>,
    units: Units,
    onDone: (Treatment, isNew: Boolean) -> Unit,
    onBack: () -> Unit,
    // a fixed moment in the screenshot tests
    clock: () -> Long = System::currentTimeMillis
) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val ctx = LocalContext.current
    fun f(x: Double) = formatAmount(x, locale)
    val u = stringResource(R.string.unit_insulin)
    val opened = remember { clock() }
    var amount by remember { mutableStateOf(p.dose) }
    var whenIdx by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf(false) }
    var another by remember { mutableStateOf(false) }
    val at = when (whenIdx) {
        0 -> opened
        1 -> opened - 15 * 60_000L
        else -> picked ?: opened
    }
    val earliest = mealAt - 60 * 60_000L
    val err = when {
        at > opened + 60_000L -> stringResource(R.string.tr_time_ahead)
        at < earliest -> stringResource(R.string.planned_too_early)
        else -> null
    }
    val near = nearDose(treatments, InsulinType.Rapid, at, null)?.takeIf { !another && it.dose.mealId != p.mealId }
    val lv = DoseCalc.level(amount, dose.maxRapid, null)
    val needOk = lv == DoseCalc.Level.High || lv == DoseCalc.Level.Absurd

    ModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.planned_given_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (near != null) {
                SameInjection(near, units, onSame = { onDone(near.dose, false) }, onAnother = { another = true }, onBack = onBack)
                return@Column
            }
            Text(stringResource(R.string.planned_given_note), color = c.textMuted, fontSize = 13.5.sp)
            Stepper(amount, dose.step, u) {
                amount = it
                ok = false
            }
            if (amount != p.dose) {
                Text(stringResource(R.string.planned_given_differs, f(p.dose), f(amount)), color = c.high, fontSize = 12.5.sp)
            }
            LimitAlert(lv, amount, dose.maxRapid, units, locale)
            if (needOk) Confirm(ok, stringResource(R.string.planned_ok_given, f(amount))) { ok = it }
            InjectWhen(whenIdx, at, err, earliest, onIdx = { whenIdx = it }, onPick = { picking = true })
            Button(
                onClick = {
                    onDone(
                        Treatment(
                            id = TreatmentQueue.newId(),
                            t = at,
                            insulin = amount,
                            insulinType = InsulinType.Rapid.wire,
                            insulinName = Prefs.nameOf(InsulinType.Rapid),
                            mealId = p.mealId,
                            insulinRole = "meal"
                        ),
                        true
                    )
                },
                enabled = err == null && amount > 0 && (!needOk || ok),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)
            ) { Text(stringResource(R.string.planned_given_save, f(amount), formatClock(ctx, at)), fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tr_cancel)) }
        }
    }
    if (picking) {
        TimeDialog(initial = at, onPick = {
            picked = it
            whenIdx = 2
            picking = false
        }, onDismiss = { picking = false })
    }
}

/** A rapid dose like this one is logged already: the same injection, or another one? */
@Composable
private fun SameInjection(near: NearDose, units: Units, onSame: () -> Unit, onAnother: () -> Unit, onBack: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun f(x: Double) = formatAmount(x, locale)
    AlertCard(
        DoseCalc.Alert.NoMeter(0),
        units,
        locale,
        title = stringResource(
            R.string.planned_same_q,
            f(near.dose.insulin ?: 0.0),
            formatClock(ctx, near.dose.t),
            near.dose.enteredBy.orEmpty()
        ),
        text = stringResource(R.string.planned_same_text)
    )
    OutlinedButton(onClick = onSame, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.planned_same_yes), color = c.text)
    }
    Button(
        onClick = onAnother,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)
    ) {
        Text(stringResource(R.string.planned_same_no))
    }
}

/** When it was given: now, 15 min ago, or a time picked; from an hour before the meal up to now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InjectWhen(whenIdx: Int, at: Long, err: String?, earliest: Long, onIdx: (Int) -> Unit, onPick: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    Text(stringResource(R.string.planned_when), color = c.textMuted, fontSize = 13.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = whenIdx == 0, onClick = { onIdx(0) }, label = { Text(stringResource(R.string.tr_now)) })
        FilterChip(selected = whenIdx == 1, onClick = { onIdx(1) }, label = { Text(stringResource(R.string.tr_minutes_ago, 15)) })
        FilterChip(selected = whenIdx == 2, onClick = onPick, label = {
            Text(
                if (whenIdx == 2) {
                    atLabel(at)
                } else {
                    stringResource(R.string.tr_other_time)
                }
            )
        })
    }
    Text(
        err ?: stringResource(R.string.planned_when_range, formatClock(ctx, earliest)),
        color = if (err != null) {
            c.low
        } else {
            c.textFaint
        },
        fontSize = 12.sp
    )
}
