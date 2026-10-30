package com.lumenarcanus.dialife.feature.meal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.core.data.PlannedDoseRepository
import com.lumenarcanus.dialife.core.data.QuestionRepository
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.MealRules
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.live.LiveSource
import com.lumenarcanus.dialife.live.withPending
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The food form's data and its save: the entries go to the queue (offline
 * too), the planned dose and the answer to "did you forget to log?" to the
 * server. [onPlanFailed] tells the parent when the planned dose did not get there.
 */
class MealViewModel(
    private val live: LiveSource,
    private val plannedDoses: PlannedDoseRepository,
    private val questions: QuestionRepository,
    private val onPlanFailed: () -> Unit
) : ViewModel() {
    val inputs: StateFlow<MealInputs> = combine(live.live, live.pending) { l, pending ->
        val s = l.state
        MealInputs(
            treatments = l.history?.treatments.orEmpty().withPending(pending),
            dose = s?.dose ?: DoseSettings(),
            planned = s?.planned.orEmpty(),
            units = unitsOf(s?.displayUnits ?: live.credentials?.displayUnits),
            mealRules = s?.mealRules ?: MealRules(),
            mbg = l.history?.mbg ?: Series()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MealInputs())

    /** [questionId]: the "did you forget to log?" this food answers, 0 for none. */
    fun save(s: MealSave, questionId: Long) {
        live.saveTreatment(s.food)
        s.reading?.let(live::saveTreatment)
        s.planned?.let { p ->
            viewModelScope.launch { plannedDoses.save(p).onFailure { onPlanFailed() } }
        }
        if (questionId != 0L) {
            viewModelScope.launch {
                questions.answer(questionId, "food", s.food.id)
            }
        }
    }
}
