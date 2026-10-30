package com.lumenarcanus.dialife.feature.planned

import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.PlannedDoseRepository
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlannedViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val planned = PlannedDose("p", "meal1", 3.0, createdAt = 0)

    private class FakeRepo(var ok: Boolean = true) : PlannedDoseRepository {
        val calls = mutableListOf<String>()

        private fun r(call: String): Result<Unit> {
            calls += call
            return if (ok) Result.success(Unit) else Result.failure(RuntimeException())
        }

        override suspend fun save(p: PlannedDose) = r("save ${p.dose}")

        override suspend fun given(id: String, insulinId: String) = r("given $id $insulinId")

        override suspend fun skip(id: String) = r("skip $id")
    }

    @Test
    fun givenNewSavesTheInsulinThenClosesTheDose() {
        val live = FakeLiveSource()
        val repo = FakeRepo()
        val vm = PlannedViewModel(live, repo)
        val ins = Treatment("i", 0, insulin = 3.0)
        vm.onAction(PlannedAction.Given(planned, ins, isNew = true))
        assertEquals(listOf(ins), live.saved)
        assertEquals(listOf("given p i"), repo.calls)
    }

    @Test
    fun anInjectionAlreadyLoggedIsLinkedToTheMeal() {
        val live = FakeLiveSource()
        val vm = PlannedViewModel(live, FakeRepo())
        vm.onAction(PlannedAction.Given(planned, Treatment("i", 0, insulin = 3.0, insulinRole = "corr"), isNew = false))
        assertEquals("meal1", live.saved.single().mealId)
        assertEquals("meal", live.saved.single().insulinRole)
    }

    @Test
    fun anEntryAlreadyWithItsMealIsLeftAlone() {
        val live = FakeLiveSource()
        val vm = PlannedViewModel(live, FakeRepo())
        vm.onAction(PlannedAction.Given(planned, Treatment("i", 0, insulin = 3.0, mealId = "meal1"), isNew = false))
        assertTrue(live.saved.isEmpty())
    }

    @Test
    fun aFailureIsTold() = runTest(main.dispatcher) {
        val repo = FakeRepo(ok = false)
        val vm = PlannedViewModel(FakeLiveSource(), repo)
        backgroundScope.launch { vm.uiState.collect {} }
        vm.onAction(PlannedAction.Change(planned, 2.5))
        vm.onAction(PlannedAction.Skip(planned))
        assertEquals(listOf("save 2.5", "skip p"), repo.calls)
        assertEquals(R.string.planned_failed, vm.uiState.value.userMessage)
    }
}
