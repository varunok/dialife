package com.lumenarcanus.dialife.feature.meal

import com.lumenarcanus.dialife.data.FoodItem
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.treatment.newFoodRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealFormStateTest {
    private val now = 1_761_400_000_000L

    @Test
    fun aNewMealIsEmptyAndNow() {
        val f = MealFormState(null, null, null, now)
        assertEquals(0, f.rows.size)
        assertEquals(now, f.time(now))
        f.whenIdx = 2
        assertEquals(now - 30 * 60_000L, f.time(now))
    }

    @Test
    fun anEditStartsFromTheEntryAtItsTime() {
        val e = Treatment(id = "f1", t = now - 90 * 60_000L, carbs = 40.0, foodItems = listOf(FoodItem("Борщ", "тарілка", 40.0, "mine")))
        val f = MealFormState(null, e, null, now)
        assertEquals(listOf("Борщ"), f.rows.map { it.name })
        assertEquals(40, f.current)
        assertEquals(e.t, f.time(now))
    }

    @Test
    fun aDraftComesBackAsItWas() {
        val d =
            MealDraft(
                listOf(MealDraft.Row("Хліб", "2 скибки", "22", "table", "t1")),
                whenIdx = 1,
                plan = true,
                join = false,
                meterText = "186",
                startedAt =
                now - 60_000L
            )
        val f = MealFormState(d, null, null, now)
        assertEquals(d, f.toDraft())
    }

    @Test
    fun typedDishFillsTheEmptyRowAndOpens() {
        val f = MealFormState(null, null, null, now)
        f.rows.add(newFoodRow())
        f.newName = "гречка 150 г"
        f.addTyped()
        assertEquals(1, f.rows.size)
        assertEquals("Гречка", f.rows[0].name)
        assertEquals("150 г", f.rows[0].portion)
        assertEquals(f.rows[0].id, f.open)
        assertEquals("", f.newName)
    }

    @Test
    fun removedIsKeptForUndo() {
        val f = MealFormState(null, null, null, now)
        f.place(newFoodRow(name = "Суп", carbs = "20"))
        f.place(newFoodRow(name = "Хліб", carbs = "12"))
        val soup = f.rows[0]
        f.remove(soup.id)
        assertEquals(0 to soup, f.removed)
        assertEquals(12, f.current)
        f.open = f.rows[0].id
        f.remove(f.rows[0].id)
        assertNull(f.open)
    }
}
