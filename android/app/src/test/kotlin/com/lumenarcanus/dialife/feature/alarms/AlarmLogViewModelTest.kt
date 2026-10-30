package com.lumenarcanus.dialife.feature.alarms

import com.lumenarcanus.dialife.data.LogEpisode
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AlarmLogViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeRepo : AlarmLogRepository {
        var fail = false
        val asked = mutableListOf<Int>()

        override suspend fun episodes(days: Int): Result<List<LogEpisode>> {
            asked += days
            return if (fail) Result.failure(RuntimeException()) else Result.success(emptyList())
        }
    }

    @Test
    fun loadsTheWeekThenThePeriodPicked() {
        val repo = FakeRepo()
        val vm = AlarmLogViewModel(FakeLiveSource(), repo)
        assertEquals(AlarmLogLoad.Ready(emptyList()), vm.uiState.value.load)
        vm.pick(30)
        assertEquals(listOf(7, 30), repo.asked)
        assertEquals(30, vm.uiState.value.days)
    }

    @Test
    fun aFailureCanBeRetried() {
        val repo = FakeRepo().apply { fail = true }
        val vm = AlarmLogViewModel(FakeLiveSource(), repo)
        assertEquals(AlarmLogLoad.Failed, vm.uiState.value.load)
        repo.fail = false
        vm.retry()
        assertEquals(AlarmLogLoad.Ready(emptyList()), vm.uiState.value.load)
    }
}
