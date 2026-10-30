package com.lumenarcanus.dialife.feature.data

import androidx.lifecycle.SavedStateHandle
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DataViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = 1_761_400_000_000L

    private class FakeRepo : DataRepository {
        val asked = mutableListOf<LongRange>()

        override suspend fun history(from: Long, to: Long): History {
            asked += from..to
            return History(from, to)
        }
    }

    @Test
    fun pagesBackByTheChartsSpanAndLoadsOlderDays() = runTest(main.dispatcher) {
        val repo = FakeRepo()
        val vm = DataViewModel(FakeLiveSource(), repo, spanHours = 12, saved = SavedStateHandle()) { now }
        backgroundScope.launch { vm.uiState.collect {} }
        assertTrue(repo.asked.isEmpty()) // the live day holds the first window
        vm.onAction(DataAction.Older)
        assertTrue(repo.asked.isEmpty()) // 12 to 24 h ago: still the live day
        vm.onAction(DataAction.Older)
        assertEquals(listOf((now - 36 * HOUR)..(now - 24 * HOUR)), repo.asked)
        assertEquals(now - 24 * HOUR, vm.uiState.value.to)
    }

    @Test
    fun newerStopsAtNow() = runTest(main.dispatcher) {
        val vm = DataViewModel(FakeLiveSource(), FakeRepo(), spanHours = 3, saved = SavedStateHandle()) { now }
        backgroundScope.launch { vm.uiState.collect {} }
        vm.onAction(DataAction.Newer)
        assertEquals(0, vm.uiState.value.page)
        assertEquals(now, vm.uiState.value.to)
    }

    @Test
    fun controlsSurviveInSavedState() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = DataViewModel(FakeLiveSource(), FakeRepo(), spanHours = 3, saved = saved) { now }
        vm.onAction(DataAction.Filter(DataFilter.Out))
        vm.onAction(DataAction.Step(15))
        val again = DataViewModel(FakeLiveSource(), FakeRepo(), spanHours = 3, saved = saved) { now }
        backgroundScope.launch { again.uiState.collect {} }
        assertEquals(DataFilter.Out, again.uiState.value.filter)
        assertEquals(15, again.uiState.value.step)
    }
}
