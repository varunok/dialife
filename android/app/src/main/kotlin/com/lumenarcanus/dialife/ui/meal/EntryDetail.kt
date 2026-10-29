package com.lumenarcanus.dialife.ui.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Meals
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.ui.treatment.basalColor
import com.lumenarcanus.dialife.ui.treatment.formatAmount

/**
 * What a tapped chart mark is, under the chart: a meal's dishes and how its
 * dose compares with what was given; an injection and what it was for.
 */
@Composable
fun EntryDetail(tr: Treatment, treatments: List<Treatment>, dose: DoseSettings, units: Units, mealRules: com.lumenarcanus.dialife.data.MealRules = com.lumenarcanus.dialife.data.MealRules()) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun f(x: Double) = formatAmount(x, locale)
    val u = stringResource(R.string.unit_insulin)
    val g = stringResource(R.string.unit_carbs)
    val meals = Meals.group(treatments, mealRules)
    val meal = meals.firstOrNull { m -> m.foods.any { it.id == tr.id } || m.insulin.any { it.id == tr.id } }
    val who = tr.enteredBy?.takeIf { it.isNotBlank() }

    Column(
        Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (title, ink) = when {
            (tr.insulin ?: 0.0) > 0 -> {
                val basal = InsulinType.of(tr.insulinType) == InsulinType.Basal
                ((tr.insulinName ?: Prefs.nameOf(if (basal) InsulinType.Basal else InsulinType.Rapid) ?: stringResource(if (basal) R.string.ins_basal else R.string.ins_rapid)) +
                    " " + f(tr.insulin ?: 0.0) + " $u") to (if (basal) basalColor(c.isDark) else c.insulin)
            }
            (tr.carbs ?: 0.0) > 0 -> stringResource(R.string.detail_food, (tr.carbs ?: 0.0).toInt()) to c.carbs
            tr.glucose != null -> stringResource(R.string.detail_meter, formatGlucose(tr.glucose, units, locale)) to c.inRange
            tr.duration != null -> stringResource(R.string.tr_activity) + " · " + stringResource(R.string.data_step_min, tr.duration) to c.inRange
            else -> stringResource(R.string.tr_note) to c.text
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(title, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(listOfNotNull(AlarmText.clock(ctx, tr.t), who).joinToString(" · "), color = c.textMuted, fontSize = 12.sp)
        }

        // the dishes, every line whole
        tr.foodItems?.takeIf { it.isNotEmpty() }?.forEach { item ->
            Row {
                Text(listOfNotNull(item.name, item.portion?.takeIf { it.isNotBlank() }).joinToString(", "), color = c.text, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text((if (item.suggested) "≈ " else "") + (item.carbs?.let { kotlin.math.round(it).toInt().toString() } ?: "—") + " $g",
                    style = GlucoseHero.copy(fontSize = 14.sp, lineHeight = 18.sp, color = c.carbs))
            }
        }

        if ((tr.insulin ?: 0.0) > 0 && InsulinType.of(tr.insulinType) == InsulinType.Rapid) {
            val forMeal = meal?.takeIf { m -> m.insulin.any { it.id == tr.id } }
            Text(
                when {
                    tr.insulinRole == "corr" -> stringResource(R.string.detail_for_corr)
                    forMeal != null -> stringResource(R.string.detail_for_meal, AlarmText.clock(ctx, forMeal.from), forMeal.carbs)
                    else -> stringResource(R.string.detail_for_nothing)
                },
                color = c.textMuted, fontSize = 13.sp,
            )
            if (tr.insulinRole == "corr") Text(stringResource(R.string.detail_corr_note), color = c.textFaint, fontSize = 12.sp)
        }

        // a meal: all its entries, the dose it needed and what was given
        if (meal != null && (tr.carbs ?: 0.0) > 0) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            if (meal.foods.size > 1) Text(stringResource(R.string.detail_meal, meal.foods.size, meal.carbs), color = c.textMuted, fontSize = 13.sp)
            val first = meal.foods.first().t
            val meter = treatments.filter { it.glucose != null && it.t <= first + 5 * 60_000L && it.t >= first - dose.freshMin * 60_000L }.maxByOrNull { it.t }
            val m = DoseCalc.meal(dose, meal.carbs, meter?.glucose, meter?.t, first + 5 * 60_000L, meal.given)
            Row {
                Text(stringResource(R.string.detail_worked_out, meal.carbs, String.format(locale, "%.2f", m.food).trimEnd('0').trimEnd(',', '.')) + if (m.corr > 0) " + " + stringResource(R.string.planned_corr, f(m.corr)) else "",
                    color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(f(m.dose) + " $u", style = GlucoseHero.copy(fontSize = 14.sp, lineHeight = 18.sp, color = c.insulin))
            }
            meal.insulin.forEach { i ->
                Row {
                    Text(stringResource(R.string.detail_given_at, AlarmText.clock(ctx, i.t)), color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(f(i.insulin ?: 0.0) + " $u", style = GlucoseHero.copy(fontSize = 14.sp, lineHeight = 18.sp, color = c.insulin))
                }
            }
            when {
                meal.given <= 0 -> AlertCard(DoseCalc.Alert.RecentRapid(0.0, 0), units, locale, title = stringResource(R.string.detail_none_given), text = stringResource(R.string.detail_none_given_text, f(m.dose)))
                m.toGive > 0 -> {
                    // food added after the injection: the dose was for less
                    val after = meal.foods.filter { it.t > meal.insulin.last().t }
                    AlertCard(DoseCalc.Alert.GivenLess(meal.given, m.dose), units, locale,
                        text = if (after.isNotEmpty()) stringResource(R.string.detail_added_after, after.sumOf { (it.carbs ?: 0.0).toInt() }) else null)
                }
                m.toGive < 0 -> AlertCard(DoseCalc.Alert.GivenMore(meal.given, m.dose), units, locale)
                else -> AlertCard(DoseCalc.Alert.GivenSame(meal.given), units, locale)
            }
        }
        tr.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.text, fontSize = 13.sp) }
    }
}
