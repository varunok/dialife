package com.lumenarcanus.dialife.feature.sensorless

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.live.LiveSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The mode's cards on home: the server's state, ticking for "how long ago"; a card closes here once the server took it. */
class SensorlessViewModel(private val live: LiveSource, private val repository: SensorlessRepository) : ViewModel() {
    private val closedHere = MutableStateFlow(emptySet<Long>())
    private val sending = mutableSetOf<Long>()
    private val userMessage = MutableStateFlow<Int?>(null)
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<SensorlessUiState> = combine(live.live, closedHere, userMessage, clock) { l, closed, message, now ->
        val s = l.state
        val units = unitsOf(s?.displayUnits ?: live.credentials?.displayUnits)
        sensorlessUiState(s?.sensorless, units, now, closed).copy(userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorlessUiState())

    /** "Close for everyone" on a check out of range. */
    fun closeCard(t: Long) {
        if (!sending.add(t)) return // a second tap while the first is on its way
        viewModelScope.launch {
            repository.closeCard(t)
                .onSuccess { closedHere.update { it + t } }
                .onFailure { userMessage.value = R.string.set_sensorless_failed }
            sending.remove(t)
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
