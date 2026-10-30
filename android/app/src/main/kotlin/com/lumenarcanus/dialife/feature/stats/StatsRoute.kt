package com.lumenarcanus.dialife.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.liveRepository

/** Statistics with their data. */
@Composable
fun StatsRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel { StatsViewModel(ctx.liveRepository) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    StatsScreen(uiState, onPick = vm::pick, onRetry = vm::retry, onBack = onBack)
}
