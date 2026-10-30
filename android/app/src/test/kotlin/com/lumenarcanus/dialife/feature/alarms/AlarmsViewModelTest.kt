package com.lumenarcanus.dialife.feature.alarms

import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AlarmsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeActions(var ok: Boolean = true) : AlarmActions {
        val acts = mutableListOf<Pair<String, Long>>()

        override suspend fun act(action: String, alarmId: Long): Result<Unit> {
            acts += action to alarmId
            return if (ok) Result.success(Unit) else Result.failure(RuntimeException())
        }
    }

    @Test
    fun aButtonGoesToTheController() = runTest(main.dispatcher) {
        val actions = FakeActions()
        val vm = AlarmsViewModel(FakeLiveSource(), actions)
        backgroundScope.launch { vm.uiState.collect {} }
        vm.onAction(ActionReceiver.HANDLE, 4)
        assertEquals(listOf(ActionReceiver.HANDLE to 4L), actions.acts)
        assertNull(vm.uiState.value.userMessage)
    }

    @Test
    fun aFailedActionIsToldOnce() = runTest(main.dispatcher) {
        val vm = AlarmsViewModel(FakeLiveSource(), FakeActions(ok = false))
        backgroundScope.launch { vm.uiState.collect {} }
        vm.onAction(ActionReceiver.QUIET, 4)
        assertEquals(R.string.action_failed_body, vm.uiState.value.userMessage)
        vm.userMessageShown()
        assertNull(vm.uiState.value.userMessage)
    }
}
