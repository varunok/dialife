package com.lumenarcanus.dialife.feature.data

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment

/** The data screen: one window of the chart's span, as rows; [page] 0 ends now, 1 is the span before, … */
data class DataUiState(
    val from: Long,
    val to: Long,
    val history: History?,
    val entries: List<Entry>,
    val rows: List<DataRow>,
    val page: Int = 0,
    val filter: DataFilter = DataFilter.All,
    val step: Int = 5,
    val wide: Boolean = false,
    val loading: Boolean = false,
    val thresholds: Thresholds = Thresholds(),
    val units: Units = Units.MgDl,
    // the live day's entries: what the entry sheet compares a dose with
    val recent: List<Treatment> = emptyList(),
    // the live state and this phone's id, for the entry sheet
    val state: StateResponse? = null,
    val myId: Long = 0
)

sealed interface DataAction {
    data object Older : DataAction
    data object Newer : DataAction
    data class Filter(val filter: DataFilter) : DataAction
    data class Step(val minutes: Int) : DataAction
    data class Wide(val on: Boolean) : DataAction
}

/** The window [page] spans back from [now]. */
fun dataWindow(spanHours: Int, page: Int, now: Long): LongRange {
    val span = spanHours * HOUR
    val to = now - page * span
    return (to - span)..to
}

/** The live day holds 24 hours; a window reaching past it is loaded on its own. */
fun isOlder(window: LongRange, now: Long) = window.first < now - 24 * HOUR

fun dataUiState(
    live: History?,
    older: History?,
    questions: List<Question>,
    thresholds: Thresholds,
    units: Units,
    spanHours: Int,
    now: Long,
    page: Int = 0,
    filter: DataFilter = DataFilter.All,
    step: Int = 5,
    wide: Boolean = false,
    loading: Boolean = false,
    state: StateResponse? = null,
    myId: Long = 0
): DataUiState {
    val w = dataWindow(spanHours, page, now)
    val h = if (isOlder(w, now)) older else live
    val entries = entriesOf(h, questions, w.first, w.last)
    return DataUiState(
        from = w.first,
        to = w.last,
        history = h,
        entries = entries,
        rows = rowsOf(h, entries, w.first, w.last, step).filtered(filter, thresholds),
        page = page,
        filter = filter,
        step = step,
        wide = wide,
        loading = loading,
        thresholds = thresholds,
        units = units,
        recent = live?.treatments.orEmpty(),
        state = state,
        myId = myId
    )
}
