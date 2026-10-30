package com.lumenarcanus.dialife.feature.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.liveRepository
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
    }
}
