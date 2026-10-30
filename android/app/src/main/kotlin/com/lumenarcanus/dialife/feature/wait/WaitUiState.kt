package com.lumenarcanus.dialife.feature.wait

import androidx.annotation.StringRes
import com.lumenarcanus.dialife.data.Wait
import com.lumenarcanus.dialife.ui.home.Units
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Everything the wait shows on home. */
data class WaitUiState(
    val countdown: CountdownUiState? = null,
    val warning: WarningUiState? = null,
    val units: Units = Units.MgDl,
    @StringRes val userMessage: Int? = null, // shown once, then WaitViewModel.userMessageShown()
)

/** The countdown card: the server's wait seen at [now]. */
data class CountdownUiState(val wait: Wait, val now: Long) {
    val doseId get() = wait.doseId
    val eatNow get() = wait.reason == "low" || wait.reason == "falling"
    val totalMs get() = (wait.eatFrom - wait.doseAt).coerceAtLeast(1)
    val leftMs get() = (wait.eatFrom - now).coerceAtLeast(0)
    val leftMin get() = ceil(leftMs / 60_000.0).toInt()
    val waitMin get() = (totalMs / 60_000).toInt()
    val agoMin get() = ((now - wait.doseAt) / 60_000).toInt()
}

/** The "more insulin than food" card: grams the dose covers and grams entered. */
data class WarningUiState(val doseId: String, val doseAt: Long, val units: Double, val covers: Int, val food: Int)

/** [closedHere]: "doseId:kind" pressed on this phone, until the server's state says so too. */
fun waitUiState(w: Wait?, ratio: Int, units: Units, now: Long, closedHere: Set<String> = emptySet()): WaitUiState {
    if (w == null) return WaitUiState(units = units)
    val countdown = CountdownUiState(w, now).takeIf { !w.closed && "${w.doseId}:wait" !in closedHere }
    val warning = warningUiState(w, ratio).takeIf { "${w.doseId}:more" !in closedHere }
    return WaitUiState(countdown, warning, units)
}

/** Over a unit's worth of the dose with no food entered for it. */
fun warningUiState(w: Wait, ratio: Int): WarningUiState? {
    val food = w.foodG.roundToInt()
    val covers = w.coversG.roundToInt()
    if (w.moreClosed || food <= 0 || covers - food <= ratio) return null
    return WarningUiState(w.doseId, w.doseAt, w.units, covers, food)
}

/** "You can eat" sounds for it: still open and not "eat now" (the alarms speak for a low). */
fun Wait.sounds() = !closed && reason != "low" && reason != "falling"
