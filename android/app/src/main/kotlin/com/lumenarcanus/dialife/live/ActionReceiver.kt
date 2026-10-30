package com.lumenarcanus.dialife.live

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

/** Buttons on alarm notifications: answer the server without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val id = intent.getLongExtra(EXTRA_ID, 0)
        if (id == 0L) return
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // the process may have just been started by this tap
                if (context.liveRepository.credentials == null) {
                    CredentialStore(context).credentials.first()?.let { context.liveRepository.start(it) }
                }
                val ctl = context.alarmController
                if (action == ANSWER) {
                    val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return@launch
                    val questions = context.questionAnswers
                    val r = questions.answer(id, answer).recoverCatching { questions.answer(id, answer).getOrThrow() } // one retry
                    if (r.isFailure) ctl.failed()
                    return@launch
                }
                val r = ctl.act(action, id).recoverCatching { ctl.act(action, id).getOrThrow() } // one retry
                if (r.isFailure) ctl.failed()
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_ID = "alarm_id"
        const val HANDLE = "handle"
        const val QUIET = "quiet"
        const val OK = "ok"
        const val HELP = "help"
        const val COMING = "coming"
        const val RESOLVE = "resolve"
        const val ANSWER = "answer" // a question: EXTRA_ID is the question, EXTRA_ANSWER what happened
        const val EXTRA_ANSWER = "answer"
    }
}
