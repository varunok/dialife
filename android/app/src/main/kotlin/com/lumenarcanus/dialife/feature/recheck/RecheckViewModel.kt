package com.lumenarcanus.dialife.feature.recheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.Recheck
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

/** What the card shows: the open recheck as of [now] (it turns "due" without new data), unless closed here already. */
data class RecheckUiState(
    val recheck: Recheck? = null,
    val due: Boolean = false,
    val units: Units = Units.MgDl,
    val userMessage: Int? = null
)

fun recheckUiState(r: Recheck?, units: Units, now: Long, closedHere: Set<Long>): RecheckUiState {
    val open = r?.takeIf { it.id !in closedHere } ?: return RecheckUiState(units = units)
    return RecheckUiState(open, due = open.state == "due" || (open.state == "waiting" && now >= open.dueAt), units = units)
}

class RecheckViewModel(private val live: LiveSource, private val repository: RecheckRepository) : ViewModel() {
    private val closedHere = MutableStateFlow(emptySet<Long>())
    private val userMessage = MutableStateFlow<Int?>(null)
    private val sending = mutableSetOf<Long>()
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<RecheckUiState> = combine(live.live, closedHere, userMessage, clock) { l, closed, message, now ->
        val units = unitsOf(l.state?.displayUnits ?: live.credentials?.displayUnits)
        recheckUiState(l.state?.recheck, units, now, closed).copy(userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecheckUiState())

    /** "Close for everyone". */
    fun close(id: Long) {
        if (!sending.add(id)) return
        viewModelScope.launch {
            repository.close(id)
                .onSuccess { closedHere.update { it + id } }
                .onFailure { userMessage.value = R.string.recheck_close_failed }
            sending.remove(id)
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }

    private companion object {
        const val TICK_MS = 15_000L
    }
}
