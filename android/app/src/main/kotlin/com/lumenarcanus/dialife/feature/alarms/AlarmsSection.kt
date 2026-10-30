package com.lumenarcanus.dialife.feature.alarms

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.alarmController
import com.lumenarcanus.dialife.live.ActionReceiver
import com.lumenarcanus.dialife.liveRepository

/** The live episodes on home; [onOpenHandling] goes to "you are handling it" (taking one goes there too). */
@Composable
fun AlarmsSection(onOpenHandling: (Long) -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel { AlarmsViewModel(ctx.liveRepository, ctx.alarmController) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    EpisodeBanners(uiState, onAction = { action, id ->
        vm.onAction(action, id)
        if (action == ActionReceiver.HANDLE) onOpenHandling(id) // took it: straight to "you are handling"
    }, onOpen = onOpenHandling)
}

/** Only draws: a banner per live episode. */
@Composable
fun EpisodeBanners(uiState: AlarmsUiState, onAction: (String, Long) -> Unit, onOpen: (Long) -> Unit) {
    uiState.episodes.forEach { a -> key(a.id) { EpisodeBanner(a, uiState, onAction, onOpen) } }
}
