package com.lumenarcanus.dialife.feature.planned

import androidx.annotation.StringRes
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.MealRules
import com.lumenarcanus.dialife.data.Meals
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Treatment
import kotlin.math.abs

/** The planned doses on home, oldest first. */
data class PlannedUiState(
    val cards: List<PlannedCardUiState> = emptyList(),
    val units: Units = Units.MgDl,
    // shown once, then PlannedViewModel.userMessageShown()
    @StringRes val userMessage: Int? = null
)

/**
 * One dose worked out for a meal and not given yet: calm at first, bright
 * after [LOUD_AFTER_MIN], and from the hour on the "was it given?" question.
 */
data class PlannedCardUiState(
    val dose: PlannedDose,
    val settings: DoseSettings,
    val now: Long,
    // the food entry's time, or when the dose was worked out
    val mealAt: Long,
    // the open "was it given?" question for it
    val question: Question?,
    // the rapid entry "it is recorded" refers to
    val recorded: Treatment?,
    // for the same-dose guard when entering it
    val treatments: List<Treatment>
) {
    val minutes get() = ((now - dose.createdAt) / 60_000L).toInt()
    val loud get() = minutes >= LOUD_AFTER_MIN
}

const val LOUD_AFTER_MIN = 30

fun plannedUiState(state: StateResponse?, treatments: List<Treatment>, units: Units, now: Long): PlannedUiState {
    val s = state ?: return PlannedUiState(units = units)
    val cards = s.planned.filter { it.state == "planned" }.map { p ->
        val mealAt = treatments.firstOrNull { it.id == p.mealId }?.t ?: p.createdAt
        PlannedCardUiState(
            dose = p,
            settings = s.dose,
            now = now,
            mealAt = mealAt,
            question = s.questions.firstOrNull { it.kind == "planned" && it.plannedId == p.id && it.state == "open" },
            recorded = nearRapid(treatments, mealAt, now, s.mealRules),
            treatments = treatments
        )
    }
    return PlannedUiState(cards, units)
}

/** Rapid near a meal that no other meal has: what "it is recorded" refers to. */
fun nearRapid(treatments: List<Treatment>, mealAt: Long, now: Long, rules: MealRules): Treatment? =
    treatments.filter { Meals.isRapid(it) && it.insulinRole != "corr" && it.mealId == null && it.t in (mealAt - rules.beforeMs)..now }
        .minByOrNull { abs(it.t - mealAt) }
