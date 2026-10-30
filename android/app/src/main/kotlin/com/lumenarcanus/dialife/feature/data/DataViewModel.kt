package com.lumenarcanus.dialife.feature.data

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.Live
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.PendingOp
import com.lumenarcanus.dialife.live.withPending
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The chart's data as a table, paged back by the chart's span. The live day
 * comes from the live connection (with entries not sent yet); an older window
 * is loaded when paged to. Paging, filter, step and the wide table survive a
 * rotation and the process being let go.
 */
class DataViewModel(
    private val live: LiveRepository,
    private val repository: DataRepository,
    private val spanHours: Int,
    private val saved: SavedStateHandle,
    private val now: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val clock = flow {
        while (true) {
            emit(now())
            delay(TICK_MS)
        }
    }
    private val older = MutableStateFlow<History?>(null)
    private val loading = MutableStateFlow(false)
    private val controls = combine(
        saved.getStateFlow(PAGE, 0),
        saved.getStateFlow(FILTER, DataFilter.All),
        saved.getStateFlow(STEP, 5),
        saved.getStateFlow(WIDE, false)
    ) { page, filter, step, wide -> Controls(page, filter, step, wide) }

    val uiState: StateFlow<DataUiState> = combine(live.live, live.pending, controls, clock, combine(older, loading, ::Pair)) {
            l,
            pending,
            c,
            t,
            (old, busy)
        ->
        state(l, pending, c, t, old, busy)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        state(
            live.live.value,
            live.pending.value,
            Controls(
                saved[PAGE] ?: 0,
                saved[FILTER] ?: DataFilter.All,
                saved[STEP] ?: 5,
                saved[WIDE] ?: false
            ),
            now(),
            null,
            false
        )
    )

    private fun state(l: Live, pending: List<PendingOp>, c: Controls, t: Long, old: History?, busy: Boolean) = dataUiState(
        live = l.history?.let { it.copy(treatments = it.treatments.withPending(pending)) },
        older = old,
        questions = l.state?.questions.orEmpty(),
        thresholds = l.state?.thresholds ?: Thresholds(),
        units = unitsOf(l.state?.displayUnits ?: live.credentials?.displayUnits),
        spanHours = spanHours,
        now = t,
        page = c.page,
        filter = c.filter,
        step = c.step,
        wide = c.wide,
        loading = busy
    )

    init {
        viewModelScope.launch {
            saved.getStateFlow(PAGE, 0).collectLatest { page ->
                older.value = null
                val w = dataWindow(spanHours, page, now())
                if (isOlder(w, now())) {
                    loading.value = true
                    older.value = repository.history(w.first, w.last)
                    loading.value = false
                }
            }
        }
    }

    fun onAction(action: DataAction) {
        when (action) {
            DataAction.Older -> saved[PAGE] = saved.get<Int>(PAGE).orZero() + 1
            DataAction.Newer -> saved[PAGE] = (saved.get<Int>(PAGE).orZero() - 1).coerceAtLeast(0)
            is DataAction.Filter -> saved[FILTER] = action.filter
            is DataAction.Step -> saved[STEP] = action.minutes
            is DataAction.Wide -> saved[WIDE] = action.on
        }
    }

    fun save(tr: Treatment) = live.saveTreatment(tr)

    private fun Int?.orZero() = this ?: 0

    private data class Controls(val page: Int, val filter: DataFilter, val step: Int, val wide: Boolean)

    private companion object {
        const val TICK_MS = 15_000L
        const val PAGE = "page"
        const val FILTER = "filter"
        const val STEP = "step"
        const val WIDE = "wide"
    }
}
