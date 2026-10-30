package com.lumenarcanus.dialife

import android.app.Application
import com.lumenarcanus.dialife.core.data.DefaultDoseSettingsRepository
import com.lumenarcanus.dialife.core.data.DefaultPlannedDoseRepository
import com.lumenarcanus.dialife.core.data.DefaultPreferencesRepository
import com.lumenarcanus.dialife.core.data.DefaultQuestionRepository
import com.lumenarcanus.dialife.core.data.DefaultSessionRepository
import com.lumenarcanus.dialife.core.data.DoseSettingsRepository
import com.lumenarcanus.dialife.core.data.FoodRepository
import com.lumenarcanus.dialife.core.data.InsulinIntentRepository
import com.lumenarcanus.dialife.core.data.PlannedDoseRepository
import com.lumenarcanus.dialife.core.data.PreferencesRepository
import com.lumenarcanus.dialife.core.data.QuestionRepository
import com.lumenarcanus.dialife.core.data.SessionRepository
import com.lumenarcanus.dialife.feature.alarms.AlarmController
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.Notifications
import kotlinx.coroutines.launch

class DiaLifeApp : Application() {
    /** One connection per process: the screen and the foreground service share it. */
    val live by lazy { LiveRepository(this) }
    val sessions: SessionRepository by lazy { DefaultSessionRepository(this) }
    val preferences: PreferencesRepository by lazy { DefaultPreferencesRepository(this) }
    val alarms by lazy { AlarmController(this, live) }
    val questions by lazy { com.lumenarcanus.dialife.feature.questions.QuestionNotifier(this) }
    val plannedDoses: PlannedDoseRepository by lazy { DefaultPlannedDoseRepository({ live.credentials }) }
    val questionAnswers: QuestionRepository by lazy { DefaultQuestionRepository(live, questions) }
    val foods by lazy { FoodRepository({ live.credentials }) }
    val insulinIntents by lazy { InsulinIntentRepository({ live.credentials }, kotlinx.coroutines.MainScope()) }
    val doseSettings: DoseSettingsRepository by lazy { DefaultDoseSettingsRepository({ live.credentials }) }

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

val android.content.Context.sessionRepository: SessionRepository
    get() = (applicationContext as DiaLifeApp).sessions

val android.content.Context.preferencesRepository: PreferencesRepository
    get() = (applicationContext as DiaLifeApp).preferences

val android.content.Context.plannedDoses: PlannedDoseRepository
    get() = (applicationContext as DiaLifeApp).plannedDoses

val android.content.Context.questionAnswers: QuestionRepository
    get() = (applicationContext as DiaLifeApp).questionAnswers

val android.content.Context.foodRepository: FoodRepository
    get() = (applicationContext as DiaLifeApp).foods

val android.content.Context.insulinIntents: InsulinIntentRepository
    get() = (applicationContext as DiaLifeApp).insulinIntents

val android.content.Context.doseSettings: DoseSettingsRepository
    get() = (applicationContext as DiaLifeApp).doseSettings

val android.content.Context.alarmController: AlarmController
    get() = (applicationContext as DiaLifeApp).alarms

val android.content.Context.questionNotifier: com.lumenarcanus.dialife.feature.questions.QuestionNotifier
    get() = (applicationContext as DiaLifeApp).questions
