package com.lumenarcanus.dialife.feature.wait

import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Wait
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class WaitViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = System.currentTimeMillis()
    private val wait = Wait("d", now - 5 * 60_000L, 3.0, now + 10 * 60_000L, "range", 142, 60.0, 60.0)

    private class FakeRepo : WaitRepository {
        val answer = CompletableDeferred<Result<Unit>>()
        val closed = mutableListOf<WaitAction>()

        override suspend fun close(a: WaitAction): Result<Unit> {
            closed += a
            return answer.await()
        }
    }

    @Test
    fun alreadyEatenClosesTheCardAtOnceAndOnTheServer() = runTest(main.dispatcher) {
        val repo = FakeRepo()
        val vm = WaitViewModel(FakeLiveSource(StateResponse(now = now, wait = wait)), repo)
        backgroundScope.launch { vm.uiState.collect {} }
        assertNotNull(vm.uiState.value.countdown)
        vm.onAction(WaitAction.Eaten("d"))
        assertNull(vm.uiState.value.countdown) // before the server answers
        assertEquals(listOf<WaitAction>(WaitAction.Eaten("d")), repo.closed)
        repo.answer.complete(Result.success(Unit))
        assertNull(vm.uiState.value.countdown)
    }

    @Test
    fun aCloseThatFailedBringsTheCardBack() = runTest(main.dispatcher) {
        val repo = FakeRepo()
        val vm = WaitViewModel(FakeLiveSource(StateResponse(now = now, wait = wait)), repo)
        backgroundScope.launch { vm.uiState.collect {} }
        vm.onAction(WaitAction.Eaten("d"))
        repo.answer.complete(Result.failure(RuntimeException()))
        assertNotNull(vm.uiState.value.countdown)
        assertEquals(R.string.q_failed, vm.uiState.value.userMessage)
    }
}
