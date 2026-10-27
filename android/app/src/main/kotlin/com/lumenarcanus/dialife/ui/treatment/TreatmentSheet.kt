package com.lumenarcanus.dialife.ui.treatment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.live.TreatmentQueue
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import androidx.compose.ui.focus.focusRequester

enum class TreatmentKind { Insulin, Food, Meter, Activity, Note }

private val InsulinQuick = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
private val BasalQuick = listOf(4.0, 6.0, 8.0, 10.0, 12.0, 14.0)

/**
 * Which insulin a new entry most likely is: long-acting when a scheduled basal
 * time is within 90 min and today's dose for it is not in yet; otherwise rapid.
 */
fun guessInsulinType(now: Long, recent: List<Treatment>): InsulinType {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    val near = Prefs.basalTimes.any { kotlin.math.abs(it - minute) <= 90 || kotlin.math.abs(it - minute) >= 1440 - 90 }
    val givenLately = recent.any { InsulinType.of(it.insulinType) == InsulinType.Basal && (it.insulin ?: 0.0) > 0 && now - it.t < 4 * 3_600_000L }
    return if (near && !givenLately) InsulinType.Basal else InsulinType.Rapid
}
private val MinutesQuick = listOf(15.0, 30.0, 45.0, 60.0, 90.0, 120.0)
private val Intensities = listOf("light" to R.string.act_light, "moderate" to R.string.act_moderate, "vigorous" to R.string.act_vigorous)
private val TimeShifts = listOf(0, 15, 30, 60) // minutes ago

/** A typed glucometer value in mg/dL, or null when it is not a plausible reading. */
fun parseMeter(text: String, units: Units): Int? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    val mg = if (units == Units.Mmol) kotlin.math.round(v * 18.0).toInt() else kotlin.math.round(v).toInt()
    return mg.takeIf { it in 20..600 }
}

/** 1.5 → "1,5" in Ukrainian, "1.5" in English; whole numbers without a decimal. */
fun formatAmount(v: Double, locale: java.util.Locale): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(locale, "%.1f", v)

/**
 * Add or edit insulin, food or a note. Works offline: the entry goes to a
 * queue and reaches the server when the phone is online again.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TreatmentSheet(
    initialKind: TreatmentKind,
    editing: Treatment?,
    onSave: (Treatment) -> Unit,
    onDismiss: () -> Unit,
    recent: List<Treatment> = emptyList(), // the day's entries: last basal dose, what is due now
    units: Units = Units.MgDl, // a glucometer value is typed in these
    initialTime: Long? = null, // a question's "log it": when the rise or fall began
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val startKind = when {
        editing == null -> initialKind
        editing.glucose != null -> TreatmentKind.Meter
        editing.duration != null -> TreatmentKind.Activity
        (editing.insulin ?: 0.0) > 0 -> TreatmentKind.Insulin
        (editing.carbs ?: 0.0) > 0 -> TreatmentKind.Food
        else -> TreatmentKind.Note
    }
    var kind by rememberSaveable { mutableStateOf(startKind) }
    var insulin by rememberSaveable { mutableDoubleStateOf(editing?.insulin ?: 0.0) }
    val lastBasal = recent.lastOrNull { InsulinType.of(it.insulinType) == InsulinType.Basal && (it.insulin ?: 0.0) > 0 }?.insulin
    var insType by rememberSaveable {
        mutableStateOf(editing?.let { InsulinType.of(it.insulinType) } ?: guessInsulinType(System.currentTimeMillis(), recent))
    }
    // long-acting is usually the same dose every day: start from the last one
    androidx.compose.runtime.LaunchedEffect(insType) {
        if (editing == null && insType == InsulinType.Basal && insulin == 0.0 && lastBasal != null) insulin = lastBasal
    }
    val foodRows = remember { androidx.compose.runtime.mutableStateListOf<FoodRow>().apply { addAll(foodRowsOf(editing?.foodItems, editing?.carbs)) } }
    val carbs = foodRows.sumOf { it.grams ?: 0 }.toDouble()
    var meter by rememberSaveable { mutableStateOf(editing?.glucose?.let { formatGlucose(it, units, locale) }.orEmpty()) }
    var minutes by rememberSaveable { mutableDoubleStateOf(editing?.duration?.toDouble() ?: 30.0) }
    var intensity by rememberSaveable { mutableStateOf(editing?.intensity ?: "moderate") }
    var note by rememberSaveable { mutableStateOf(editing?.notes.orEmpty()) }
    val now = remember { System.currentTimeMillis() }
    var time by rememberSaveable { mutableLongStateOf(editing?.t ?: initialTime ?: now) }

    // "… is giving insulin" on the other phones while this form is on insulin
    val repo = (ctx.applicationContext as? com.lumenarcanus.dialife.DiaLifeApp)?.live // null in previews and tests
    val live = repo?.live?.collectAsState()?.value?.state
    val announce = editing == null && kind == TreatmentKind.Insulin
    var announced by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(announce, insType) {
        if (announce) repo?.insulinIntent(insType.wire) else if (announced) repo?.insulinIntentEnd()
        announced = announce
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (announced) repo?.insulinIntentEnd() } }
    val others = live?.intents.orEmpty().filter { it.deviceId != repo?.myId && it.until > System.currentTimeMillis() }
    var confirming by remember { mutableStateOf<NearDose?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (editing != null) R.string.tr_edit_title else R.string.tr_add_title),
                color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            if (editing == null) {
                // five kinds do not fit one segmented row on a phone: chips that wrap
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TreatmentKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = {
                                Text(stringResource(when (k) {
                                    TreatmentKind.Insulin -> R.string.tr_insulin
                                    TreatmentKind.Food -> R.string.tr_food
                                    TreatmentKind.Meter -> R.string.tr_meter
                                    TreatmentKind.Activity -> R.string.tr_activity
                                    TreatmentKind.Note -> R.string.tr_note
                                }))
                            },
                        )
                    }
                }
            }

            when (kind) {
                TreatmentKind.Insulin -> {
                if (others.isNotEmpty()) IntentWarning(others, System.currentTimeMillis())
                if (editing == null) nearDose(recent, insType, time, null)?.takeIf { it.level == DoseLevel.Recent }?.let { RecentDoseNote(it, live) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    InsulinType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = insType == t,
                            onClick = { insType = t },
                            shape = SegmentedButtonDefaults.itemShape(i, InsulinType.entries.size),
                        ) {
                            Text(
                                stringResource(if (t == InsulinType.Basal) R.string.ins_basal else R.string.ins_rapid) + (Prefs.nameOf(t)?.let { " · $it" } ?: ""),
                                maxLines = 1, fontSize = 13.sp,
                            )
                        }
                    }
                }
                val basal = insType == InsulinType.Basal
                Amount(
                    value = insulin, step = if (basal) 1.0 else 0.5, max = 80.0, unit = stringResource(R.string.unit_insulin),
                    quick = if (basal) BasalQuick else InsulinQuick,
                    accent = if (basal) basalColor(c.isDark) else c.insulin, container = if (basal) basalContainer(c.isDark) else c.insulinContainer, locale = locale,
                    label = stringResource(R.string.tr_insulin),
                ) { insulin = it }
                }
                TreatmentKind.Food -> FoodList(foodRows, repo)
                TreatmentKind.Meter -> MeterField(meter, units) { meter = it }
                TreatmentKind.Activity -> {
                    Text(stringResource(R.string.act_intensity), color = c.textMuted, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Intensities.forEach { (key, label) ->
                            FilterChip(selected = intensity == key, onClick = { intensity = key }, label = { Text(stringResource(label)) })
                        }
                    }
                    Amount(
                        value = minutes, step = 5.0, max = 600.0, unit = stringResource(R.string.unit_min),
                        quick = MinutesQuick, accent = c.inRange, container = c.inRangeContainer, locale = locale,
                        label = stringResource(R.string.act_duration),
                    ) { minutes = it }
                }
                TreatmentKind.Note -> {}
            }

            // when: now, a little earlier, or any time picked on the clock
            Text(stringResource(R.string.tr_when), color = c.textMuted, fontSize = 13.sp)
            var picking by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shifts = if (editing == null) TimeShifts.map { now - it * 60_000L } else emptyList()
                if (editing == null) {
                    TimeShifts.forEachIndexed { i, m ->
                        FilterChip(
                            selected = time == shifts[i],
                            onClick = { time = shifts[i] },
                            label = {
                                Text(if (m == 0) stringResource(R.string.tr_now) else stringResource(R.string.tr_minutes_ago, m))
                            },
                        )
                    }
                }
                val custom = time !in shifts
                FilterChip(
                    selected = custom,
                    onClick = { picking = true },
                    label = {
                        Text(if (custom) stringResource(R.string.tr_at, AlarmText.clock(ctx, time)) else stringResource(R.string.tr_other_time))
                    },
                )
            }
            if (picking) {
                TimeDialog(initial = time, onPick = { time = it; picking = false }, onDismiss = { picking = false })
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text(stringResource(R.string.tr_note_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (kind == TreatmentKind.Note) 3 else 1,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            val amountOk = when (kind) {
                TreatmentKind.Insulin -> insulin > 0
                TreatmentKind.Food -> carbs > 0
                TreatmentKind.Meter -> parseMeter(meter, units) != null
                TreatmentKind.Activity -> minutes > 0
                TreatmentKind.Note -> note.isNotBlank()
            }
            fun build(confirmedNew: String?) = Treatment(
                id = editing?.id ?: TreatmentQueue.newId(),
                t = time,
                // an edit keeps the other half of a meal bolus untouched
                insulin = if (kind == TreatmentKind.Insulin) insulin else editing?.insulin?.takeIf { kind == TreatmentKind.Food },
                carbs = if (kind == TreatmentKind.Food) carbs else editing?.carbs?.takeIf { kind == TreatmentKind.Insulin },
                glucose = if (kind == TreatmentKind.Meter) parseMeter(meter, units) else null,
                duration = if (kind == TreatmentKind.Activity) minutes.toInt() else null,
                intensity = if (kind == TreatmentKind.Activity) intensity else null,
                notes = note.trim().ifBlank { null },
                insulinType = if (kind == TreatmentKind.Insulin) insType.wire else editing?.insulinType,
                insulinName = if (kind == TreatmentKind.Insulin) {
                    editing?.insulinName?.takeIf { InsulinType.of(editing.insulinType) == insType } ?: Prefs.nameOf(insType)
                } else editing?.insulinName,
                confirmedNew = confirmedNew,
                foodItems = if (kind == TreatmentKind.Food) foodItemsOf(foodRows) else editing?.foodItems?.takeIf { kind == TreatmentKind.Insulin },
            )
            confirming?.let { near ->
                SameDoseDialog(
                    near, insType, live,
                    onSame = { confirming = null; onDismiss() }, // the same injection: nothing new to log
                    onNew = { confirming = null; onSave(build(near.dose.id)) },
                    onBack = { confirming = null },
                )
            }
            if (kind == TreatmentKind.Food) FoodTotal(foodRows)
            Button(
                onClick = {
                    val near = if (editing == null && kind == TreatmentKind.Insulin) nearDose(recent, insType, time, null) else null
                    if (near != null) confirming = near else onSave(build(null))
                },
                enabled = amountOk,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text(stringResource(R.string.tr_save), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * The clock dial. A time later than now means yesterday: at 00:20 the dinner
 * at 23:40 is still entered as "23:40".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val start = java.util.Calendar.getInstance().apply { timeInMillis = initial }
    val state = rememberTimePickerState(
        initialHour = start.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = start.get(java.util.Calendar.MINUTE),
        is24Hour = android.text.format.DateFormat.is24HourFormat(ctx),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    set(java.util.Calendar.HOUR_OF_DAY, state.hour)
                    set(java.util.Calendar.MINUTE, state.minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis > now + 60_000L) add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
                onPick(cal.timeInMillis)
            }) { Text(stringResource(R.string.tr_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_cancel)) } },
        title = { Text(stringResource(R.string.tr_pick_time)) },
        text = { TimePicker(state = state) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Amount(
    value: Double,
    step: Double,
    max: Double,
    unit: String,
    quick: List<Double>,
    accent: androidx.compose.ui.graphics.Color,
    container: androidx.compose.ui.graphics.Color,
    locale: java.util.Locale,
    label: String,
    onChange: (Double) -> Unit,
) {
    val c = DiaTheme.colors
    val shown = formatAmount(value, locale)
    Row(
        Modifier.fillMaxWidth().background(container, RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val minus = stringResource(R.string.tr_less)
        val plus = stringResource(R.string.tr_more)
        FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(0.0)) }, modifier = Modifier.size(56.dp).semantics { contentDescription = minus }) {
            Text("−", fontSize = 28.sp, color = accent)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label $shown $unit" }) {
            Text(shown, style = GlucoseHero.copy(fontSize = 64.sp, lineHeight = 64.sp, color = c.text))
            Spacer(Modifier.width(6.dp))
            Text(unit, color = c.textMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max)) }, modifier = Modifier.size(56.dp).semantics { contentDescription = plus }) {
            Text("+", fontSize = 28.sp, color = accent)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quick.forEach { q ->
            FilterChip(
                selected = value == q,
                onClick = { onChange(q) },
                label = { Text(formatAmount(q, locale) + " " + unit) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = container, selectedLabelColor = accent),
                shape = CircleShape,
            )
        }
    }
}

/** The glucometer value, typed: big digits like the amounts, in the phone's units. */
@Composable
private fun MeterField(text: String, units: Units, onChange: (String) -> Unit) {
    val c = DiaTheme.colors
    val unit = if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL"
    val label = stringResource(R.string.tr_meter_value, unit)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ',' || it == '.' }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        textStyle = GlucoseHero.copy(fontSize = 48.sp, lineHeight = 52.sp, color = c.text),
        suffix = { Text(unit, color = c.textMuted, fontSize = 18.sp) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (units == Units.Mmol) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** Long-acting insulin's own colour: blue, so it never reads as the rapid one. */
fun basalColor(dark: Boolean) = if (dark) androidx.compose.ui.graphics.Color(0xFF7FB2FF) else androidx.compose.ui.graphics.Color(0xFF1F5FCC)
fun basalContainer(dark: Boolean) = if (dark) androidx.compose.ui.graphics.Color(0xFF0E1C33) else androidx.compose.ui.graphics.Color(0xFFE3EDFF)
