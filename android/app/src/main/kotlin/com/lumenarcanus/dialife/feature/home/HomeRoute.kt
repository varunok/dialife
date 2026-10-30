package com.lumenarcanus.dialife.feature.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.feature.alarms.AlarmsSection
import com.lumenarcanus.dialife.feature.planned.PlannedSection
import com.lumenarcanus.dialife.feature.questions.QuestionsSection
import com.lumenarcanus.dialife.feature.questions.WatchSection
import com.lumenarcanus.dialife.feature.wait.WaitSection
import com.lumenarcanus.dialife.foodRepository
import com.lumenarcanus.dialife.insulinIntents
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.preferencesRepository
import com.lumenarcanus.dialife.questionAnswers

/**
 * Home with its data and its features; what only the activity can do (ask
 * for permissions, the service, the session) comes in as [setup] and callbacks.
 */
@Composable
fun HomeRoute(
    creds: Credentials,
    setup: BackgroundSetup,
    onAllowNotifications: () -> Unit,
    onAllowBackground: () -> Unit,
    onAllowFullScreen: () -> Unit,
    // the server no longer takes this phone's key
    onUnauthorized: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenData: (rangeHours: Int) -> Unit,
    onOpenWebLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMeal: (Treatment?, Question?) -> Unit,
    onOpenHandling: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val vm = viewModel { HomeViewModel(ctx.liveRepository, ctx.questionAnswers) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.unauthorized) { if (ui.unauthorized) onUnauthorized() }
    ui.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    HomeScreen(
        ui = ui,
        fallbackUnits = unitsOf(creds.displayUnits),
        deviceName = creds.deviceName,
        setup = setup,
        onAllowNotifications = onAllowNotifications,
        onAllowBackground = onAllowBackground,
        onAllowFullScreen = onAllowFullScreen,
        alarmsSection = { AlarmsSection(onOpenHandling = onOpenHandling) },
        onRange = vm::setRange,
        onOpenStats = onOpenStats,
        onOpenLog = onOpenLog,
        onOpenData = { onOpenData(ui.rangeHours) },
        onOpenWebLogin = onOpenWebLogin,
        onOpenMeal = onOpenMeal,
        plannedSection = { PlannedSection() },
        questionsSection = { onLog -> QuestionsSection(onLog) },
        watchSection = { WatchSection() },
        waitSection = { WaitSection() },
        onOpenSettings = onOpenSettings,
        onRetry = vm::retry,
        onSaveTreatment = vm::save,
        onDeleteTreatment = vm::delete,
        onAnswerQuestion = vm::answer,
        onDismissQuestion = vm::dismiss,
        onDisconnect = onDisconnect,
        onAnnounceInsulin = ctx.insulinIntents::announce,
        foods = ctx.foodRepository,
        onMeterLine = ctx.preferencesRepository::chooseMeterLine
    )
}
