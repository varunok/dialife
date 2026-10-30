package com.lumenarcanus.dialife.feature.entry

import com.lumenarcanus.dialife.core.data.FoodRepository
import com.lumenarcanus.dialife.data.CarbMine
import com.lumenarcanus.dialife.data.CarbSuggestion
import com.lumenarcanus.dialife.data.CarbSuggestions
import com.lumenarcanus.dialife.data.FoodCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoodLookupTest {
    private class FakeFoods : FoodRepository {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun suggest(name: String, portion: String): Result<CarbSuggestions> {
            asked += name to portion
            return Result.success(CarbSuggestions(items = listOf(CarbSuggestion(mine = CarbMine(30)))))
        }

        override suspend fun catalog(): Result<FoodCatalog> = Result.failure(UnsupportedOperationException())
    }

    @Test
    fun aShortNameIsNotLookedUp() = runTest {
        val foods = FakeFoods()
        var row = newFoodRow("Б").copy(asked = true)
        lookUp(row, foods) { row = it(row) }
        assertTrue(foods.asked.isEmpty())
        assertFalse(row.asked)
    }

    @Test
    fun theFoodIsLookedUpOnceTheTypingStops() = runTest {
        val foods = FakeFoods()
        var row = newFoodRow(" Борщ ", portion = "тарілка ")
        launch { lookUp(row, foods) { row = it(row) } }
        advanceTimeBy(LOOKUP_PAUSE_MS - 1)
        assertTrue(foods.asked.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Борщ" to "тарілка"), foods.asked)
        assertEquals(30, row.suggestion?.mine?.carbs)
        assertFalse(row.loading)
    }

    @Test
    fun moreTypingCancelsTheLookup() = runTest {
        val foods = FakeFoods()
        var row = newFoodRow("Бор")
        val job = launch { lookUp(row, foods) { row = it(row) } }
        advanceTimeBy(LOOKUP_PAUSE_MS / 2)
        job.cancel() // a new letter: the screen starts a new lookup
        advanceTimeBy(LOOKUP_PAUSE_MS)
        assertTrue(foods.asked.isEmpty())
    }
}
