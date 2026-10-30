package com.lumenarcanus.dialife.feature.meal

import com.lumenarcanus.dialife.core.data.PlannedDoseRepository
import com.lumenarcanus.dialife.core.data.QuestionRepository
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MealViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakePlanned(val ok: Boolean = true) : PlannedDoseRepository {
        val saved = mutableListOf<PlannedDose>()

        override suspend fun save(p: PlannedDose): Result<Unit> {
            saved += p
            return if (ok) Result.success(Unit) else Result.failure(RuntimeException())
        }

        override suspend fun given(id: String, insulinId: String) = Result.success(Unit)

        override suspend fun skip(id: String) = Result.success(Unit)
    }

    private class FakeQuestions : QuestionRepository {
        val answers = mutableListOf<Triple<Long, String, String?>>()

        override suspend fun answer(id: Long, answer: String, treatmentId: String?): Result<Unit> {
            answers += Triple(id, answer, treatmentId)
            return Result.success(Unit)
        }

        override suspend fun dismiss(id: Long) = Result.success(Unit)
    }

    private val food = Treatment("f", 0, carbs = 45.0)
    private val reading = Treatment("g", 0, glucose = 180)
    private val plan = PlannedDose("p", "f", 3.0, createdAt = 0)

    @Test
    fun theMealItsReadingAndItsPlannedDoseAreSaved() {
        val live = FakeLiveSource()
        val planned = FakePlanned()
        val questions = FakeQuestions()
        MealViewModel(live, planned, questions) {}.save(MealSave(food, reading, plan), questionId = 0)
        assertEquals(listOf(food, reading), live.saved)
        assertEquals(listOf(plan), planned.saved)
        assertTrue(questions.answers.isEmpty())
    }

    @Test
    fun foodForAQuestionAnswersIt() {
        val questions = FakeQuestions()
        MealViewModel(FakeLiveSource(), FakePlanned(), questions) {}.save(MealSave(food, null, null), questionId = 9)
        assertEquals(listOf(Triple(9L, "food", "f")), questions.answers)
    }

    @Test
    fun aPlanThatDidNotReachTheServerIsTold() {
        var told = 0
        MealViewModel(FakeLiveSource(), FakePlanned(ok = false), FakeQuestions()) {
            told++
        }.save(MealSave(food, null, plan), questionId = 0)
        assertEquals(1, told)
    }
}
