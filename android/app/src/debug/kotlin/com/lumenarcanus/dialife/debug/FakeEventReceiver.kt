package com.lumenarcanus.dialife.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.liveRepository

/**
 * adb shell am broadcast -n com.lumenarcanus.dialife/.debug.FakeEventReceiver \
 *   --es type alarm --es data '{"alarm":{"id":999001,"kind":"low","level":"urgent","sgv":52,"started_at":0}}'
 *
 * Only adb (DUMP permission) can send it, and only debug builds have it.
 */
class FakeEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return
        if (type == "basal_reminder") { // show the reminder as it looks, whatever the schedule
            com.lumenarcanus.dialife.live.BasalReminder.show(context, System.currentTimeMillis() + 3_600_000L)
            return
        }
        val data = intent.getStringExtra("data") ?: return
        context.liveRepository.debugEvent(type, data)
    }
}
