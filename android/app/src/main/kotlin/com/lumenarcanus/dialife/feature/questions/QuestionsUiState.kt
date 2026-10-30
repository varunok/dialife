package com.lumenarcanus.dialife.feature.questions

import androidx.annotation.StringRes
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Treatment

/** "Did you forget to log?" on home: the open questions and the doses being watched. */
data class QuestionsUiState(
    // open, oldest first; a planned dose's question goes on its own card
    val open: List<Question> = emptyList(),
    // "watching": a second dose, until its time is up
    val watching: List<Question> = emptyList(),
    // who "I" am among the answers
    val myId: Long = 0,
    // for "it is already entered" and a dose said to be for the meal
    val treatments: List<Treatment> = emptyList(),
    val units: Units = Units.MgDl,
    // shown once, then QuestionsViewModel.userMessageShown()
    @StringRes val userMessage: Int? = null
)

fun questionsUiState(state: StateResponse?, treatments: List<Treatment>, myId: Long, units: Units, now: Long): QuestionsUiState {
    val qs = state?.questions.orEmpty()
    return QuestionsUiState(
        open = qs.filter { it.state == "open" && it.kind != "planned" },
        watching = qs.filter { it.watch && (it.watchUntil ?: 0) > now },
        myId = myId,
        treatments = treatments,
        units = units
    )
}
