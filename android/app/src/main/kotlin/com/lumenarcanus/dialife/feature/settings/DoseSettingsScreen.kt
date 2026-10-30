package com.lumenarcanus.dialife.feature.settings

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
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.data.DoseCalc

private val Rules = listOf("nearest", "halfDown", "down", "up")
private val Samples = mapOf(1.0 to listOf(2.1, 2.4, 2.5, 2.6, 2.9), 0.5 to listOf(2.1, 2.25, 2.3, 2.7, 2.8))

/**
 * The family's dose calculation: shared on the server, so a change here is
 * the same on every phone. Nothing is saved until "Save".
 */
@Composable
fun DoseSettingsScreen(uiState: DoseSettingsUiState, onEdit: (DoseSettingsForm) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    fun f(x: Double) = formatAmount(x, locale)
    val form = uiState.form
    val units = uiState.units
    val rule = form.rule
    val step = form.step
    val rows = form.rows
    val r = form.ratio.toIntOrNull()
    fun edit(change: DoseSettingsForm.() -> DoseSettingsForm) = onEdit(form.change())
    fun editRows(change: MutableList<Pair<String, String>>.() -> Unit) = edit { copy(rows = rows.toMutableList().apply(change)) }
    val problems = uiState.problems.map { problemText(it, ::f) }

    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back), color = c.textMuted) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.dose_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.dose_shared) +
                    if (uiState.updatedBy.isNotBlank()) " " + stringResource(R.string.dose_changed_by, uiState.updatedBy) else "",
                color = c.textMuted,
                fontSize = 13.sp
            )

            Group(stringResource(R.string.dose_carbs)) {
                NumberRow(
                    stringResource(R.string.dose_ratio_pre),
                    form.ratio,
                    stringResource(R.string.dose_ratio_post),
                    KeyboardType.Number
                ) {
                    edit { copy(ratio = it.filter(Char::isDigit).take(3)) }
                }
            }

            Group(stringResource(R.string.dose_rounding)) {
                Text(stringResource(R.string.dose_round_why), color = c.text, fontSize = 14.sp)
                Text(stringResource(R.string.dose_round_once), color = c.textMuted, fontSize = 13.sp)
                Text(stringResource(R.string.dose_pen_step), color = c.textMuted, fontSize = 13.sp)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(1.0, 0.5).forEachIndexed { i, s ->
                        SegmentedButton(selected = step == s, onClick = {
                            edit { copy(step = s) }
                        }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(
                                f(s) + " " + stringResource(R.string.unit_insulin)
                            )
                        }
                    }
                }
                Rules.forEach { id ->
                    val on = rule == id
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = on, role = Role.RadioButton) {
                            edit { copy(rule = id) }
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(selected = on, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                com.lumenarcanus.dialife.feature.meal.ruleName(id).replaceFirstChar {
                                    it.uppercase()
                                },
                                color = c.text,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(stringResource(ruleDesc(id, step)), color = c.textMuted, fontSize = 12.5.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Samples.getValue(step).forEach { x ->
                                    val o = DoseCalc.round(x, id, step)
                                    Column(
                                        Modifier.weight(1f).background(c.background, RoundedCornerShape(8.dp)).padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(f(x), style = GlucoseHero.copy(fontSize = 12.sp, lineHeight = 14.sp, color = c.textMuted))
                                        Text(
                                            if (o > x + 1e-9) {
                                                "↑"
                                            } else if (o < x - 1e-9) {
                                                "↓"
                                            } else {
                                                "="
                                            },
                                            color = c.textFaint,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            f(o),
                                            style = GlucoseHero.copy(
                                                fontSize = 15.sp,
                                                lineHeight = 18.sp,
                                                color = if (o > x + 1e-9) {
                                                    c.high
                                                } else if (o < x - 1e-9) {
                                                    c.inRange
                                                } else {
                                                    c.text
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                            val gU = stringResource(R.string.unit_carbs)
                            val uU = stringResource(R.string.unit_insulin)
                            r?.takeIf { it > 0 }?.let { ratioG ->
                                Text(
                                    listOf(30, 31, 45).joinToString(" · ") { g ->
                                        "$g $gU → " +
                                            f(DoseCalc.round(g.toDouble() / ratioG, id, step)) +
                                            " $uU"
                                    },
                                    style = GlucoseHero.copy(fontSize = 12.sp, lineHeight = 15.sp, color = c.textMuted)
                                )
                            }
                        }
                    }
                }
                Text(stringResource(R.string.dose_round_source), color = c.textFaint, fontSize = 11.5.sp)
            }

            Group(stringResource(R.string.dose_limits)) {
                NumberRow(
                    stringResource(R.string.dose_max_rapid),
                    form.maxRapid,
                    stringResource(R.string.unit_insulin),
                    KeyboardType.Decimal
                ) {
                    edit { copy(maxRapid = it.take(5)) }
                }
                NumberRow(
                    stringResource(R.string.dose_max_basal),
                    form.maxBasal,
                    stringResource(R.string.unit_insulin),
                    KeyboardType.Decimal
                ) {
                    edit { copy(maxBasal = it.take(5)) }
                }
                NumberRow(
                    stringResource(R.string.dose_max_carbs),
                    form.maxCarbs,
                    stringResource(R.string.unit_carbs),
                    KeyboardType.Number
                ) {
                    edit { copy(maxCarbs = it.filter(Char::isDigit).take(3)) }
                }
                Text(stringResource(R.string.dose_limits_note), color = c.textFaint, fontSize = 12.sp)
            }

            Group(stringResource(R.string.dose_table)) {
                Text(stringResource(R.string.dose_table_note), color = c.textMuted, fontSize = 13.sp)
                val unit = if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL"
                rows.forEachIndexed { i, (mg, u) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            mg,
                            { v ->
                                editRows { this[i] = v.filter { it.isDigit() || it == ',' || it == '.' }.take(5) to u }
                            },
                            label = { Text(stringResource(R.string.dose_from, unit)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        Text("→", color = c.textFaint)
                        OutlinedTextField(
                            u,
                            { v -> editRows { this[i] = mg to v.filter { it.isDigit() || it == ',' || it == '.' }.take(4) } },
                            label = {
                                Text(
                                    "+ " + stringResource(R.string.unit_insulin)
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        val del = stringResource(R.string.dose_row_remove)
                        IconButton(onClick = {
                            editRows { removeAt(i) }
                        }, modifier = Modifier.semantics { contentDescription = del }) { Text("×", fontSize = 22.sp, color = c.textMuted) }
                    }
                }
                OutlinedButton(onClick = { editRows { add("" to "") } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.dose_row_add), color = c.insulin, fontWeight = FontWeight.SemiBold)
                }
                NumberRow(stringResource(R.string.dose_fresh_pre), form.fresh, stringResource(R.string.unit_min), KeyboardType.Number) {
                    edit { copy(fresh = it.filter(Char::isDigit).take(2)) }
                }
                Text(stringResource(R.string.dose_fresh_note), color = c.textFaint, fontSize = 12.sp)
            }
            Group(stringResource(R.string.dose_forecast)) {
                Text(stringResource(R.string.dose_forecast_note), color = c.textMuted, fontSize = 13.sp)
                val unit = if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL"
                NumberRow(stringResource(R.string.dose_isf), form.isf, unit, KeyboardType.Decimal) {
                    edit { copy(isf = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5)) }
                }
                Text(stringResource(R.string.dose_isf_note), color = c.textFaint, fontSize = 12.sp)
                NumberRow(stringResource(R.string.dose_dia), form.dia, stringResource(R.string.unit_min), KeyboardType.Number) {
                    edit { copy(dia = it.filter(Char::isDigit).take(3)) }
                }
                NumberRow(stringResource(R.string.dose_carb_min), form.carbMin, stringResource(R.string.unit_min), KeyboardType.Number) {
                    edit { copy(carbMin = it.filter(Char::isDigit).take(3)) }
                }
            }
            Group(stringResource(R.string.dose_wait)) {
                Text(stringResource(R.string.dose_wait_note), color = c.textMuted, fontSize = 13.sp)
                val unit = if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL"
                NumberRow(stringResource(R.string.dose_wait_min), form.waitMin, stringResource(R.string.unit_min), KeyboardType.Number) {
                    edit { copy(waitMin = it.filter(Char::isDigit).take(2)) }
                }
                NumberRow(stringResource(R.string.dose_wait_high_from), form.waitHighFrom, unit, KeyboardType.Decimal) {
                    edit { copy(waitHighFrom = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5)) }
                }
                NumberRow(
                    stringResource(R.string.dose_wait_high_min),
                    form.waitHighMin,
                    stringResource(R.string.unit_min),
                    KeyboardType.Number
                ) {
                    edit { copy(waitHighMin = it.filter(Char::isDigit).take(2)) }
                }
                NumberRow(stringResource(R.string.dose_wait_low), form.waitLowBelow, unit, KeyboardType.Decimal) {
                    edit { copy(waitLowBelow = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5)) }
                }
                Text(stringResource(R.string.dose_wait_source), color = c.textFaint, fontSize = 12.sp)
            }
            problems.forEach { Text(it, color = c.low, fontSize = 13.sp) }
            when (uiState.saved) {
                true -> Text(stringResource(R.string.dose_saved), color = c.inRange, fontSize = 13.sp)
                false -> Text(stringResource(R.string.dose_save_failed), color = c.low, fontSize = 13.sp)
                null -> {}
            }
        }
        Button(
            onClick = onSave,
            enabled = problems.isEmpty() && !uiState.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)
        ) { Text(stringResource(R.string.dose_save), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun problemText(p: DoseProblem, f: (Double) -> String): String = when (p) {
    DoseProblem.Ratio -> stringResource(R.string.dose_err_ratio)
    DoseProblem.Fresh -> stringResource(R.string.dose_err_fresh)
    DoseProblem.MaxRapid -> stringResource(R.string.dose_err_max_rapid)
    DoseProblem.MaxBasal -> stringResource(R.string.dose_err_max_basal)
    DoseProblem.MaxCarbs -> stringResource(R.string.dose_err_max_carbs)
    DoseProblem.Table -> stringResource(R.string.dose_err_table)
    DoseProblem.Twice -> stringResource(R.string.dose_err_twice)
    is DoseProblem.TableOverMax -> stringResource(R.string.dose_err_table_max, f(p.max))
    DoseProblem.Isf -> stringResource(R.string.dose_err_isf)
    DoseProblem.Dia -> stringResource(R.string.dose_err_dia)
    DoseProblem.CarbMin -> stringResource(R.string.dose_err_carb_min)
    DoseProblem.Wait -> stringResource(R.string.dose_err_wait)
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
        Modifier.fillMaxWidth().background(
            c.surface,
            RoundedCornerShape(22.dp)
        ).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
        OutlinedTextField(
            value,
            onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            textStyle = GlucoseHero.copy(fontSize = 16.sp, lineHeight = 20.sp, color = c.text),
            modifier = Modifier.width(96.dp)
        )
        Text(post, color = c.textMuted, fontSize = 14.sp)
    }
}
