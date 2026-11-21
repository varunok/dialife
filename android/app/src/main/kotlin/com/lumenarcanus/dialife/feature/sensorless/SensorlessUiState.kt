package com.lumenarcanus.dialife.feature.sensorless

import androidx.annotation.StringRes
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.CheckCard
import com.lumenarcanus.dialife.data.Sensorless

/** The mode's cards on home: a check out of range, the reminder to measure. */
data class SensorlessUiState(
    val card: CheckCard? = null,
    // the reminder is due, with how long since the newest check (null: none in a week)
    val remind: Remind? = null,
    val units: Units = Units.MgDl,
    // shown once, then SensorlessViewModel.userMessageShown()
    @StringRes val userMessage: Int? = null
)

data class Remind(val sinceCheckMs: Long?)

/** [closedHere]: cards closed on this phone, until the server's state says so too. */
fun sensorlessUiState(s: Sensorless?, units: Units, now: Long, closedHere: Set<Long> = emptySet()): SensorlessUiState {
    if (s == null) return SensorlessUiState(units = units)
    val card = s.card?.takeIf { it.t !in closedHere }
    val remind = if (s.remindDue) {
        Remind(s.lastCheck?.let { (now - it.t).coerceAtLeast(0) })
    } else {
        null
    }
    return SensorlessUiState(card, remind, units)
}
