package com.lumenarcanus.dialife.feature.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.CalibrationAdvice
import com.lumenarcanus.dialife.live.LiveSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the card shows as of now: "calibrate now" only until the check is 5 minutes old, then the reason to wait. */
data class CalibrationUiState(
    val advice: CalibrationAdvice? = null,
    val units: Units = Units.MgDl,
    val absorbMin: Int = 180,
    val userMessage: Int? = null
)

/** The advice as of [now]; [closedSince]: closed on this phone for that stretch, until the server says so too. */
fun calibrationUiState(a: CalibrationAdvice?, units: Units, absorbMin: Int, now: Long, closedSince: Long?): CalibrationUiState {
    if (a == null || a.since == closedSince) return CalibrationUiState(units = units, absorbMin = absorbMin)
    val shown = if (a.now != null && now >= a.now.until) a.copy(now = null, wait = listOf("old")) else a
    return CalibrationUiState(shown, units, absorbMin)
}

class CalibrationViewModel(private val live: LiveSource, private val repository: CalibrationRepository) : ViewModel() {
    private val closedSince = MutableStateFlow<Long?>(null)
    private val userMessage = MutableStateFlow<Int?>(null)
    private var sending = false
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<CalibrationUiState> = combine(live.live, closedSince, userMessage, clock) { l, closed, message, now ->
        val s = l.state
        val units = unitsOf(s?.displayUnits ?: live.credentials?.displayUnits)
        calibrationUiState(s?.calibration, units, s?.dose?.carbMin ?: 180, now, closed).copy(userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalibrationUiState())

    /** "Close for everyone". */
    fun close(since: Long) {
        if (sending) return
        sending = true
        viewModelScope.launch {
            repository.close()
                .onSuccess { closedSince.value = since }
                .onFailure { userMessage.value = R.string.calib_close_failed }
            sending = false
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }

    private companion object {
        const val TICK_MS = 15_000L
    }
}
