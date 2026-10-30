package com.lumenarcanus.dialife.feature.meal

import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealDoseTest {
    private val now = 1_761_400_000_000L
    private val min = 60_000L
    private val settings = DoseSettings() // 1 unit per 20 g

    @Test
    fun aNewMealCountsOnlyTheForm() {
        val d = mealDose(MealInputs(dose = settings), null, now, now, join = true, current = 40, typedMeter = null)
        assertNull(d.meal)
        assertFalse(d.joined)
        assertEquals(40, d.calc.carbs)
        assertEquals(2.0, d.calc.food, 0.001)
    }

    @Test
    fun joinsTheMealJustBeforeItAndCountsWhatWasGiven() {
        val food = Treatment(id = "f1", t = now - 10 * min, carbs = 30.0)
        val rapid = Treatment(id = "i1", t = now - 12 * min, insulin = 1.5, insulinType = "rapid")
        val inputs = MealInputs(treatments = listOf(food, rapid), dose = settings)
        val joined = mealDose(inputs, null, now, now, join = true, current = 20, typedMeter = null)
        assertTrue(joined.joined)
        assertEquals(30, joined.earlier)
        assertEquals(50, joined.calc.carbs)
        val apart = mealDose(inputs, null, now, now, join = false, current = 20, typedMeter = null)
        assertEquals(20, apart.calc.carbs)
    }

    @Test
    fun editingCountsTheMealWithoutTheOldEntry() {
        val food = Treatment(id = "f1", t = now - 10 * min, carbs = 30.0)
        val more = Treatment(id = "f2", t = now - 5 * min, carbs = 15.0)
        val d =
            mealDose(
                MealInputs(treatments = listOf(food, more), dose = settings),
                food,
                food.t,
                now,
                join = true,
                current = 40,
                typedMeter = null
            )
        assertEquals(15, d.earlier)
        assertEquals(55, d.calc.carbs)
    }

    @Test
    fun aTypedMeterIsFreshAndGoesIntoThePlannedDose() {
        val d = mealDose(MealInputs(dose = settings), null, now, now, join = true, current = 40, typedMeter = 186)
        assertEquals(186, d.meter)
        assertEquals(now, d.meterAt)
        val p = plannedFor(d, "food1", now, withPlan = true) { "new" }!!
        assertEquals("new", p.id)
        assertEquals("food1", p.mealId)
        assertEquals(d.calc.toGive, p.dose, 0.001)
        assertEquals(186, p.meter)
    }

    @Test
    fun theMealsOpenPlanIsChangedNotMadeAgain() {
        val food = Treatment(id = "f1", t = now - 10 * min, carbs = 30.0)
        val open = PlannedDose(id = "p1", mealId = "f1", dose = 1.0, food = 1.0, corr = 0.0, carbs = 30, createdAt = now - 9 * min)
        val inputs = MealInputs(treatments = listOf(food), dose = settings, planned = listOf(open))
        val d = mealDose(inputs, food, food.t, now, join = true, current = 60, typedMeter = null)
        val p = plannedFor(d, "f1", now, withPlan = false) { "new" }!!
        assertEquals("p1", p.id)
        assertEquals(open.createdAt, p.createdAt)
        assertEquals(3.0, p.dose, 0.001)
    }

    @Test
    fun nothingToPlanWithoutTheChoiceOrADose() {
        val d = mealDose(MealInputs(dose = settings), null, now, now, join = true, current = 40, typedMeter = null)
        assertNull(plannedFor(d, "f", now, withPlan = false) { "new" })
        val none = mealDose(MealInputs(dose = settings), null, now, now, join = true, current = 0, typedMeter = null)
        assertNull(plannedFor(none, "f", now, withPlan = true) { "new" })
    }
}
