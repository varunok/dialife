package com.lumenarcanus.dialife.feature.alarms

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.ui.home.HomeViewModel

/** "You are handling it" with its data: the reading and chart as on home, the episode's actions as on the banner. */
@Composable
fun HandlingRoute(alarmId: Long, onBack: () -> Unit, onOpenMeal: () -> Unit) {
    val ctx = LocalContext.current
    val live = ctx.liveRepository
    val home = viewModel { HomeViewModel(live) }
    val alarms = viewModel { AlarmsViewModel(live, ctx.alarmController) }
    val ui by home.ui.collectAsStateWithLifecycle()
    val alarmsState by alarms.uiState.collectAsStateWithLifecycle()
    alarmsState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            alarms.userMessageShown()
        }
    }
    HandlingScreen(
        ui = ui,
        alarmId = alarmId,
        units = alarmsState.units,
        onAct = alarms::onAction,
        onSaveTreatment = live::saveTreatment,
        onBack = onBack,
        onOpenMeal = onOpenMeal
    )
}
