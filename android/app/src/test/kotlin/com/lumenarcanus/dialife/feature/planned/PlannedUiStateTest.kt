package com.lumenarcanus.dialife.feature.planned

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.MealRules
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedUiStateTest {
    private val now = 1_761_400_000_000L
    private val meal = Treatment(id = "m1", t = now - 20 * 60_000L, carbs = 45.0)
    private val p = PlannedDose(id = "p1", mealId = "m1", dose = 4.0, food = 2.25, corr = 2.0, carbs = 45, createdAt = now - 10 * 60_000L)

    private fun state(vararg ps: PlannedDose, questions: List<Question> = emptyList()) =
        StateResponse(now = now, planned = ps.toList(), questions = questions)

    @Test
    fun onlyThoseStillPlanned() {
        val ui = plannedUiState(state(p, p.copy(id = "p2", state = "given")), listOf(meal), Units.MgDl, now)
        assertEquals(listOf("p1"), ui.cards.map { it.dose.id })
        assertEquals(meal.t, ui.cards[0].mealAt)
    }

    @Test
    fun brightAfterHalfAnHour() {
        assertFalse(plannedUiState(state(p), listOf(meal), Units.MgDl, now).cards[0].loud)
        assertTrue(plannedUiState(state(p), listOf(meal), Units.MgDl, p.createdAt + LOUD_AFTER_MIN * 60_000L).cards[0].loud)
    }

    @Test
    fun theOpenQuestionForThisDose() {
        val q = Question(id = 9, kind = "planned", state = "open", from = 0, to = 0, sgvFrom = 0, sgvTo = 0, plannedId = "p1")
        val other = q.copy(id = 10, plannedId = "p9")
        assertEquals(9L, plannedUiState(state(p, questions = listOf(other, q)), listOf(meal), Units.MgDl, now).cards[0].question?.id)
    }

    @Test
    fun recordedIsTheMealsRapidNotAnotherMealsOrACorrection() {
        val rapid = Treatment(id = "i1", t = meal.t + 60_000L, insulin = 4.0, insulinType = "rapid")
        val corr = rapid.copy(id = "i2", insulinRole = "corr", t = meal.t)
        val othersMeal = rapid.copy(id = "i3", mealId = "m0", t = meal.t)
        assertEquals("i1", nearRapid(listOf(meal, corr, othersMeal, rapid), meal.t, now, MealRules())?.id)
        assertNull(nearRapid(listOf(meal, corr, othersMeal), meal.t, now, MealRules()))
    }
}
