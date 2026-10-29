package com.lumenarcanus.dialife.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.CorrectionRow
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.ui.treatment.formatAmount
import kotlinx.coroutines.launch

private val Rules = listOf("nearest", "halfDown", "down", "up")
private val Samples = mapOf(1.0 to listOf(2.1, 2.4, 2.5, 2.6, 2.9), 0.5 to listOf(2.1, 2.25, 2.3, 2.7, 2.8))

private fun num(text: String) = text.trim().replace(',', '.').toDoubleOrNull()

/**
 * The family's dose calculation: shared on the server, so a change here is
 * the same on every phone. Nothing is saved until "Save".
 */
@Composable
fun DoseSettingsScreen(dose: DoseSettings, units: Units, onSave: suspend (DoseSettings) -> Result<DoseSettings>, onBack: () -> Unit) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    fun f(x: Double) = formatAmount(x, locale)
    var ratio by remember { mutableStateOf(dose.ratio.toString()) }
    var rule by remember { mutableStateOf(dose.rule) }
    var step by remember { mutableStateOf(dose.step) }
    var fresh by remember { mutableStateOf(dose.freshMin.toString()) }
    var maxRapid by remember { mutableStateOf(f(dose.maxRapid)) }
    var maxBasal by remember { mutableStateOf(f(dose.maxBasal)) }
    var maxCarbs by remember { mutableStateOf(dose.maxCarbs.toString()) }
    var isf by remember { mutableStateOf(if (dose.isf > 0) formatGlucose(dose.isf, units, locale) else "") }
    var dia by remember { mutableStateOf(dose.diaMin.toString()) }
    var carbMin by remember { mutableStateOf(dose.carbMin.toString()) }
    var waitMin by remember { mutableStateOf(dose.waitMin.toString()) }
    var waitHighMin by remember { mutableStateOf(dose.waitHighMin.toString()) }
    var waitHighFrom by remember { mutableStateOf(formatGlucose(dose.waitHighFrom, units, locale)) }
    var waitLowBelow by remember { mutableStateOf(formatGlucose(dose.waitLowBelow, units, locale)) }
    // rows as typed: the mg/dL shown in this phone's units
    val rows = remember { mutableStateListOf<Pair<String, String>>().apply { dose.table.forEach { add(formatGlucose(it.mg, units, locale) to f(it.u)) } } }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }

    fun mgOf(text: String): Int? = num(text)?.let { v -> (if (units == Units.Mmol) kotlin.math.round(v * 18.0) else kotlin.math.round(v)).toInt() }
    val table = rows.map { (mg, u) -> mgOf(mg) to num(u) }
    val r = ratio.toIntOrNull()
    val problems = buildList {
        if (r == null || r !in 1..100) add(stringResource(R.string.dose_err_ratio))
        if (fresh.toIntOrNull()?.let { it in 1..60 } != true) add(stringResource(R.string.dose_err_fresh))
        val mr = num(maxRapid)
        if (mr == null || mr < 0.5 || mr > 100) add(stringResource(R.string.dose_err_max_rapid))
        if (num(maxBasal)?.let { it in 0.5..200.0 } != true) add(stringResource(R.string.dose_err_max_basal))
        if (maxCarbs.toIntOrNull()?.let { it in 20..500 } != true) add(stringResource(R.string.dose_err_max_carbs))
        if (table.any { (mg, u) -> mg == null || mg !in 40..600 || u == null || u < 0 || u > 50 }) add(stringResource(R.string.dose_err_table))
        val mgs = table.mapNotNull { it.first }
        if (mgs.size != mgs.toSet().size) add(stringResource(R.string.dose_err_twice))
        if (mr != null && table.any { (_, u) -> u != null && u > mr }) add(stringResource(R.string.dose_err_table_max, f(mr)))
        if (isf.isNotBlank() && mgOf(isf)?.let { it in 5..400 } != true) add(stringResource(R.string.dose_err_isf))
        if (dia.toIntOrNull()?.let { it in 120..480 } != true) add(stringResource(R.string.dose_err_dia))
        if (carbMin.toIntOrNull()?.let { it in 60..360 } != true) add(stringResource(R.string.dose_err_carb_min))
        val wm = waitMin.toIntOrNull()
        val whm = waitHighMin.toIntOrNull()
        val low = mgOf(waitLowBelow)
        val high = mgOf(waitHighFrom)
        if (wm == null || wm !in 0..45 || whm == null || whm !in wm..60 || low == null || low !in 60..120 || high == null || high <= low || high > 300)
            add(stringResource(R.string.dose_err_wait))
    }

    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back), color = c.textMuted) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.dose_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.dose_shared) + if (dose.updatedBy.isNotBlank()) " " + stringResource(R.string.dose_changed_by, dose.updatedBy) else "",
                color = c.textMuted, fontSize = 13.sp,
            )

            Group(stringResource(R.string.dose_carbs)) {
                NumberRow(stringResource(R.string.dose_ratio_pre), ratio, stringResource(R.string.dose_ratio_post), KeyboardType.Number) { ratio = it.filter(Char::isDigit).take(3) }
            }

            Group(stringResource(R.string.dose_rounding)) {
                Text(stringResource(R.string.dose_round_why), color = c.text, fontSize = 14.sp)
                Text(stringResource(R.string.dose_round_once), color = c.textMuted, fontSize = 13.sp)
                Text(stringResource(R.string.dose_pen_step), color = c.textMuted, fontSize = 13.sp)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(1.0, 0.5).forEachIndexed { i, s ->
                        SegmentedButton(selected = step == s, onClick = { step = s }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(f(s) + " " + stringResource(R.string.unit_insulin)) }
                    }
                }
                Rules.forEach { id ->
                    val on = rule == id
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = on, role = Role.RadioButton) { rule = id }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = on, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(com.lumenarcanus.dialife.ui.meal.ruleName(id).replaceFirstChar { it.uppercase() }, color = c.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(ruleDesc(id, step)), color = c.textMuted, fontSize = 12.5.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Samples.getValue(step).forEach { x ->
                                    val o = DoseCalc.round(x, id, step)
                                    Column(
                                        Modifier.weight(1f).background(c.background, RoundedCornerShape(8.dp)).padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(f(x), style = GlucoseHero.copy(fontSize = 12.sp, lineHeight = 14.sp, color = c.textMuted))
                                        Text(if (o > x + 1e-9) "↑" else if (o < x - 1e-9) "↓" else "=", color = c.textFaint, fontSize = 11.sp)
                                        Text(f(o), style = GlucoseHero.copy(fontSize = 15.sp, lineHeight = 18.sp, color = if (o > x + 1e-9) c.high else if (o < x - 1e-9) c.inRange else c.text))
                                    }
                                }
                            }
                            val gU = stringResource(R.string.unit_carbs)
                            val uU = stringResource(R.string.unit_insulin)
                            r?.takeIf { it > 0 }?.let { ratioG ->
                                Text(listOf(30, 31, 45).joinToString(" · ") { g -> "$g $gU → " + f(DoseCalc.round(g.toDouble() / ratioG, id, step)) + " $uU" },
                                    style = GlucoseHero.copy(fontSize = 12.sp, lineHeight = 15.sp, color = c.textMuted))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.dose_round_source), color = c.textFaint, fontSize = 11.5.sp)
            }

            Group(stringResource(R.string.dose_limits)) {
                NumberRow(stringResource(R.string.dose_max_rapid), maxRapid, stringResource(R.string.unit_insulin), KeyboardType.Decimal) { maxRapid = it.take(5) }
                NumberRow(stringResource(R.string.dose_max_basal), maxBasal, stringResource(R.string.unit_insulin), KeyboardType.Decimal) { maxBasal = it.take(5) }
                NumberRow(stringResource(R.string.dose_max_carbs), maxCarbs, stringResource(R.string.unit_carbs), KeyboardType.Number) { maxCarbs = it.filter(Char::isDigit).take(3) }
                Text(stringResource(R.string.dose_limits_note), color = c.textFaint, fontSize = 12.sp)
            }

            Group(stringResource(R.string.dose_table)) {
                Text(stringResource(R.string.dose_table_note), color = c.textMuted, fontSize = 13.sp)
                val unit = if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL"
                rows.forEachIndexed { i, (mg, u) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(mg, { v -> rows[i] = v.filter { it.isDigit() || it == ',' || it == '.' }.take(5) to u }, label = { Text(stringResource(R.string.dose_from, unit)) },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        Text("→", color = c.textFaint)
                        OutlinedTextField(u, { v -> rows[i] = mg to v.filter { it.isDigit() || it == ',' || it == '.' }.take(4) }, label = { Text("+ " + stringResource(R.string.unit_insulin)) },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        val del = stringResource(R.string.dose_row_remove)
                        IconButton(onClick = { rows.removeAt(i) }, modifier = Modifier.semantics { contentDescription = del }) { Text("×", fontSize = 22.sp, color = c.textMuted) }
                    }
                }
                OutlinedButton(onClick = { rows.add("" to "") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.dose_row_add), color = c.insulin, fontWeight = FontWeight.SemiBold)
                }
                NumberRow(stringResource(R.string.dose_fresh_pre), fresh, stringResource(R.string.unit_min), KeyboardType.Number) { fresh = it.filter(Char::isDigit).take(2) }
                Text(stringResource(R.string.dose_fresh_note), color = c.textFaint, fontSize = 12.sp)
            }
            Group(stringResource(R.string.dose_forecast)) {
                Text(stringResource(R.string.dose_forecast_note), color = c.textMuted, fontSize = 13.sp)
                val unit = if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL"
                NumberRow(stringResource(R.string.dose_isf), isf, unit, KeyboardType.Decimal) { isf = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5) }
                Text(stringResource(R.string.dose_isf_note), color = c.textFaint, fontSize = 12.sp)
                NumberRow(stringResource(R.string.dose_dia), dia, stringResource(R.string.unit_min), KeyboardType.Number) { dia = it.filter(Char::isDigit).take(3) }
                NumberRow(stringResource(R.string.dose_carb_min), carbMin, stringResource(R.string.unit_min), KeyboardType.Number) { carbMin = it.filter(Char::isDigit).take(3) }
            }
            Group(stringResource(R.string.dose_wait)) {
                Text(stringResource(R.string.dose_wait_note), color = c.textMuted, fontSize = 13.sp)
                val unit = if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL"
                NumberRow(stringResource(R.string.dose_wait_min), waitMin, stringResource(R.string.unit_min), KeyboardType.Number) { waitMin = it.filter(Char::isDigit).take(2) }
                NumberRow(stringResource(R.string.dose_wait_high_from), waitHighFrom, unit, KeyboardType.Decimal) { waitHighFrom = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5) }
                NumberRow(stringResource(R.string.dose_wait_high_min), waitHighMin, stringResource(R.string.unit_min), KeyboardType.Number) { waitHighMin = it.filter(Char::isDigit).take(2) }
                NumberRow(stringResource(R.string.dose_wait_low), waitLowBelow, unit, KeyboardType.Decimal) { waitLowBelow = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5) }
                Text(stringResource(R.string.dose_wait_source), color = c.textFaint, fontSize = 12.sp)
            }
            problems.forEach { Text(it, color = c.low, fontSize = 13.sp) }
            when (result) {
                true -> Text(stringResource(R.string.dose_saved), color = c.inRange, fontSize = 13.sp)
                false -> Text(stringResource(R.string.dose_save_failed), color = c.low, fontSize = 13.sp)
                null -> {}
            }
        }
        Button(
            onClick = {
                saving = true; result = null
                val d = DoseSettings(
                    ratio = r!!, rule = rule, step = step, freshMin = fresh.toInt(),
                    maxRapid = num(maxRapid)!!, maxBasal = num(maxBasal)!!, maxCarbs = maxCarbs.toInt(),
                    table = table.map { (mg, u) -> CorrectionRow(mg!!, u!!) }.sortedBy { it.mg },
                    isf = if (isf.isBlank()) 0 else mgOf(isf)!!, diaMin = dia.toInt(), carbMin = carbMin.toInt(),
                    waitMin = waitMin.toInt(), waitHighMin = waitHighMin.toInt(), waitHighFrom = mgOf(waitHighFrom)!!, waitLowBelow = mgOf(waitLowBelow)!!,
                )
                scope.launch { result = onSave(d).isSuccess; saving = false }
            },
            enabled = problems.isEmpty() && !saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background),
        ) { Text(stringResource(R.string.dose_save), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

private fun ruleDesc(rule: String, step: Double): Int = when (rule) {
    "nearest" -> if (step == 1.0) R.string.rule_nearest_1 else R.string.rule_nearest_5
    "down" -> if (step == 1.0) R.string.rule_down_1 else R.string.rule_down_5
    "up" -> if (step == 1.0) R.string.rule_up_1 else R.string.rule_up_5
    else -> if (step == 1.0) R.string.rule_half_down_1 else R.string.rule_half_down_5
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(22.dp)).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun NumberRow(pre: String, value: String, post: String, type: KeyboardType, onChange: (String) -> Unit) {
    val c = DiaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(pre, color = c.text, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value, onChange, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type),
            textStyle = GlucoseHero.copy(fontSize = 16.sp, lineHeight = 20.sp, color = c.text), modifier = Modifier.width(96.dp))
        Text(post, color = c.textMuted, fontSize = 14.sp)
    }
}
