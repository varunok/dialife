package com.lumenarcanus.dialife.live

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.questionNotifier
import com.lumenarcanus.dialife.ui.home.unitsOf
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.widget.GlucoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the stream open while the app is closed: without it Android would
 * freeze the process and the parents' phones would go quiet.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var units = "mg/dl"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // startForeground must come within seconds of startForegroundService, before any I/O
        startForeground(
            Notifications.ID_LIVE,
            Notifications.live(this, liveRepository.live.value, units),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            liveRepository.live
                .map { Triple(it.state, it.link, it.unauthorized) }
                .distinctUntilChanged()
                .collect { (_, link, unauthorized) ->
                    if (unauthorized) {
                        stopSelf()
                        return@collect
                    }
                    nm.notify(Notifications.ID_LIVE, Notifications.live(this@LiveService, liveRepository.live.value, units))
                    GlucoseWidget.refresh(this@LiveService)
                    if (link == Link.Live) Notifications.clearConnectionLost(this@LiveService)
                }
        }
        scope.launch { liveRepository.alarms.collect(alarmController::onSignal) }
        // "did you forget to log?": the snapshot after each (re)connect, then every change and reminder
        scope.launch {
            liveRepository.live.map { it.state?.questions }.distinctUntilChanged().collect { qs ->
                val st = liveRepository.live.value.state
                if (qs != null) questionNotifier.sync(qs, liveRepository.myId, unitsOf(st?.displayUnits ?: units))
            }
        }
        scope.launch {
            liveRepository.questions.collect { q ->
                questionNotifier.onEvent(q, liveRepository.myId, unitsOf(liveRepository.live.value.state?.displayUnits ?: units))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val creds = CredentialStore(this@LiveService).credentials.first()
            if (creds == null) {
                stopSelf()
                return@launch
            }
            units = creds.displayUnits
            // re-post too: one posted before notifications were allowed was dropped
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_LIVE, Notifications.live(this@LiveService, liveRepository.live.value, units))
            liveRepository.start(creds)
            Watchdog.schedule(this@LiveService)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LiveService::class.java))
            } catch (e: Exception) { // e.g. not allowed from the background right now
                Log.w("DiaLifeLive", "cannot start the service", e)
            }
        }

        /** Disconnected or revoked: close the stream, forget the data, stop watching. */
        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
            context.liveRepository.stop(forget = true)
            Watchdog.cancel(context)
            Notifications.clearConnectionLost(context)
        }
    }
}
