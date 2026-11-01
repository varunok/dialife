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

    private class FakeQuestions(val ok: Boolean = true) : QuestionRepository {
        val answers = mutableListOf<Triple<Long, String, String?>>()

        override suspend fun answer(id: Long, answer: String, treatmentId: String?): Result<Unit> {
            answers += Triple(id, answer, treatmentId)
            return if (ok) Result.success(Unit) else Result.failure(RuntimeException())
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
        val vm = MealViewModel(live, planned, questions)
        vm.save(MealSave(food, reading, plan), questionId = 0)
        assertEquals(listOf(food, reading), live.saved)
        assertEquals(listOf(plan), planned.saved)
        assertTrue(questions.answers.isEmpty())
        assertEquals(MealSaveStatus.Saved, vm.status.value)
    }

    @Test
    fun foodAloneGoesToTheQueueWithoutTheServer() {
        val live = FakeLiveSource()
        val vm = MealViewModel(live, FakePlanned(ok = false), FakeQuestions(ok = false))
        vm.save(MealSave(food, null, null), questionId = 0)
        assertEquals(listOf(food), live.saved)
        assertEquals(MealSaveStatus.Saved, vm.status.value)
    }

    @Test
    fun foodForAQuestionAnswersIt() {
        val questions = FakeQuestions()
        MealViewModel(FakeLiveSource(), FakePlanned(), questions).save(MealSave(food, null, null), questionId = 9)
        assertEquals(listOf(Triple(9L, "food", "f")), questions.answers)
    }

    @Test
    fun aPlanThatDidNotReachTheServerSavesNothingAndKeepsTheForm() {
        val live = FakeLiveSource()
        val vm = MealViewModel(live, FakePlanned(ok = false), FakeQuestions())
        vm.save(MealSave(food, reading, plan), questionId = 0)
        assertTrue(live.saved.isEmpty())
        assertEquals(MealSaveStatus.Failed, vm.status.value)
        vm.failureSeen()
        assertEquals(MealSaveStatus.Editing, vm.status.value)
    }

    @Test
    fun anAnswerThatDidNotReachTheServerSavesNothing() {
        val live = FakeLiveSource()
        val vm = MealViewModel(live, FakePlanned(), FakeQuestions(ok = false))
        vm.save(MealSave(food, null, null), questionId = 9)
        assertTrue(live.saved.isEmpty())
        assertEquals(MealSaveStatus.Failed, vm.status.value)
    }

    @Test
    fun tryingAgainAfterAFailureSavesTheMealOnce() {
        val live = FakeLiveSource()
        val planned = object : PlannedDoseRepository {
            var calls = 0

            override suspend fun save(p: PlannedDose): Result<Unit> = if (++calls ==
                1
            ) {
                Result.failure(RuntimeException())
            } else {
                Result.success(Unit)
            }

            override suspend fun given(id: String, insulinId: String) = Result.success(Unit)

            override suspend fun skip(id: String) = Result.success(Unit)
        }
        val vm = MealViewModel(live, planned, FakeQuestions())
        vm.save(MealSave(food, null, plan), questionId = 0)
        vm.failureSeen()
        vm.save(MealSave(food, null, plan), questionId = 0)
        assertEquals(listOf(food), live.saved)
        assertEquals(MealSaveStatus.Saved, vm.status.value)
    }
}
