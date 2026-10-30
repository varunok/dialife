package com.lumenarcanus.dialife.feature.questions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.QuestionNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The questions on home: from the live state; an answer clears its notification here. */
class QuestionsViewModel(private val live: LiveRepository, private val notifier: QuestionNotifier) : ViewModel() {
    private val userMessage = MutableStateFlow<Int?>(null)
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<QuestionsUiState> = combine(live.live, userMessage, clock) { l, message, now ->
        val units = unitsOf(l.state?.displayUnits ?: live.credentials?.displayUnits)
        questionsUiState(l.state, l.history?.treatments.orEmpty(), live.myId, units, now).copy(userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuestionsUiState())

    fun onAction(a: QuestionAction) {
        when (a) {
            is QuestionAction.Link -> live.saveTreatment(a.treatment)
            is QuestionAction.Answer -> viewModelScope.launch {
                live.answerQuestion(a.id, a.answer, a.treatmentId)
                    .onSuccess { notifier.cancel(a.id) }
                    .onFailure { userMessage.value = R.string.q_failed }
            }
        }
    }

    fun userMessageShown() {
        userMessage.value = null
    }

    private companion object {
        const val TICK_MS = 15_000L
    }
}
