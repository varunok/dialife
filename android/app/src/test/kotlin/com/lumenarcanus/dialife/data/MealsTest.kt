package com.lumenarcanus.dialife.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealsTest {
    private fun min(m: Int) = m * 60_000L
    private fun food(id: String, at: Int, g: Double) = Treatment(id = id, t = min(at), carbs = g)
    private fun rapid(id: String, at: Int, u: Double, meal: String? = null, role: String? = null) =
        Treatment(id = id, t = min(at), insulin = u, insulinType = "rapid", mealId = meal, insulinRole = role)

    @Test
    fun aDessertLaterIsTheSameMeal() {
        val ts = listOf(food("lunch", 760, 45.0), rapid("i1", 755, 2.0), food("dessert", 800, 20.0), food("dinner", 1140, 50.0))
        val meals = Meals.group(ts, MealRules(gapMin = 90))
        assertEquals(2, meals.size)
        assertEquals(65, meals[0].carbs)
        assertEquals(2.0, meals[0].given, 0.0)
        assertEquals(0.0, meals[1].given, 0.0)
    }

    @Test
    fun theServersWindowSaysWhichRapidIsTheMeals() {
        val ts = listOf(food("lunch", 760, 45.0), rapid("early", 723, 1.0), rapid("pre", 735, 2.0))
        // 25 min before counts, 37 min before does not: 30 min, as the server says
        assertEquals(2.0, Meals.group(ts, MealRules())[0].given, 0.0)
        assertEquals(3.0, Meals.group(ts, MealRules(beforeMin = 40))[0].given, 0.0)
    }

    @Test
    fun aCorrectionIsNotTheMeals() {
        val ts = listOf(food("lunch", 760, 45.0), rapid("c", 790, 1.0, role = "corr"))
        assertEquals(0.0, Meals.group(ts, MealRules(gapMin = 90))[0].given, 0.0)
    }

    @Test
    fun aLinkedDoseGoesToItsMealWhereverItIs() {
        val ts = listOf(food("a", 600, 30.0), food("b", 900, 30.0), rapid("i", 640, 2.0, meal = "b"))
        val meals = Meals.group(ts, MealRules(gapMin = 90))
        assertEquals(0.0, meals[0].given, 0.0) // near the first meal, but given for the second
        assertEquals(2.0, meals[1].given, 0.0)
    }

    @Test
    fun joining() {
        val meals = Meals.group(listOf(food("lunch", 760, 45.0)), MealRules(gapMin = 90))
        assertEquals("lunch", Meals.joining(meals, min(800), 90)!!.foods[0].id)
        assertNull(Meals.joining(meals, min(900), 90))
    }
}
