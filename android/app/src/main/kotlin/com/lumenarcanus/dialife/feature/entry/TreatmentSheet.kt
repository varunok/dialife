package com.lumenarcanus.dialife.feature.entry

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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
import com.lumenarcanus.dialife.core.ui.Confirm
import com.lumenarcanus.dialife.core.ui.LimitAlert
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Treatment

enum class TreatmentKind { Insulin, Food, Meter, Activity, Note }

private val InsulinQuick = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
private val BasalQuick = listOf(4.0, 6.0, 8.0, 10.0, 12.0, 14.0)

/**
 * Which insulin a new entry most likely is: long-acting when a scheduled basal
 * time is within 90 min and today's dose for it is not in yet; otherwise rapid.
 */
fun guessInsulinType(now: Long, recent: List<Treatment>, basalTimes: List<Int> = Prefs.basalTimes): InsulinType {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    val near = basalTimes.any { kotlin.math.abs(it - minute) <= 90 || kotlin.math.abs(it - minute) >= 1440 - 90 }
    val givenLately = recent.any {
        InsulinType.of(it.insulinType) == InsulinType.Basal &&
            (it.insulin ?: 0.0) > 0 &&
            now - it.t < 4 * 3_600_000L
    }
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
    dose: com.lumenarcanus.dialife.data.DoseSettings = com.lumenarcanus.dialife.data.DoseSettings(), // the dose limits
    // the server's: what makes one meal
    mealRules: com.lumenarcanus.dialife.data.MealRules = com.lumenarcanus.dialife.data.MealRules(),
    // the live state: who else is giving insulin, the doses on board
    state: com.lumenarcanus.dialife.data.StateResponse? = null,
    // this phone's device id: its own "giving insulin" is not a warning
    myId: Long = 0,
    // "… is giving insulin" on the other phones: the type while this form is on insulin, null when it stops
    onAnnounce: (InsulinType?) -> Unit = {},
    // food suggestions and the carb tables; null in previews and tests
    foods: com.lumenarcanus.dialife.core.data.FoodRepository? = null
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val form = rememberTreatmentFormState(initialKind, editing, recent, units, initialTime)
    val kind = form.kind
    // long-acting is usually the same dose every day: start from the last one
    androidx.compose.runtime.LaunchedEffect(form.insType) { form.startFromLastBasal() }

    // "… is giving insulin" on the other phones while this form is on insulin
    val live = state
    val announce = editing == null && kind == TreatmentKind.Insulin
    var announced by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(announce, form.insType) {
        if (announce) {
            onAnnounce(form.insType)
        } else if (announced) {
            onAnnounce(null)
        }
        announced = announce
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (announced) onAnnounce(null) } }
    val others = live?.intents.orEmpty().filter { it.deviceId != myId && it.until > System.currentTimeMillis() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // the kind was chosen by the button that opened the form (or by the entry being edited)
            val look = kindLook(kind, form.insType)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KindSign(look.glyph, look.ink, 30.dp, background = look.fill)
                Column {
                    Text(stringResource(look.name), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (editing != null) Text(stringResource(R.string.tr_edit_title), color = c.textMuted, fontSize = 12.5.sp)
                }
            }

            when (kind) {
                TreatmentKind.Insulin -> {
                    if (others.isNotEmpty()) IntentWarning(others, System.currentTimeMillis())
                    if (editing == null) {
                        nearDose(recent, form.insType, form.time, null)?.takeIf {
                            it.level == DoseLevel.Recent
                        }?.let { RecentDoseNote(it, live) }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        InsulinType.entries.forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = form.insType == t,
                                onClick = { form.insType = t },
                                shape = SegmentedButtonDefaults.itemShape(i, InsulinType.entries.size),
                                // its chart letter instead of the tick: І or Д, so a wrong one is seen
                                icon = {
                                    kindLook(TreatmentKind.Insulin, t).let { l -> KindSign(l.glyph, l.ink, 20.dp, background = l.fill) }
                                }
                            ) {
                                Text(
                                    stringResource(if (t == InsulinType.Basal) R.string.ins_basal else R.string.ins_rapid) +
                                        (Prefs.nameOf(t)?.let { " · $it" } ?: ""),
                                    maxLines = 1,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    val basal = form.insType == InsulinType.Basal
                    // rapid: for a meal or a correction; the meal near it chooses, a tap changes it
                    val nearMeal = remember(recent, form.time) { form.nearMeal(mealRules) }
                    if (!basal) {
                        val role = form.role(mealRules)
                        Text(stringResource(R.string.ins_for), color = c.textMuted, fontSize = 13.sp)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("meal" to R.string.ins_for_meal, "corr" to R.string.ins_for_corr).forEachIndexed { i, (r, label) ->
                                SegmentedButton(selected = role == r, onClick = {
                                    form.insRole = r
                                }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                            }
                        }
                        if (form.insRole == null) {
                            Text(
                                if (nearMeal != null) {
                                    stringResource(R.string.ins_for_auto_meal, formatClock(ctx, nearMeal.from))
                                } else {
                                    stringResource(R.string.ins_for_auto_corr)
                                },
                                color = c.textFaint,
                                fontSize = 12.sp
                            )
                        }
                        if (role == "meal" && nearMeal == null) {
                            Text(stringResource(R.string.ins_for_meal_none), color = c.high, fontSize = 12.5.sp)
                        }
                    }
                    Amount(
                        value = form.insulin, step = if (basal) 1.0 else 0.5, max = 80.0, unit = stringResource(R.string.unit_insulin),
                        quick = if (basal) BasalQuick else InsulinQuick,
                        accent = if (basal) {
                            basalColor(
                                c.isDark
                            )
                        } else {
                            c.insulin
                        },
                        container = if (basal) basalContainer(c.isDark) else c.insulinContainer, locale = locale,
                        label = stringResource(R.string.tr_insulin)
                    ) {
                        form.changeInsulin(it)
                    }
                    val lv = form.level(dose)
                    LimitAlert(lv, form.insulin, form.limit(dose), units, locale)
                    if (lv == com.lumenarcanus.dialife.data.DoseCalc.Level.High ||
                        lv == com.lumenarcanus.dialife.data.DoseCalc.Level.Absurd
                    ) {
                        Confirm(
                            form.okBig,
                            stringResource(R.string.planned_ok_given, formatAmount(form.insulin, locale))
                        ) {
                            form.okBig = it
                        }
                    }
                }
                TreatmentKind.Food -> FoodList(form.foodRows, foods)
                TreatmentKind.Meter -> MeterField(form.meter, units) { form.meter = it }
                TreatmentKind.Activity -> {
                    Text(stringResource(R.string.act_intensity), color = c.textMuted, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Intensities.forEach { (key, label) ->
                            FilterChip(selected = form.intensity == key, onClick = {
                                form.intensity = key
                            }, label = { Text(stringResource(label)) })
                        }
                    }
                    Amount(
                        value = form.minutes, step = 5.0, max = 600.0, unit = stringResource(R.string.unit_min),
                        quick = MinutesQuick, accent = c.inRange, container = c.inRangeContainer, locale = locale,
                        label = stringResource(R.string.act_duration)
                    ) { form.minutes = it }
                }
                TreatmentKind.Note -> {}
            }

            // when: now, a little earlier, or any time picked on the clock
            Text(stringResource(R.string.tr_when), color = c.textMuted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shifts = if (editing == null) TimeShifts.map { form.openedAt - it * 60_000L } else emptyList()
                if (editing == null) {
                    TimeShifts.forEachIndexed { i, m ->
                        FilterChip(
                            selected = form.time == shifts[i],
                            onClick = { form.time = shifts[i] },
                            label = {
                                Text(if (m == 0) stringResource(R.string.tr_now) else stringResource(R.string.tr_minutes_ago, m))
                            }
                        )
                    }
                }
                val custom = form.time !in shifts
                FilterChip(
                    selected = custom,
                    onClick = { form.picking = true },
                    label = {
                        Text(if (custom) atLabel(form.time) else stringResource(R.string.tr_other_time))
                    }
                )
            }
            if (form.picking) {
                TimeDialog(initial = form.time, onPick = {
                    form.time = it
                    form.picking = false
                }, onDismiss = { form.picking = false })
            }

            OutlinedTextField(
                value = form.note,
                onValueChange = { form.note = it.take(200) },
                label = { Text(stringResource(R.string.tr_note_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (kind == TreatmentKind.Note) 3 else 1,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )

            form.confirming?.let { near ->
                SameDoseDialog(
                    near,
                    form.insType,
                    live,
                    onSame = {
                        form.confirming = null
                        onDismiss()
                    }, // the same injection: nothing new to log
                    onNew = {
                        form.confirming = null
                        onSave(form.build(near.dose.id, mealRules))
                    },
                    onBack = { form.confirming = null }
                )
            }
            if (kind == TreatmentKind.Food) FoodTotal(form.foodRows)
            Button(
                onClick = {
                    val near = form.nearOnSave()
                    if (near != null) form.confirming = near else onSave(form.build(null, mealRules))
                },
                enabled = form.amountOk(dose),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text(stringResource(R.string.tr_save), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * The moment a time on the clock dial means. Yesterday only when today's would
 * be more than 12 h ahead: at 00:20 the dinner at 23:40 is still entered as
 * "23:40". Null for a time still to come, which no entry can have.
 */
fun pickedTime(now: Long, hour: Int, minute: Int): Long? {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis - now > 12 * 3_600_000L) add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    return cal.timeInMillis.takeIf { it <= now + 60_000L }
}

/** Days from [t]'s calendar day to [now]'s: 0 today, 1 yesterday. */
fun daysAgo(t: Long, now: Long): Int {
    fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return java.time.temporal.ChronoUnit.DAYS.between(day(t), day(now)).toInt()
}

/** "At 14:30", "Yesterday at 14:30", or with the date when older. */
@Composable
fun atLabel(t: Long): String {
    val ctx = LocalContext.current
    val clock = formatClock(ctx, t)
    return when (daysAgo(t, System.currentTimeMillis())) {
        0 -> stringResource(R.string.tr_at, clock)
        1 -> stringResource(R.string.tr_yesterday_at, clock)
        else -> stringResource(R.string.tr_at, android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date(t)) + " " + clock)
    }
}

/** The clock dial; a time still to come cannot be confirmed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val start = java.util.Calendar.getInstance().apply { timeInMillis = initial }
    val state = rememberTimePickerState(
        initialHour = start.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = start.get(java.util.Calendar.MINUTE),
        is24Hour = android.text.format.DateFormat.is24HourFormat(ctx)
    )
    val picked = pickedTime(System.currentTimeMillis(), state.hour, state.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { pickedTime(System.currentTimeMillis(), state.hour, state.minute)?.let(onPick) },
                enabled = picked != null
            ) { Text(stringResource(R.string.tr_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_cancel)) } },
        title = { Text(stringResource(R.string.tr_pick_time)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                if (picked == null) Text(stringResource(R.string.tr_time_ahead), color = DiaTheme.colors.textMuted, fontSize = 13.sp)
            }
        }
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
    onChange: (Double) -> Unit
) {
    val c = DiaTheme.colors
    val shown = formatAmount(value, locale)
    Row(
        Modifier.fillMaxWidth().background(container, RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val minus = stringResource(R.string.tr_less)
        val plus = stringResource(R.string.tr_more)
        FilledTonalIconButton(
            onClick = { onChange((value - step).coerceAtLeast(0.0)) },
            modifier = Modifier.size(56.dp).semantics {
                contentDescription = minus
            }
        ) {
            Text("−", fontSize = 28.sp, color = accent)
        }
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "$label $shown $unit"
            }
        ) {
            Text(shown, style = GlucoseHero.copy(fontSize = 64.sp, lineHeight = 64.sp, color = c.text))
            Spacer(Modifier.width(6.dp))
            Text(unit, color = c.textMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            modifier = Modifier.size(56.dp).semantics {
                contentDescription = plus
            }
        ) {
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
                shape = CircleShape
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
            keyboardType = if (units == Units.Mmol) {
                androidx.compose.ui.text.input.KeyboardType.Decimal
            } else {
                androidx.compose.ui.text.input.KeyboardType.Number
            }
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
    )
}

/** Long-acting insulin's own colour: blue, so it never reads as the rapid one. */
fun basalColor(dark: Boolean) = if (dark) androidx.compose.ui.graphics.Color(0xFF7FB2FF) else androidx.compose.ui.graphics.Color(0xFF1F5FCC)
fun basalContainer(dark: Boolean) =
    if (dark) androidx.compose.ui.graphics.Color(0xFF0E1C33) else androidx.compose.ui.graphics.Color(0xFFE3EDFF)
