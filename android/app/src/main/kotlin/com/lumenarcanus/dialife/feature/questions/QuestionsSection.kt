package com.lumenarcanus.dialife.feature.questions

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.questionNotifier

@Composable
private fun questionsViewModel(): QuestionsViewModel {
    val ctx = LocalContext.current
    return viewModel { QuestionsViewModel(ctx.liveRepository, ctx.questionNotifier) }
}

/** The open questions on home; [onLog] opens the entry form for one ("enter it"). */
@Composable
fun QuestionsSection(onLog: (Question) -> Unit) {
    val ctx = LocalContext.current
    val vm = questionsViewModel()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    QuestionCards(uiState, vm::onAction, onLog)
}

/** The doses being watched, a quiet card each (the same view model as the questions). */
@Composable
fun WatchSection() {
    val uiState by questionsViewModel().uiState.collectAsStateWithLifecycle()
    WatchCards(uiState)
}

/** Only draws: a card per open question. */
@Composable
fun QuestionCards(uiState: QuestionsUiState, onAction: (QuestionAction) -> Unit, onLog: (Question) -> Unit) {
    uiState.open.forEach { q ->
        key("q", q.id) {
            QuestionCard(
                q,
                uiState.myId,
                uiState.treatments,
                uiState.units,
                onAnswer = { answer, tr -> onAction(QuestionAction.Answer(q.id, answer, tr)) },
                onLog = { onLog(q) },
                onLink = { onAction(QuestionAction.Link(it)) }
            )
        }
    }
}

/** Only draws: a card per watched dose. */
@Composable
fun WatchCards(uiState: QuestionsUiState) {
    uiState.watching.forEach { q -> key("w", q.id) { WatchCard(q) } }
}
