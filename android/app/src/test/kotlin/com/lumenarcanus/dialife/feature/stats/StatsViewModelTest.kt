package com.lumenarcanus.dialife.feature.stats

import com.lumenarcanus.dialife.data.MeterReport
import com.lumenarcanus.dialife.data.Stats
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** Each period answers when the test says so. */
    private class FakeRepo : StatsRepository {
        val answers = mutableMapOf<Int, CompletableDeferred<Result<Stats>>>()

        override suspend fun stats(days: Int) = answers.getOrPut(days) { CompletableDeferred() }.await()

        override suspend fun meter() = Result.success(MeterReport(ready = true))
    }

    private fun stats(days: Int) = Stats(0, 0, days)

    @Test
    fun aLaterPickWinsOverASlowerAnswer() {
        val repo = FakeRepo()
        val vm = StatsViewModel(FakeLiveSource(), repo)
        vm.pick(30)
        repo.answers.getValue(30).complete(Result.success(stats(30)))
        repo.answers.getValue(14).complete(Result.success(stats(14))) // the first period, answered last
        assertEquals(30, vm.uiState.value.days)
        assertEquals(StatsLoad.Ready(stats(30)), vm.uiState.value.load)
        assertEquals(true, vm.uiState.value.meter?.ready)
    }

    @Test
    fun aFailureIsShown() {
        val repo = FakeRepo()
        val vm = StatsViewModel(FakeLiveSource(), repo)
        repo.answers.getValue(14).complete(Result.failure(RuntimeException()))
        assertEquals(StatsLoad.Failed, vm.uiState.value.load)
    }
}
