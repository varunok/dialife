package com.lumenarcanus.dialife.feature.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.AlertCard
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts

/** The worked-out dose, line by line, with what to look at before injecting. */
@Composable
internal fun DoseCard(
    m: DoseCalc.Meal,
    dose: DoseSettings,
    units: Units,
    locale: java.util.Locale,
    earlier: Int,
    given: Double,
    typedMeter: String,
    onMeter: (String) -> Unit,
    plan: Boolean,
    onPlan: (Boolean) -> Unit,
    openPlan: PlannedDose?
) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().background(
            c.surface,
            RoundedCornerShape(18.dp)
        ).border(1.dp, c.insulinContainer, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DoseWorkings(m, dose, units, locale, earlier, given)
        m.alerts.forEach { AlertCard(it, units, locale) }
        if (!m.fresh) FreshMeterField(typedMeter, units, onMeter)
        ToGive(m, given, locale)
        PlanChoice(m, openPlan, plan, onPlan, locale)
    }
}

/** How the dose was worked out: carbs ÷ ratio, the correction (or why none), the total, what was given already. */
@Composable
private fun DoseWorkings(m: DoseCalc.Meal, dose: DoseSettings, units: Units, locale: java.util.Locale, earlier: Int, given: Double) {
    val c = DiaTheme.colors
    val u = stringResource(R.string.unit_insulin)
    fun f(x: Double) = formatAmount(x, locale)
    Row {
        Text(stringResource(R.string.meal_dose_title), color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.meal_ratio, dose.ratio) + " · " + stringResource(R.string.meal_step, f(dose.step)),
            color = c.textFaint,
            fontSize = 11.5.sp
        )
    }
    Line(
        (
            if (earlier > 0) {
                stringResource(R.string.meal_line_carbs_join, earlier, m.carbs - earlier, m.carbs)
            } else {
                "${m.carbs} " +
                    stringResource(R.string.unit_carbs)
            }
            ) +
            " ÷ ${dose.ratio}",
        String.format(locale, "%.2f", m.food).trimEnd('0').trimEnd(',', '.') + " $u"
    )
    Line(
        if (m.fresh) {
            stringResource(
                R.string.meal_line_corr,
                formatGlucose(m.meter ?: 0, units, locale),
                m.meterAgeMin ?: 0
            )
        } else {
            stringResource(R.string.meal_line_corr_none)
        },
        if (m.fresh) "+" + f(m.corr) + " $u" else stringResource(R.string.meal_not_counted)
    )
    Line(stringResource(R.string.meal_line_total, f(m.raw)), f(m.dose) + " $u")
    if (given > 0) Line(stringResource(R.string.meal_line_given), "−" + f(given) + " $u")
    Text(stringResource(R.string.meal_rule_note, ruleName(dose.rule)), color = c.textFaint, fontSize = 11.5.sp)
}

/** No fresh reading: one typed here counts. */
@Composable
private fun FreshMeterField(typedMeter: String, units: Units, onMeter: (String) -> Unit) {
    val c = DiaTheme.colors
    OutlinedTextField(
        value = typedMeter,
        onValueChange = onMeter,
        label = {
            Text(
                stringResource(
                    R.string.meal_fresh_meter,
                    if (units == Units.Mmol) {
                        stringResource(R.string.unit_mmol)
                    } else {
                        "mg/dL"
                    }
                )
            )
        },
        singleLine = true,
        textStyle = GlucoseHero.copy(fontSize = 22.sp, lineHeight = 26.sp, color = c.text),
        keyboardOptions = KeyboardOptions(keyboardType = if (units == Units.Mmol) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** What to inject (more, when some was given). */
@Composable
private fun ToGive(m: DoseCalc.Meal, given: Double, locale: java.util.Locale) {
    val c = DiaTheme.colors
    val u = stringResource(R.string.unit_insulin)
    fun f(x: Double) = formatAmount(x, locale)
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                if (given > 0) {
                    R.string.meal_to_give_more
                } else {
                    R.string.meal_to_give
                }
            ),
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            (
                if (given > 0 && m.toGive > 0) {
                    "+"
                } else {
                    ""
                }
                ) + f(m.toGive),
            style = GlucoseHero.copy(fontSize = 44.sp, lineHeight = 48.sp, color = c.insulin)
        )
        Text(" $u", color = c.textMuted, fontSize = 16.sp)
    }
}

/** Plan the dose (it waits on home until given), a plan already open, or why it cannot be planned. */
@Composable
private fun PlanChoice(m: DoseCalc.Meal, openPlan: PlannedDose?, plan: Boolean, onPlan: (Boolean) -> Unit, locale: java.util.Locale) {
    val c = DiaTheme.colors
    fun f(x: Double) = formatAmount(x, locale)
    when {
        m.level == DoseCalc.Level.Absurd && m.carbs > 0 -> Text(
            stringResource(R.string.meal_cannot_plan),
            color = c.low,
            fontSize = 13.sp
        )
        openPlan != null -> Text(
            stringResource(R.string.meal_plan_follows, f(openPlan.dose), f(m.toGive)),
            color = c.insulin,
            fontSize = 13.sp
        )
        m.canPlan -> Row(
            Modifier.fillMaxWidth()
                .border(if (plan) 1.5.dp else 1.dp, if (plan) c.insulin else c.textFaint, RoundedCornerShape(14.dp))
                .background(if (plan) c.insulinContainer.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(14.dp))
                .clickable { onPlan(!plan) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Switch(checked = plan, onCheckedChange = onPlan)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.meal_plan, f(m.toGive)),
                    color = c.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(R.string.meal_plan_note), color = c.textMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun Line(label: String, value: String) {
    val c = DiaTheme.colors
    // on one baseline: the number's face sits higher than the label's otherwise
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = c.textMuted, fontSize = 13.5.sp, modifier = Modifier.weight(1f).alignByBaseline())
        Spacer(Modifier.width(12.dp))
        Text(value, style = GlucoseHero.copy(fontSize = 14.sp, lineHeight = 18.sp, color = c.text), modifier = Modifier.alignByBaseline())
    }
}

@Composable
fun ruleName(rule: String): String = stringResource(
    when (rule) {
        "nearest" -> R.string.rule_nearest
        "down" -> R.string.rule_down
        "up" -> R.string.rule_up
        else -> R.string.rule_half_down
    }
)
