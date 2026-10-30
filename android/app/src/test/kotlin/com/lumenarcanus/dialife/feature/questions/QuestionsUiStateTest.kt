package com.lumenarcanus.dialife.feature.questions

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.StateResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionsUiStateTest {
    private val now = 1_761_400_000_000L
    private fun q(id: Long, kind: String = "rise", state: String = "open") =
        Question(id = id, kind = kind, state = state, from = 0, to = 0, sgvFrom = 0, sgvTo = 0)

    // two doses were given: everyone watches for a low until then
    private fun watched(id: Long, until: Long) = q(id, kind = "double", state = "answered").copy(outcome = "two", watchUntil = until)

    @Test
    fun openQuestionsButNotAPlannedDosesOwn() {
        val st = StateResponse(now = now, questions = listOf(q(1), q(2, kind = "planned"), q(3, state = "answered"), q(4, kind = "bolus")))
        assertEquals(listOf(1L, 4L), questionsUiState(st, emptyList(), 7, Units.MgDl, now).open.map { it.id })
    }

    @Test
    fun watchedUntilItsTimeIsUp() {
        val st = StateResponse(now = now, questions = listOf(watched(1, now + 1), watched(2, now), q(3)))
        assertEquals(listOf(1L), questionsUiState(st, emptyList(), 7, Units.MgDl, now).watching.map { it.id })
    }
}
