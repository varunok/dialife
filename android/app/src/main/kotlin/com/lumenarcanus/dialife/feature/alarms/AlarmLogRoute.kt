package com.lumenarcanus.dialife.feature.alarms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.liveRepository

/** The alarm log with its data. */
@Composable
fun AlarmLogRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel {
        val live = ctx.liveRepository
        AlarmLogViewModel(live, DefaultAlarmLogRepository({ live.credentials }))
    }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    AlarmLogScreen(uiState, onPick = vm::pick, onRetry = vm::retry, onBack = onBack)
}
