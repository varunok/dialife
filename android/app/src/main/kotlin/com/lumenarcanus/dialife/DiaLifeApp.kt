package com.lumenarcanus.dialife

import android.app.Application
import android.content.Context
import com.lumenarcanus.dialife.core.data.DefaultDoseSettingsRepository
import com.lumenarcanus.dialife.core.data.DefaultFoodRepository
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
import com.lumenarcanus.dialife.core.info.DefaultMethodsRepository
import com.lumenarcanus.dialife.core.info.MethodsRepository
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.feature.alarms.AlarmController
import com.lumenarcanus.dialife.feature.questions.QuestionNotifier
import com.lumenarcanus.dialife.live.BasalReminder
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.Notifications
import com.lumenarcanus.dialife.widget.GlucoseWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class DiaLifeApp : Application() {
    /** One connection per process: the screen and the foreground service share it. */
    val live by lazy { LiveRepository(this) }
    val sessions: SessionRepository by lazy { DefaultSessionRepository(this) }
    val preferences: PreferencesRepository by lazy { DefaultPreferencesRepository(this) }
    val alarms by lazy { AlarmController(this, live) }
    val questions by lazy { QuestionNotifier(this) }
    val plannedDoses: PlannedDoseRepository by lazy { DefaultPlannedDoseRepository({ live.credentials }) }
    val questionAnswers: QuestionRepository by lazy { DefaultQuestionRepository(live, questions) }
    val foods: FoodRepository by lazy { DefaultFoodRepository({ live.credentials }) }
    val insulinIntents by lazy { InsulinIntentRepository({ live.credentials }, MainScope()) }
    val doseSettings: DoseSettingsRepository by lazy { DefaultDoseSettingsRepository({ live.credentials }) }
    val methods: MethodsRepository by lazy { DefaultMethodsRepository({ live.credentials }) }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifications.createChannels(this)
        BasalReminder.schedule(this) // alarms do not survive a reboot or an update
        MainScope().launch { GlucoseWidget.publishPreviews(this@DiaLifeApp) }
    }
}

val Context.liveRepository: LiveRepository
    get() = (applicationContext as DiaLifeApp).live

val Context.sessionRepository: SessionRepository
    get() = (applicationContext as DiaLifeApp).sessions

val Context.preferencesRepository: PreferencesRepository
    get() = (applicationContext as DiaLifeApp).preferences

val Context.plannedDoses: PlannedDoseRepository
    get() = (applicationContext as DiaLifeApp).plannedDoses

val Context.questionAnswers: QuestionRepository
    get() = (applicationContext as DiaLifeApp).questionAnswers

val Context.foodRepository: FoodRepository
    get() = (applicationContext as DiaLifeApp).foods

val Context.insulinIntents: InsulinIntentRepository
    get() = (applicationContext as DiaLifeApp).insulinIntents

val Context.doseSettings: DoseSettingsRepository
    get() = (applicationContext as DiaLifeApp).doseSettings

val Context.alarmController: AlarmController
    get() = (applicationContext as DiaLifeApp).alarms

val Context.questionNotifier: QuestionNotifier
    get() = (applicationContext as DiaLifeApp).questions

val Context.methodsRepository: MethodsRepository
    get() = (applicationContext as DiaLifeApp).methods
