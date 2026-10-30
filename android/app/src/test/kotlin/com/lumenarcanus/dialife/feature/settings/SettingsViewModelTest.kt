package com.lumenarcanus.dialife.feature.settings

import com.lumenarcanus.dialife.core.data.LocalPrefs
import com.lumenarcanus.dialife.core.data.PreferencesRepository
import com.lumenarcanus.dialife.core.data.SessionRepository
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.ThemeChoice
import com.lumenarcanus.dialife.feature.alarms.AlarmSound
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private class FakePrefs : PreferencesRepository {
        override val prefs = MutableStateFlow(LocalPrefs(basalTimes = listOf(21 * 60)))

        override fun current() = prefs.value

        private fun set(f: LocalPrefs.() -> LocalPrefs) {
            prefs.value = prefs.value.f()
        }

        override fun chooseUnits(u: Units?) = set { copy(units = u) }

        override fun chooseTheme(t: ThemeChoice) = set { copy(theme = t) }

        override fun chooseLanguage(tag: String) = set { copy(language = tag) }

        override fun chooseMeterLine(on: Boolean) = set { copy(meterLine = on) }

        override fun chooseInsulin(type: InsulinType, name: String) =
            set { if (type == InsulinType.Basal) copy(basalName = name) else copy(rapidName = name) }

        override fun chooseBasalTimes(times: List<Int>) = set { copy(basalTimes = times.distinct().sorted()) }

        override fun chooseBasalReminder(on: Boolean) = set { copy(basalReminder = on) }
    }

    private class FakeSound : AlarmSound {
        val calls = mutableListOf<String>()

        override fun start(alarmId: Long) {
            calls += "start $alarmId"
        }

        override fun stop(alarmId: Long) {
            calls += "stop $alarmId"
        }
    }

    private class FakeSessions : SessionRepository {
        var disconnected = false
        override val credentials = flowOf<Credentials?>(null)
        override val revoked = MutableStateFlow(false)

        override suspend fun connect(server: String, token: String) = Result.success(Unit)

        override suspend fun revoke() {}

        override suspend fun disconnect() {
            disconnected = true
        }
    }

    private val creds = Credentials("https://dia.example.com", "t", "Тато", "caregiver", "mg/dl", 7)

    @Test
    fun choicesAreSavedAndShown() = runTest(main.dispatcher) {
        val prefs = FakePrefs()
        val vm = SettingsViewModel(creds, FakeLiveSource(), prefs, FakeSessions(), FakeSound())
        vm.onAction(SettingsAction.ChooseUnits(Units.Mmol))
        vm.onAction(SettingsAction.AddBasalTime(9 * 60))
        vm.onAction(SettingsAction.RemoveBasalTime(21 * 60))
        vm.onAction(SettingsAction.ChooseInsulin(InsulinType.Basal, "Lantus"))
        runCurrent()
        val p = vm.uiState.value.prefs
        assertEquals(Units.Mmol, p.units)
        assertEquals(listOf(9 * 60), p.basalTimes)
        assertEquals("Lantus", p.basalName)
    }

    @Test
    fun theTestSoundPlaysFiveSeconds() = runTest(main.dispatcher) {
        val sound = FakeSound()
        val vm = SettingsViewModel(creds, FakeLiveSource(), FakePrefs(), FakeSessions(), sound)
        vm.onAction(SettingsAction.TestSound)
        runCurrent()
        assertTrue(vm.uiState.value.testing)
        assertEquals(listOf("start -1"), sound.calls)
        advanceTimeBy(5_001)
        assertFalse(vm.uiState.value.testing)
        assertEquals(listOf("start -1", "stop -1"), sound.calls)
    }

    @Test
    fun stopStopsTheTestSound() = runTest(main.dispatcher) {
        val sound = FakeSound()
        val vm = SettingsViewModel(creds, FakeLiveSource(), FakePrefs(), FakeSessions(), sound)
        vm.onAction(SettingsAction.TestSound)
        runCurrent()
        vm.onAction(SettingsAction.TestSound)
        runCurrent()
        assertFalse(vm.uiState.value.testing)
        assertEquals(listOf("start -1", "stop -1"), sound.calls)
    }

    @Test
    fun disconnectingForgetsTheKey() = runTest(main.dispatcher) {
        val sessions = FakeSessions()
        val vm = SettingsViewModel(creds, FakeLiveSource(), FakePrefs(), sessions, FakeSound())
        vm.disconnect()
        runCurrent()
        assertTrue(sessions.disconnected)
    }
}
