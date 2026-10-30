package com.lumenarcanus.dialife.feature.meal

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.Meal
import com.lumenarcanus.dialife.data.MealRules
import com.lumenarcanus.dialife.data.Meals
import com.lumenarcanus.dialife.data.Meters
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.Treatment

/** What the food form needs from the rest of the app (MealViewModel keeps it current). */
data class MealInputs(
    // the loaded history, pending entries included
    val treatments: List<Treatment> = emptyList(),
    val dose: DoseSettings = DoseSettings(),
    val planned: List<PlannedDose> = emptyList(),
    val units: Units = Units.MgDl,
    // the server's: what makes one meal
    val mealRules: MealRules = MealRules(),
    // glucometer checks uploaded by xDrip+
    val mbg: Series = Series()
)

/** The dose for what is on the form, and the meal it belongs to. */
data class MealDose(
    // the meal this belongs to: the one being edited, or the one it joins
    val meal: Meal?,
    val joined: Boolean,
    // carbs already in the meal, without the entry being edited
    val earlier: Int,
    // the planned dose of this meal, still open: a save changes it
    val openPlan: PlannedDose?,
    val meter: Int?,
    val meterAt: Long?,
    val calc: DoseCalc.Meal
)

/**
 * The dose for [current] grams on the form at [time]: the meal it joins (or
 * is), what that meal already had and was given, the glucometer (typed here
 * or the latest), another rapid dose within 3 h, and the family's usual sizes.
 */
fun mealDose(inputs: MealInputs, editing: Treatment?, time: Long, now: Long, join: Boolean, current: Int, typedMeter: Int?): MealDose {
    val treatments = inputs.treatments
    val others = treatments.filter { it.id != editing?.id }
    val meal = if (editing != null) {
        Meals.group(treatments, inputs.mealRules).firstOrNull { m -> m.foods.any { it.id == editing.id } }
    } else {
        Meals.joining(Meals.group(others, inputs.mealRules), time, inputs.mealRules.gapMin)
    }
    val joined = meal != null && (editing != null || join)
    val earlier = if (!joined) 0 else meal!!.carbs - (editing?.let { (it.carbs ?: 0.0).toInt() } ?: 0)
    val mealIds = meal?.foods?.map { it.id }.orEmpty().toSet() + listOfNotNull(editing?.id)
    val openPlan = inputs.planned.firstOrNull { it.state == "planned" && it.mealId in mealIds }
    val given = if (joined) meal!!.given else 0.0

    val lastMeter = Meters.latest(treatments, inputs.mbg, to = now)
    val meter = typedMeter ?: lastMeter?.mg
    val meterAt = if (typedMeter != null) now else lastMeter?.t
    val recent = treatments.filter {
        Meals.isRapid(it) && it.t in (now - 3 * 3_600_000L)..now && (meal == null || meal.insulin.none { m -> m.id == it.id })
    }.maxByOrNull { it.t }?.let { (it.insulin ?: 0.0) to it.t }
    val span = (treatments.maxOfOrNull { it.t } ?: 0) - (treatments.minOfOrNull { it.t } ?: 0)
    val usualDose = if (span >= 24 * 3_600_000L) treatments.filter(Meals::isRapid).maxOfOrNull { it.insulin ?: 0.0 } else null
    val usualCarbs = Meals.group(others, inputs.mealRules).takeIf { it.size >= 5 }?.maxOfOrNull { it.carbs }
    val calc = DoseCalc.meal(inputs.dose, earlier + current, meter, meterAt, now, given, recent, usualDose, usualCarbs)
    return MealDose(meal, joined, earlier, openPlan, meter, meterAt, calc)
}

/**
 * The planned dose a save makes ([withPlan]) or changes (the meal's open one),
 * or null: nothing to give, or carbs for a low.
 */
fun plannedFor(d: MealDose, foodId: String, now: Long, withPlan: Boolean, newId: () -> String): PlannedDose? {
    val p = d.openPlan
    val m = d.calc
    if (!(withPlan || p != null) || m.toGive <= 0) return null
    return PlannedDose(
        id = p?.id ?: newId(),
        mealId = p?.mealId ?: foodId,
        dose = m.toGive,
        food = m.food,
        corr = m.corr,
        carbs = m.carbs,
        meter = if (m.fresh) d.meter else null,
        meterAt = if (m.fresh) d.meterAt else null,
        createdAt = p?.createdAt ?: now
    )
}

/** What one save of the form writes. */
data class MealSave(
    val food: Treatment,
    // a glucometer value typed on the form: a reading of its own
    val reading: Treatment?,
    // made or changed with it
    val planned: PlannedDose?
)
