package com.lumenarcanus.dialife.live

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.MainActivity
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.data.Wait
import com.lumenarcanus.dialife.liveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "You can eat": an ordinary notification when the wait on home runs out, on
 * every phone, each from the state it already has. None for "eat now" (low or
 * falling: the alarms speak there) and none once "already ate" was pressed on
 * any phone; pressing it on the notification closes it for all. When the
 * entries behind it are deleted it goes quietly with the state that drops it.
 */
object EatReminder {
    private const val ID = 5
    private const val LATE_MS = 10 * 60_000L // a phone that learns of it later than this stays quiet
    internal const val EXTRA_DOSE = "dose_id"
    private const val PREFS = "eat_reminder"
    private const val KEY_SHOWN = "shown_for" // the dose it was shown for: once per meal

    /** (Re)arms it for the wait the server sends, or takes it away. Call on every change of the wait. */
    fun sync(context: Context, wait: Wait?) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, null))
        val due = wait?.takeIf { it.counts() }
        if (due == null || shownFor(context) != due.doseId) cancel(context)
        if (due == null) return
        val now = System.currentTimeMillis()
        when {
            due.eatFrom > now -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due.eatFrom, pending(context, due.doseId))
            now - due.eatFrom < LATE_MS && shownFor(context) != due.doseId -> show(context, due)
        }
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)

    private fun Wait.counts() = !closed && reason != "low" && reason != "falling"

    /**
     * Time is up: asks the server first, so an entry deleted or "already ate"
     * pressed while this phone was out of touch makes no sound. No answer, no sound.
     */
    internal suspend fun fire(context: Context, doseId: String) {
        val repo = context.liveRepository
        // the process may have been started just for this
        if (repo.credentials == null) CredentialStore(context).credentials.first()?.let { repo.start(it) }
        val wait = repo.freshState().getOrNull()?.wait
        if (wait == null || wait.doseId != doseId || !wait.counts() || shownFor(context) == doseId) return
        show(context, wait)
    }

    private fun show(context: Context, w: Wait) {
        val open = PendingIntent.getActivity(
            context, 5, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val eaten = PendingIntent.getBroadcast(
            context, 6,
            Intent(context, ActionReceiver::class.java)
                .putExtra(ActionReceiver.EXTRA_ACTION, ActionReceiver.EATEN)
                .putExtra(ActionReceiver.EXTRA_DOSE, w.doseId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val units = if (w.units % 1.0 == 0.0) w.units.toInt().toString() else w.units.toString()
        val n = Notification.Builder(context, Notifications.CHANNEL_EAT)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(context.getString(R.string.wait_go))
            .setContentText(context.getString(R.string.wait_fact_dose, units, AlarmText.clock(context, w.doseAt)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.wait_eaten), eaten).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SHOWN, w.doseId).apply()
    }

    private fun shownFor(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SHOWN, null)

    private fun pending(context: Context, doseId: String?) = PendingIntent.getBroadcast(
        context, 4, Intent(context, EatReminderReceiver::class.java).apply { doseId?.let { putExtra(EXTRA_DOSE, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class EatReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dose = intent.getStringExtra(EatReminder.EXTRA_DOSE) ?: return
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                EatReminder.fire(context, dose)
            } finally {
                done.finish()
            }
        }
    }
}
