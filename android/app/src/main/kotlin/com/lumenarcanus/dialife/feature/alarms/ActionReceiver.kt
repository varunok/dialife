package com.lumenarcanus.dialife.feature.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.core.platform.runAsync
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.liveRepository
import kotlinx.coroutines.flow.first

/** Buttons on alarm notifications: answer the server without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val id = intent.getLongExtra(EXTRA_ID, 0)
        if (id == 0L) return
        runAsync { actFromNotification(context, action, id) }
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

/** An alarm button pressed: to the server, once more on failure, then "did not reach" if it still fails. */
internal suspend fun actFromNotification(context: Context, action: String, alarmId: Long) {
    // the process may have just been started by this tap
    if (context.liveRepository.credentials == null) {
        CredentialStore(context).credentials.first()?.let { context.liveRepository.start(it) }
    }
    val ctl = context.alarmController
    val r = ctl.act(action, alarmId).recoverCatching { ctl.act(action, alarmId).getOrThrow() } // one retry
    if (r.isFailure) ctl.failed()
}
