package com.lumenarcanus.dialife.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.feature.home.BackgroundSetup
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.preferencesRepository
import com.lumenarcanus.dialife.sessionRepository

/** Settings with their data; asking the system for permissions stays with the activity. */
@Composable
fun SettingsRoute(
    creds: Credentials,
    setup: BackgroundSetup,
    onFixNotifications: () -> Unit,
    onFixBattery: () -> Unit,
    onFixFullScreen: () -> Unit,
    onDisconnected: () -> Unit,
    onOpenDose: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val vm = viewModel {
        SettingsViewModel(creds, ctx.liveRepository, ctx.preferencesRepository, ctx.sessionRepository, ctx.alarmController.ringer)
    }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState,
        setup = setup,
        onAction = vm::onAction,
        onFixNotifications = onFixNotifications,
        onFixBattery = onFixBattery,
        onFixFullScreen = onFixFullScreen,
        onDisconnect = {
            vm.disconnect()
            onDisconnected()
        },
        onOpenDose = onOpenDose,
        onBack = onBack
    )
}
