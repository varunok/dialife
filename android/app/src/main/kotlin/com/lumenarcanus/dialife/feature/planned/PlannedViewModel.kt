package com.lumenarcanus.dialife.feature.planned

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.PlannedDoseRepository
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.withPending
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The planned doses on home: from the live state; given, changed or skipped on every phone. */
class PlannedViewModel(private val live: LiveRepository, private val repository: PlannedDoseRepository) : ViewModel() {
    private val userMessage = MutableStateFlow<Int?>(null)
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<PlannedUiState> = combine(live.live, live.pending, userMessage, clock) { l, pending, message, now ->
        val treatments = l.history?.treatments.orEmpty().withPending(pending)
        val units = unitsOf(l.state?.displayUnits ?: live.credentials?.displayUnits)
        plannedUiState(l.state, treatments, units, now).copy(userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannedUiState())

    /** Given: the insulin entry first (it waits in the queue offline), then the dose closed on the server. */
    fun onAction(a: PlannedAction) {
        viewModelScope.launch {
            val r = when (a) {
                is PlannedAction.Given -> {
                    if (a.isNew) {
                        live.saveTreatment(a.insulin)
                    } else if (a.insulin.mealId == null) {
                        live.saveTreatment(a.insulin.copy(mealId = a.planned.mealId, insulinRole = "meal"))
                    }
                    repository.given(a.planned.id, a.insulin.id)
                }
                is PlannedAction.Change -> repository.save(a.planned.copy(dose = a.dose))
                is PlannedAction.Skip -> repository.skip(a.planned.id)
            }
            // the server sends every phone the new state itself
            r.onFailure { userMessage.value = R.string.planned_failed }
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }

    private companion object {
        const val TICK_MS = 15_000L
    }
}
