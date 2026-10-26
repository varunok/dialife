package com.lumenarcanus.dialife

import android.app.Application
import kotlinx.coroutines.launch
import com.lumenarcanus.dialife.live.AlarmController
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.Notifications

class DiaLifeApp : Application() {
    /** One connection per process: the screen and the foreground service share it. */
    val live by lazy { LiveRepository(this) }
    val alarms by lazy { AlarmController(this, live) }
    val questions by lazy { com.lumenarcanus.dialife.live.QuestionNotifier(this) }

    override fun onCreate() {
        super.onCreate()
        com.lumenarcanus.dialife.data.Prefs.init(this)
        Notifications.createChannels(this)
        com.lumenarcanus.dialife.live.BasalReminder.schedule(this) // alarms do not survive a reboot or an update
        kotlinx.coroutines.MainScope().launch { com.lumenarcanus.dialife.widget.GlucoseWidget.publishPreviews(this@DiaLifeApp) }
    }
}

val android.content.Context.liveRepository: LiveRepository
    get() = (applicationContext as DiaLifeApp).live

val android.content.Context.alarmController: AlarmController
    get() = (applicationContext as DiaLifeApp).alarms

val android.content.Context.questionNotifier: com.lumenarcanus.dialife.live.QuestionNotifier
    get() = (applicationContext as DiaLifeApp).questions
