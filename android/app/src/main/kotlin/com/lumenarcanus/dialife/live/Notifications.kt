package com.lumenarcanus.dialife.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.MainActivity
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.ui.home.Trend
import com.lumenarcanus.dialife.ui.home.formatDelta
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.home.unitsOf
import com.lumenarcanus.dialife.ui.home.Units

object Notifications {
    const val CHANNEL_LIVE = "live"
    const val CHANNEL_CONNECTION = "connection"
    const val CHANNEL_ALARMS = "alarms"
    const val CHANNEL_CHECKIN = "checkin"
    const val CHANNEL_EPISODES = "episodes"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_QUESTIONS = "questions"
    const val CHANNEL_QUESTIONS_NIGHT = "questions_night"
    const val ID_LIVE = 1
    const val ID_CONNECTION = 2
    const val ID_ACTION_FAILED = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, context.getString(R.string.channel_live), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_live_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_connection_desc)
            },
        )
        nm.createNotificationChannel(
            // silent channel: the Ringer plays the sound on the alarm stream, which DND lets through
            NotificationChannel(CHANNEL_ALARMS, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alarms_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHECKIN, context.getString(R.string.channel_checkin), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_checkin_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_reminders_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUESTIONS, context.getString(R.string.channel_questions), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_questions_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUESTIONS_NIGHT, context.getString(R.string.channel_questions_night), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_questions_night_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EPISODES, context.getString(R.string.channel_episodes), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_episodes_desc)
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The quiet ongoing notification: the current number, like a watch face in the shade. */
    fun live(context: Context, live: Live, fallbackUnits: String): Notification {
        val st = live.state
        val bg = st?.bg
        val b = Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // the number is what we want to see on the lock screen
            .setContentIntent(openApp(context))
        if (bg == null) {
            return b.setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(if (live.link == Link.Offline) R.string.status_offline else R.string.home_loading))
                .build()
        }
        val units = unitsOf(st.displayUnits ?: fallbackUnits)
        val locale = context.resources.configuration.locales[0]
        val arrow = arrowOf(bg.direction)
        val unitLabel = context.getString(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
        val title = "${formatGlucose(bg.sgv, units, locale)} $unitLabel $arrow".trim()
        val parts = buildList {
            bg.delta?.let { add(context.getString(R.string.delta_per_5, formatDelta(it, units, locale))) }
            if (live.link == Link.Offline) add(context.getString(R.string.status_offline))
        }
        val color = when {
            bg.sgv < st.thresholds.low -> 0xFFFF5C7A.toInt()
            bg.sgv > st.thresholds.high -> 0xFFF5A524.toInt()
            else -> 0xFF4FD1C5.toInt()
        }
        return b.setContentTitle(title)
            .setContentText(parts.joinToString(" · "))
            .setWhen(bg.mills) // the shade shows "2 min" itself and keeps it current
            .setShowWhen(true)
            .setColor(color)
            .build()
    }

    fun showConnectionLost(context: Context, minutes: Int) {
        val n = Notification.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(context.getString(R.string.conn_lost_title))
            .setContentText(context.resources.getQuantityString(R.plurals.conn_lost_body, minutes, minutes))
            .setStyle(Notification.BigTextStyle().bigText(context.resources.getQuantityString(R.plurals.conn_lost_body, minutes, minutes)))
            .setCategory(Notification.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_CONNECTION, n)
    }

    fun clearConnectionLost(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_CONNECTION)
    }

    /** The trend as one glyph, for places that cannot draw (notification, widget). */
    fun arrowOf(direction: String?): String = when (Trend.entries.firstOrNull { it.name == direction }) {
        Trend.DoubleUp -> "⇈"
        Trend.SingleUp -> "↑"
        Trend.FortyFiveUp -> "↗"
        Trend.Flat -> "→"
        Trend.FortyFiveDown -> "↘"
        Trend.SingleDown -> "↓"
        Trend.DoubleDown -> "⇊"
        else -> ""
    }
}
