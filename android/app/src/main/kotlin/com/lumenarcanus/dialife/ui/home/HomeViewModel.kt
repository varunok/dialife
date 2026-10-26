package com.lumenarcanus.dialife.ui.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.live.Link
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.withPending
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class HomeUi(
    val state: StateResponse? = null,
    val history: History? = null,
    val rangeHours: Int = 6,
    val offline: Boolean = false, // cannot reach the server; data shown is what we had
    val unauthorized: Boolean = false, // token revoked: back to the connect screen
    val myId: Long = 0, // this phone's device id: "you" in the episode banners
    val meter: com.lumenarcanus.dialife.data.MeterReport? = null, // sensor vs glucometer
    val now: Long = System.currentTimeMillis(),
)

/** The screen's view of the shared live connection, plus a clock for "minutes ago". */
class HomeViewModel(repo: LiveRepository) : ViewModel() {
    private val range = MutableStateFlow(6)
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val ui: StateFlow<HomeUi> = combine(repo.live, repo.pending, range, clock) { live, pending, hours, now ->
        HomeUi(
            state = live.state,
            history = live.history?.let { it.copy(treatments = it.treatments.withPending(pending)) },
            rangeHours = hours,
            // a reconnect after a server restart takes seconds: only call it offline when it lasts
            offline = live.link == Link.Offline &&
                SystemClock.elapsedRealtime() - live.lastLiveAt > OFFLINE_AFTER_MS,
            unauthorized = live.unauthorized,
            myId = repo.myId,
            meter = live.meter,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi(state = repo.live.value.state, history = repo.live.value.history))

    fun setRange(hours: Int) {
        range.value = hours
    }

    companion object {
        const val TICK_MS = 15_000L
        const val OFFLINE_AFTER_MS = 30_000L
    }
}
