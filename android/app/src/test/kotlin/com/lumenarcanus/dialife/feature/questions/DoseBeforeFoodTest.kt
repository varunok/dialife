package com.lumenarcanus.dialife.feature.questions

import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoseBeforeFoodTest {
    private val now = 1_761_400_000_000L
    private val min = 60_000L
    private val food = Treatment("f", now, carbs = 40.0)
    private fun bolus(at: Long = now) = Question(1, "bolus", "open", at, at + 30 * min, 120, 180)

    @Test
    fun theLastRapidDoseUpTo3HoursBefore() {
        val older = Treatment("a", now - 150 * min, insulin = 2.0)
        val last = Treatment("b", now - 20 * min, insulin = 3.0)
        assertEquals(food to last, doseBeforeFood(bolus(), listOf(older, last, food)))
    }

    @Test
    fun notADoseForAnotherMealOrTooEarlyOrLongActing() {
        val other = Treatment("o", now - 60 * min, carbs = 30.0)
        val forOther = Treatment("b", now - 50 * min, insulin = 3.0, mealId = "o")
        val tooEarly = Treatment("e", now - 181 * min, insulin = 2.0)
        val basal = Treatment("l", now - 30 * min, insulin = 12.0, insulinType = "basal")
        assertNull(doseBeforeFood(bolus(), listOf(other, forOther, tooEarly, basal, food)))
    }

    @Test
    fun aDoseLinkedToAMealSinceDeletedStillCounts() {
        val orphan = Treatment("b", now - 30 * min, insulin = 3.0, mealId = "gone")
        assertEquals(food to orphan, doseBeforeFood(bolus(), listOf(orphan, food)))
    }

    @Test
    fun onlyForABolusQuestionAtTheFood() {
        val dose = Treatment("b", now - 20 * min, insulin = 3.0)
        assertNull(doseBeforeFood(bolus().copy(kind = "rise"), listOf(dose, food)))
        assertNull(doseBeforeFood(bolus(now - 5 * min), listOf(dose, food)))
    }
}
