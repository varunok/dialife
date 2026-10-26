package com.lumenarcanus.dialife.live

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.AlarmEvent
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.ui.alarm.AlarmActivity
import com.lumenarcanus.dialife.ui.home.unitsOf

/**
 * Turns episode events into what this phone does: ring, ask, inform or stay
 * quiet. The routing mirrors the server's ntfy messages: everyone is woken for
 * an alarm, the check-in goes to the handler only, a help request to everyone
 * but the handler, and "is on it" / "is coming" to the others.
 */
class AlarmController(context: Context, private val repo: LiveRepository, private val api: DiaLifeApi = DiaLifeApi()) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)
    val ringer = Ringer(app)

    // alarm id → elapsedRealtime when this phone last rang for it (a reconnect must not ring twice)
    private val rungAt = mutableMapOf<Long, Long>()

    private val me get() = repo.myId
    private val units get() = unitsOf(repo.live.value.state?.displayUnits ?: repo.credentials?.displayUnits)

    @Synchronized
    fun onSignal(s: AlarmSignal) {
        val e = s.event
        if (e == null) return reconcile()
        val a = e.alarm
        when (s.type) {
            "alarm" -> ring(a, e, help = false)
            "handled" -> {
                silence(a.id)
                if (a.handledById != me) info(a, app.getString(R.string.someone_handles, e.by ?: a.handledBy.orEmpty()), handledBody(a), loud = false)
            }
            "checkin" -> if (a.handledById == me) checkin(a)
            "checkin_ok" -> nm.cancel(TAG_CHECKIN, a.id.toInt()) // answered, maybe from the ntfy button
            "help" -> if (a.handledById != me) ring(a, e, help = true)
            "coming" -> {
                silence(a.id)
                if (a.comingById != me) {
                    info(a, app.getString(R.string.someone_coming, e.by ?: a.comingBy.orEmpty()), AlarmText.title(app, a, units), loud = a.handledById == me)
                }
            }
            "clear" -> {
                silence(a.id)
                nm.cancel(TAG_CHECKIN, a.id.toInt())
                val title = if (e.summary?.manual == true) app.getString(R.string.clear_manual, AlarmText.kind(app, a)) else AlarmText.clear(app, a)
                info(a, title, AlarmText.clearBody(app, e, units), loud = false)
                rungAt.remove(a.id)
            }
        }
    }

    /** After a (re)connect: catch up with what happened while we were away. */
    private fun reconcile() {
        val live = repo.live.value.state?.alarms.orEmpty()
        val now = SystemClock.elapsedRealtime()
        for (a in live) {
            val recentlyRung = rungAt[a.id]?.let { now - it < RERING_AFTER_MS } == true
            when {
                a.state == "firing" && !recentlyRung -> ring(a, null, help = false)
                a.state == "handled" && a.helpAt != null && a.comingAt == null && a.handledById != me && !recentlyRung ->
                    ring(a, null, help = true)
                a.state == "handled" && a.checkinPending && a.handledById == me -> checkin(a)
                a.state == "handled" && a.helpAt == null -> silence(a.id) // taken while we were away
            }
        }
        // episodes that ended while we were away
        val ids = live.map { it.id }.toSet()
        for (id in rungAt.keys.toList()) if (id !in ids) {
            silence(id)
            rungAt.remove(id)
        }
    }

    private fun ring(a: AlarmView, e: AlarmEvent?, help: Boolean) {
        rungAt[a.id] = SystemClock.elapsedRealtime()
        nm.cancel(TAG_INFO, a.id.toInt())
        val title = if (help) app.getString(R.string.help_title, a.handledBy.orEmpty()) else AlarmText.title(app, a, units)
        val body = if (help) AlarmText.title(app, a, units) else AlarmText.reason(app, e, a)
        val screen = PendingIntent.getActivity(
            app, a.id.toInt(),
            AlarmActivity.intent(app, a.id, help),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(app, Notifications.CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColor(if (help) 0xFFF5A524.toInt() else 0xFFFF3B5C.toInt())
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true) // over the lock screen, like an incoming call
        if (help) {
            b.addAction(action(a.id, ActionReceiver.COMING, R.string.act_coming))
        } else {
            b.addAction(action(a.id, ActionReceiver.HANDLE, R.string.act_handle))
            if (!a.urgentLow) b.addAction(action(a.id, ActionReceiver.QUIET, R.string.act_quiet))
        }
        nm.notify(TAG_ALARM, a.id.toInt(), b.build())
        ringer.start(a.id)
    }

    private fun checkin(a: AlarmView) {
        val n = Notification.Builder(app, Notifications.CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(app.getString(R.string.checkin_title))
            .setContentText(AlarmText.kind(app, a) + ": " + AlarmText.progress(app, a, units))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .addAction(action(a.id, ActionReceiver.OK, R.string.act_ok))
            .addAction(action(a.id, ActionReceiver.HELP, R.string.act_help))
            .build()
        nm.notify(TAG_CHECKIN, a.id.toInt(), n)
    }

    private fun info(a: AlarmView, title: String, body: String, loud: Boolean) {
        val n = Notification.Builder(app, if (loud) Notifications.CHANNEL_CHECKIN else Notifications.CHANNEL_EPISODES)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(TAG_INFO, a.id.toInt(), n)
    }

    private fun handledBody(a: AlarmView): String {
        val base = AlarmText.title(app, a, units)
        return a.quietUntil?.let { app.getString(R.string.quiet_until, base, AlarmText.clock(app, it)) } ?: base
    }

    /** Stop the sound and take the alarm off the screen. */
    fun silence(alarmId: Long) {
        ringer.stop(alarmId)
        nm.cancel(TAG_ALARM, alarmId.toInt())
        app.sendBroadcast(Intent(AlarmActivity.ACTION_CLOSE).setPackage(app.packageName).putExtra(AlarmActivity.EXTRA_ID, alarmId))
    }

    /** A button on a notification, the alarm screen or the home banner. */
    suspend fun act(action: String, alarmId: Long): Result<Unit> {
        val c = repo.credentials ?: return Result.failure(IllegalStateException("not connected"))
        val r = when (action) {
            ActionReceiver.HANDLE -> api.handle(c, alarmId)
            ActionReceiver.QUIET -> api.handle(c, alarmId, QUIET_MIN)
            ActionReceiver.OK -> api.checkin(c, alarmId, "ok")
            ActionReceiver.HELP -> api.checkin(c, alarmId, "help")
            ActionReceiver.COMING -> api.coming(c, alarmId)
            ActionReceiver.RESOLVE -> api.resolve(c, alarmId)
            else -> Result.failure(IllegalArgumentException(action))
        }
        r.onSuccess {
            // do not wait for the event to come back over the stream
            if (action == ActionReceiver.OK || action == ActionReceiver.HELP) nm.cancel(TAG_CHECKIN, alarmId.toInt()) else silence(alarmId)
        }
        return r
    }

    private fun action(alarmId: Long, what: String, label: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            app, (alarmId.toInt() * 8) + what.hashCode().and(7),
            Intent(app, ActionReceiver::class.java).putExtra(ActionReceiver.EXTRA_ACTION, what).putExtra(ActionReceiver.EXTRA_ID, alarmId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, app.getString(label), pi).build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app, 0,
        Intent(app, com.lumenarcanus.dialife.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun failed() {
        val n = Notification.Builder(app, Notifications.CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(app.getString(R.string.action_failed))
            .setContentText(app.getString(R.string.action_failed_body))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(Notifications.ID_ACTION_FAILED, n)
    }

    companion object {
        const val TAG_ALARM = "alarm"
        const val TAG_CHECKIN = "checkin"
        const val TAG_INFO = "info"
        const val QUIET_MIN = 120
        private const val RERING_AFTER_MS = 4 * 60_000L
    }
}
