package com.lumenarcanus.dialife.feature.wait

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

/** The wait on home: the server's state, ticking for the countdown; a close goes at once here. */
class WaitViewModel(private val live: LiveSource, private val repository: WaitRepository) : ViewModel() {
    private val closedHere = MutableStateFlow(emptySet<String>())
    private val userMessage = MutableStateFlow<Int?>(null)
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<WaitUiState> = combine(live.live, closedHere, userMessage, clock) { l, closed, message, now ->
        val s = l.state
        val units = unitsOf(s?.displayUnits ?: live.credentials?.displayUnits)
        waitUiState(s?.wait, s?.dose?.ratio ?: 0, units, now, closed).copy(userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WaitUiState())

    fun onAction(a: WaitAction) {
        val key = "${a.doseId}:${a.kind}"
        closedHere.update { it + key }
        viewModelScope.launch {
            repository.close(a).onFailure {
                closedHere.update { it - key } // the card is back
                userMessage.value = R.string.q_failed
            }
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }

    private companion object {
        const val TICK_MS = 10_000L
    }
}
