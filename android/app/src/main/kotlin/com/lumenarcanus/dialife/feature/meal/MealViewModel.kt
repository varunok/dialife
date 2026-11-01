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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where a save of the form is. */
enum class MealSaveStatus { Editing, Saving, Failed, Saved }

/**
 * The food form's data and its save. What needs the server (the planned dose,
 * the answer to "did you forget to log?") goes first, and the entries go to
 * the queue only once the server took it: a failed save leaves nothing
 * behind, so trying again cannot count the meal twice. A save with nothing
 * for the server goes straight to the queue (offline too).
 */
class MealViewModel(
    private val live: LiveSource,
    private val plannedDoses: PlannedDoseRepository,
    private val questions: QuestionRepository
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

    private val _status = MutableStateFlow(MealSaveStatus.Editing)
    val status: StateFlow<MealSaveStatus> = _status.asStateFlow()

    /** [questionId]: the "did you forget to log?" this food answers, 0 for none. */
    fun save(s: MealSave, questionId: Long) {
        if (_status.value == MealSaveStatus.Saving || _status.value == MealSaveStatus.Saved) return
        if (s.planned == null && questionId == 0L) return queue(s)
        _status.value = MealSaveStatus.Saving
        viewModelScope.launch {
            val r = (s.planned?.let { plannedDoses.save(it) } ?: Result.success(Unit)).mapCatching {
                if (questionId != 0L) questions.answer(questionId, "food", s.food.id).getOrThrow()
            }
            if (r.isSuccess) queue(s) else _status.value = MealSaveStatus.Failed
        }
    }

    /** The failure was shown: the form is back to editing. */
    fun failureSeen() {
        if (_status.value == MealSaveStatus.Failed) _status.value = MealSaveStatus.Editing
    }

    private fun queue(s: MealSave) {
        live.saveTreatment(s.food)
        s.reading?.let(live::saveTreatment)
        _status.value = MealSaveStatus.Saved
    }
}
