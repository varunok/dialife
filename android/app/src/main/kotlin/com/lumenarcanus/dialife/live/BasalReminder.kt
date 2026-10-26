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
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.liveRepository
import java.util.Calendar

/**
 * "In an hour: long-acting, Lantus at 21:00" — an hour before each scheduled
 * basal time, unless the dose is already in. Each phone keeps its own schedule.
 */
object BasalReminder {
    private const val AHEAD_MS = 60 * 60_000L
    private const val ID = 4

    /** (Re)arms the next reminder, or clears it when switched off. Call after any schedule change. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context))
        if (!Prefs.basalReminder || Prefs.basalTimes.isEmpty()) return
        val next = nextDue(System.currentTimeMillis()) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first - AHEAD_MS, pending(context))
    }

    /** The next basal time whose reminder is still ahead: (due time, minutes after midnight). */
    internal fun nextDue(now: Long): Pair<Long, Int>? {
        val candidates = (0..1).flatMap { day ->
            Prefs.basalTimes.map { m ->
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, day)
                    set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis to m
            }
        }
        return candidates.filter { it.first - AHEAD_MS > now + 30_000L }.minByOrNull { it.first }
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, BasalReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun fire(context: Context) {
        val now = System.currentTimeMillis()
        val due = (Prefs.basalTimes.map { m ->
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } + Prefs.basalTimes.map { m ->
            Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }).filter { it >= now - 10 * 60_000L }.minOrNull() ?: return
        // already given (early, or someone entered it): nothing to remind
        // the process may have been started just for this: fall back to the saved day
        val history = context.liveRepository.live.value.history ?: LiveCache(context.filesDir).loadAny()?.history
        val entries = history?.treatments.orEmpty().withPending(context.liveRepository.pending.value)
        val given = entries.any {
            InsulinType.of(it.insulinType) == InsulinType.Basal && (it.insulin ?: 0.0) > 0 && it.t > due - 4 * 3_600_000L
        }
        if (given) return
        show(context, due)
    }

    internal fun show(context: Context, due: Long) {
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(Prefs.basalName?.let { context.getString(R.string.rem_title, it) } ?: context.getString(R.string.rem_title_plain))
            .setContentText(context.getString(R.string.rem_body, AlarmText.clock(context, due)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }
}

class BasalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BasalReminder.fire(context)
        BasalReminder.schedule(context) // the next one
    }
}
