package com.lumenarcanus.dialife.feature.home

import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.QuestionRepository
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeQuestions(var ok: Boolean = true) : QuestionRepository {
        val answers = mutableListOf<Triple<Long, String, String?>>()

        override suspend fun answer(id: Long, answer: String, treatmentId: String?): Result<Unit> {
            answers += Triple(id, answer, treatmentId)
            return if (ok) Result.success(Unit) else Result.failure(RuntimeException())
        }

        override suspend fun dismiss(id: Long) = if (ok) Result.success(Unit) else Result.failure(RuntimeException())
    }

    @Test
    fun entriesGoThroughTheLiveConnection() {
        val live = FakeLiveSource()
        val vm = HomeViewModel(live, FakeQuestions())
        val t = Treatment("a", 0, insulin = 2.0)
        vm.save(t)
        vm.delete("b")
        vm.retry()
        assertEquals(listOf(t), live.saved)
        assertEquals(listOf("b"), live.deleted)
        assertEquals(1, live.reconnects)
    }

    @Test
    fun aFailedAnswerIsToldOnce() = runTest(main.dispatcher) {
        val questions = FakeQuestions(ok = false)
        val vm = HomeViewModel(FakeLiveSource(), questions)
        backgroundScope.launch { vm.ui.collect {} }
        vm.answer(5, "food", "t1")
        assertEquals(listOf(Triple(5L, "food", "t1")), questions.answers)
        assertEquals(R.string.q_failed, vm.ui.value.userMessage)
        vm.userMessageShown()
        assertNull(vm.ui.value.userMessage)
    }

    @Test
    fun theRangePickedIsKept() = runTest(main.dispatcher) {
        val vm = HomeViewModel(FakeLiveSource())
        backgroundScope.launch { vm.ui.collect {} }
        vm.setRange(12)
        assertEquals(12, vm.ui.value.rangeHours)
    }
}
