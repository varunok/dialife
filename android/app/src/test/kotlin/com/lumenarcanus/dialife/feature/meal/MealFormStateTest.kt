package com.lumenarcanus.dialife.feature.meal

import com.lumenarcanus.dialife.data.FoodItem
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.feature.entry.newFoodRow
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

    @Test
    fun aRenamedDishLosesItsCarbsAndOpens() {
        val f = MealFormState(null, null, null, now)
        f.rows.add(newFoodRow("Гречка", "22", "table", "100 г", tableId = "t1"))
        val id = f.rows[0].id
        f.revealed = id
        f.renaming = id
        f.rename(id, "рис 150 г")
        val r = f.rows[0]
        assertEquals("Рис", r.name)
        assertEquals("150 г", r.portion)
        assertEquals("", r.carbs)
        assertNull(r.source)
        assertNull(r.tableId)
        assertEquals(id, f.open)
        assertNull(f.revealed)
        assertNull(f.renaming)
    }

    @Test
    fun aRenameKeepsThePortionWhenNoneIsTyped() {
        val f = MealFormState(null, null, null, now)
        f.rows.add(newFoodRow("Гречка", "22", "manual", "100 г"))
        f.rename(f.rows[0].id, "булгур")
        assertEquals("Булгур", f.rows[0].name)
        assertEquals("100 г", f.rows[0].portion)
        assertEquals("", f.rows[0].carbs)
    }

    @Test
    fun theSameNameOrABlankOneChangesNothing() {
        val f = MealFormState(null, null, null, now)
        f.rows.add(newFoodRow("Гречка", "22", "mine", "100 г"))
        val id = f.rows[0].id
        f.rename(id, "гречка")
        f.rename(id, "   ")
        assertEquals("22", f.rows[0].carbs)
        assertEquals("mine", f.rows[0].source)
        assertNull(f.open)
    }
}
