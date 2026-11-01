package com.lumenarcanus.dialife.feature.questions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.core.platform.runAsync
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.questionAnswers
import kotlinx.coroutines.flow.first

/** An answer button on a question's notification: to the server without opening the app. */
class QuestionAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0)
        val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return
        if (id == 0L) return
        runAsync { answerFromNotification(context, id, answer) }
    }

    companion object {
        const val EXTRA_ID = "question_id"
        const val EXTRA_ANSWER = "answer"
    }
}

/** A question answered from its notification: once more on failure, then "did not reach" if it still fails. */
internal suspend fun answerFromNotification(context: Context, questionId: Long, answer: String) {
    // the process may have just been started by this tap
    val live = context.liveRepository
    if (live.credentials == null) CredentialStore(context).credentials.first()?.let { live.start(it) }
    val questions = context.questionAnswers
    val r = questions.answer(questionId, answer).recoverCatching { questions.answer(questionId, answer).getOrThrow() } // one retry
    if (r.isFailure) context.alarmController.failed()
}
