package com.lumenarcanus.dialife.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.core.platform.runAsync
import com.lumenarcanus.dialife.feature.alarms.actFromNotification
import com.lumenarcanus.dialife.feature.questions.answerFromNotification
import com.lumenarcanus.dialife.feature.wait.EatReminder

// Notifications shown, and timers set, by an app from before 2026-11 point at
// these class names. They stay so an update does not leave dead buttons on the
// screen; they can go once every phone has run the new version for a while.

/** An alarm, question or "already ate" button on a notification from before the update. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: return
        val id = intent.getLongExtra("alarm_id", 0) // the question's id for "answer"
        val dose = intent.getStringExtra("dose_id")
        when {
            action == "eaten" && dose != null -> runAsync { EatReminder.eaten(context, dose) }
            action == "answer" && id != 0L -> {
                val answer = intent.getStringExtra("answer") ?: return
                runAsync { answerFromNotification(context, id, answer) }
            }
            id != 0L -> runAsync { actFromNotification(context, action, id) }
        }
    }
}

/** A "you can eat" timer set before the update. */
class EatReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dose = intent.getStringExtra("dose_id") ?: return
        runAsync { EatReminder.fire(context, dose) }
    }
}
