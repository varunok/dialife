package com.lumenarcanus.dialife.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.widget.GlucoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The local half of "nobody is watching": the server raises an alarm when the
 * xDrip+ phone goes quiet, but only this phone can notice that it cannot
 * reach the server. AlarmManager wakes us even in Doze, where timers stall.
 */
object Watchdog {
    private const val EVERY_MS = 5 * 60_000L
    const val LOST_AFTER_MS = 10 * 60_000L

    fun schedule(context: Context) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + EVERY_MS,
            pending(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun check(context: Context) {
        val live = context.liveRepository.live.value
        val now = SystemClock.elapsedRealtime()
        when {
            live.link == Link.Live -> Notifications.clearConnectionLost(context)
            live.lastLiveAt == 0L -> context.liveRepository.reconnectNow() // never connected since start
            now - live.lastLiveAt >= LOST_AFTER_MS -> {
                Notifications.showConnectionLost(context, ((now - live.lastLiveAt) / 60_000).toInt())
                context.liveRepository.reconnectNow()
            }
            else -> context.liveRepository.reconnectNow()
        }
        schedule(context)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (CredentialStore(context).credentials.first() == null) return@launch
                LiveService.start(context) // no-op if running; revives it if Android killed the process
                Watchdog.check(context)
                GlucoseWidget.refresh(context) // so a stale reading shows as stale even when nothing arrives
            } finally {
                done.finish()
            }
        }
    }
}

/** After a reboot or an app update the stream has to come back by itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (CredentialStore(context).credentials.first() != null) LiveService.start(context)
            } finally {
                done.finish()
            }
        }
    }
}
