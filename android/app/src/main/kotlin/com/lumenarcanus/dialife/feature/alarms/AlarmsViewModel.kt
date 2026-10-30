package com.lumenarcanus.dialife.feature.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.feature.alarms.AlarmController
import com.lumenarcanus.dialife.live.LiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The live episodes on home: from the live state; an action goes through the alarm controller (it also stops the sound). */
class AlarmsViewModel(private val live: LiveRepository, private val controller: AlarmController) : ViewModel() {
    private val userMessage = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<AlarmsUiState> = combine(live.live, userMessage) { l, message ->
        val units = unitsOf(l.state?.displayUnits ?: live.credentials?.displayUnits)
        alarmsUiState(l.state, live.myId, units).copy(userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmsUiState())

    /** [action]: one of ActionReceiver's (handle, quiet, ok, help, coming, resolve). */
    fun onAction(action: String, alarmId: Long) {
        viewModelScope.launch {
            controller.act(action, alarmId).onFailure { userMessage.value = R.string.action_failed_body }
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }
}
