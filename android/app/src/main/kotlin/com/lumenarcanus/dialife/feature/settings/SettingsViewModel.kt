package com.lumenarcanus.dialife.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.PreferencesRepository
import com.lumenarcanus.dialife.core.data.SessionRepository
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.feature.alarms.AlarmSound
import com.lumenarcanus.dialife.feature.sensorless.SensorSending
import com.lumenarcanus.dialife.feature.sensorless.SensorlessRepository
import com.lumenarcanus.dialife.live.LiveSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** This phone's settings: its own choices, the alarm sound test, disconnecting. */
class SettingsViewModel(
    creds: Credentials,
    live: LiveSource,
    private val prefs: PreferencesRepository,
    private val sessions: SessionRepository,
    private val ringer: AlarmSound,
    private val sensorless: SensorlessRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(creds, live.live.value.state, prefs.current()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var test: Job? = null

    init {
        viewModelScope.launch { live.live.collect { l -> _uiState.update { it.copy(state = l.state) } } }
        viewModelScope.launch { prefs.prefs.collect { p -> _uiState.update { it.copy(prefs = p) } } }
    }

    fun onAction(action: SettingsAction) {
        val p = prefs.current() // the stored choices: the UI state may not have caught up with the last tap
        when (action) {
            is SettingsAction.ChooseUnits -> prefs.chooseUnits(action.units)
            is SettingsAction.ChooseTheme -> prefs.chooseTheme(action.theme)
            is SettingsAction.ChooseLanguage -> prefs.chooseLanguage(action.tag)
            is SettingsAction.ChooseMeterLine -> prefs.chooseMeterLine(action.on)
            is SettingsAction.ChooseInsulin -> prefs.chooseInsulin(action.type, action.name)
            is SettingsAction.AddBasalTime -> prefs.chooseBasalTimes(p.basalTimes + action.minutes)
            is SettingsAction.RemoveBasalTime -> prefs.chooseBasalTimes(p.basalTimes - action.minutes)
            is SettingsAction.ChooseBasalReminder -> prefs.chooseBasalReminder(action.on)
            SettingsAction.TestSound -> toggleTest()
            is SettingsAction.SetSensorless -> sendSensorless { sensorless.setOn(action.on) }
            is SettingsAction.ChooseSensorlessRemind -> sendSensorless { sensorless.setRemind(action.minutes) }
        }
    }

    /** One change at a time; the new state comes back with the live stream, on every phone. */
    private fun sendSensorless(send: suspend () -> Result<Unit>) {
        if (_uiState.value.sensorlessBusy) return
        _uiState.update { it.copy(sensorlessBusy = true) }
        viewModelScope.launch {
            send().onFailure { e ->
                val message = if (e is SensorSending) R.string.set_sensorless_sending else R.string.set_sensorless_failed
                _uiState.update { it.copy(userMessage = message) }
            }
            _uiState.update { it.copy(sensorlessBusy = false) }
        }
    }

    fun userMessageShown() = _uiState.update { it.copy(userMessage = null) }

    /** The alarm sound for five seconds; tapped again, it stops. */
    private fun toggleTest() {
        if (test?.isActive == true) {
            test?.cancel()
            return
        }
        test = viewModelScope.launch {
            _uiState.update { it.copy(testing = true) }
            ringer.start(-1)
            try {
                delay(5_000)
            } finally {
                ringer.stop(-1)
                _uiState.update { it.copy(testing = false) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { sessions.disconnect() }
    }

    override fun onCleared() {
        if (test?.isActive == true) ringer.stop(-1)
    }
}
