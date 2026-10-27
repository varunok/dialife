package com.lumenarcanus.dialife.ui

import com.lumenarcanus.dialife.data.FoodItem
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.ui.treatment.foodItemsOf
import com.lumenarcanus.dialife.ui.treatment.foodRowsOf
import com.lumenarcanus.dialife.ui.treatment.newFoodRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodRowsTest {
    @Test
    fun aLoneTotalStaysATotal() {
        // 15 g for a low, no name: an ordinary carbs entry, no list
        assertNull(foodItemsOf(listOf(newFoodRow(carbs = "15", source = "manual"))))
        assertNull(foodItemsOf(listOf(newFoodRow())))
    }

    @Test
    fun aMealKeepsItsLines() {
        val items = foodItemsOf(listOf(newFoodRow("Борщ", "18", "mine", "тарілка"), newFoodRow("Хліб"), newFoodRow()))!!
        assertEquals(listOf("Борщ", "Хліб"), items.map { it.name })
        assertEquals(18.0, items[0].carbs)
        assertNull(items[1].carbs) // no number yet: kept, not counted
    }

    @Test
    fun editingRestoresTheRows() {
        val rows = foodRowsOf(listOf(FoodItem("Банан", "1 шт", 23.0, "estimate")), 23.0)
        assertEquals("23", rows[0].carbs)
        assertEquals("1 шт", rows[0].portion)
        assertEquals("40", foodRowsOf(null, 40.0)[0].carbs) // an older entry with only a total
        assertEquals("банан 23 ≈ · хліб 11", Treatment("t", 0, foodItems = listOf(FoodItem("банан", carbs = 23.0, source = "estimate"), FoodItem("хліб", carbs = 11.0, source = "manual"))).foodText())
    }
}
