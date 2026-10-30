package com.lumenarcanus.dialife.feature.entry

import com.lumenarcanus.dialife.data.CarbMine
import com.lumenarcanus.dialife.data.CarbSuggestion
import com.lumenarcanus.dialife.data.CarbSuggestions
import com.lumenarcanus.dialife.data.CarbTableHit
import com.lumenarcanus.dialife.data.FoodItem
import com.lumenarcanus.dialife.data.Treatment
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
        assertEquals(
            "банан 23 ≈ · хліб 11",
            Treatment(
                "t",
                0,
                foodItems = listOf(FoodItem("банан", carbs = 23.0, source = "estimate"), FoodItem("хліб", carbs = 11.0, source = "manual"))
            ).foodText()
        )
    }

    private val answer = CarbSuggestions(
        items = listOf(
            CarbSuggestion(
                mine = CarbMine(30),
                table = listOf(CarbTableHit("t1", 25))
            )
        )
    )

    @Test
    fun aPickedSuggestionFollowsANewPortion() {
        assertEquals("30", newFoodRow("Борщ", "18", "mine").withSuggestions(Result.success(answer)).carbs)
        assertEquals("25", newFoodRow("Борщ", "20", "table", tableId = "t1").withSuggestions(Result.success(answer)).carbs)
    }

    @Test
    fun typedCarbsStay() {
        val r = newFoodRow("Борщ", "18", "manual").withSuggestions(Result.success(answer))
        assertEquals("18", r.carbs)
        assertEquals(30, r.suggestion?.mine?.carbs) // offered, not put in
    }

    @Test
    fun noAnswerIsNotAsked() {
        val r = newFoodRow("Борщ").copy(loading = true).withSuggestions(Result.failure(RuntimeException()))
        assertEquals(false, r.asked)
        assertEquals(false, r.loading)
    }
}
