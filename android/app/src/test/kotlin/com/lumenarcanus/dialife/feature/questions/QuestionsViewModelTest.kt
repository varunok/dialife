package com.lumenarcanus.dialife.feature.questions

import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.QuestionRepository
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuestionsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeQuestions(val ok: Boolean) : QuestionRepository {
        val answers = mutableListOf<Triple<Long, String, String?>>()

        override suspend fun answer(id: Long, answer: String, treatmentId: String?): Result<Unit> {
            answers += Triple(id, answer, treatmentId)
            return if (ok) Result.success(Unit) else Result.failure(RuntimeException())
        }

        override suspend fun dismiss(id: Long) = Result.success(Unit)
    }

    @Test
    fun aDoseForTheMealIsSavedLinked() {
        val live = FakeLiveSource()
        val vm = QuestionsViewModel(live, FakeQuestions(ok = true))
        val t = Treatment("i", 0, insulin = 2.0, mealId = "m")
        vm.onAction(QuestionAction.Link(t))
        assertEquals(listOf(t), live.saved)
    }

    @Test
    fun aFailedAnswerIsTold() = runTest(main.dispatcher) {
        val questions = FakeQuestions(ok = false)
        val vm = QuestionsViewModel(FakeLiveSource(), questions)
        backgroundScope.launch { vm.uiState.collect {} }
        vm.onAction(QuestionAction.Answer(3, "nothing", null))
        assertEquals(listOf(Triple(3L, "nothing", null)), questions.answers)
        assertEquals(R.string.q_failed, vm.uiState.value.userMessage)
    }
}
