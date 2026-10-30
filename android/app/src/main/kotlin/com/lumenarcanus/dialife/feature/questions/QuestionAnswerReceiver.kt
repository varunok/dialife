package com.lumenarcanus.dialife.feature.questions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.questionAnswers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** An answer button on a question's notification: to the server without opening the app. */
class QuestionAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0)
        val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return
        if (id == 0L) return
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // the process may have just been started by this tap
                val live = context.liveRepository
                if (live.credentials == null) CredentialStore(context).credentials.first()?.let { live.start(it) }
                val questions = context.questionAnswers
                val r = questions.answer(id, answer).recoverCatching { questions.answer(id, answer).getOrThrow() } // one retry
                if (r.isFailure) context.alarmController.failed()
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "question_id"
        const val EXTRA_ANSWER = "answer"
    }
}
