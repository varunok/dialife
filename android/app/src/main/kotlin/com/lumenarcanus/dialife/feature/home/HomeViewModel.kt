package com.lumenarcanus.dialife.feature.home

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.QuestionRepository
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.MeterReport
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.Link
import com.lumenarcanus.dialife.live.LiveSource
import com.lumenarcanus.dialife.live.withPending
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUi(
    val state: StateResponse? = null,
    val history: History? = null,
    val rangeHours: Int = 6,
    val offline: Boolean = false, // cannot reach the server; data shown is what we had
    val unauthorized: Boolean = false, // token revoked: back to the connect screen
    val myId: Long = 0, // this phone's device id: "you" in the episode banners
    val meter: MeterReport? = null, // sensor vs glucometer
    val now: Long = System.currentTimeMillis(),
    // shown once, then HomeViewModel.userMessageShown()
    @StringRes val userMessage: Int? = null
)

/**
 * The screen's view of the shared live connection, plus a clock for "minutes
 * ago"; entries saved and deleted, and answers to "did you forget to log?",
 * go through it.
 */
class HomeViewModel(private val repo: LiveSource, private val questions: QuestionRepository? = null) : ViewModel() {
    private val range = MutableStateFlow(6)
    private val userMessage = MutableStateFlow<Int?>(null)
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val ui: StateFlow<HomeUi> = combine(repo.live, repo.pending, range, clock, userMessage) { live, pending, hours, now, message ->
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
            userMessage = message
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeUi(state = repo.live.value.state, history = repo.live.value.history, myId = repo.myId)
    )

    fun setRange(hours: Int) {
        range.value = hours
    }

    fun save(t: Treatment) = repo.saveTreatment(t)

    fun delete(id: String) = repo.deleteTreatment(id)

    fun retry() = repo.reconnectNow()

    /** [treatmentId]: the entry that answers it, when the answer is one. */
    fun answer(id: Long, answer: String, treatmentId: String?) {
        val q = questions ?: return
        viewModelScope.launch { q.answer(id, answer, treatmentId).onFailure { userMessage.value = R.string.q_failed } }
    }

    fun dismiss(id: Long) {
        val q = questions ?: return
        viewModelScope.launch { q.dismiss(id).onFailure { userMessage.value = R.string.q_failed } }
    }

    fun userMessageShown() {
        userMessage.value = null
    }

    companion object {
        const val TICK_MS = 15_000L
        const val OFFLINE_AFTER_MS = 30_000L
    }
}
