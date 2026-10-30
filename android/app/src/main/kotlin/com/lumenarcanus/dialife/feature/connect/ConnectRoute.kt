package com.lumenarcanus.dialife.feature.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.sessionRepository

/** Connecting with its data; the activity's view model, since links opened from outside connect through it too. */
@Composable
fun ConnectRoute(vm: ConnectViewModel = connectViewModel()) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    ConnectScreen(uiState, onScanned = vm::onLink, onScannerFailed = vm::onScannerFailed, onManual = vm::onManual)
}

@Composable
private fun connectViewModel(): ConnectViewModel {
    val ctx = LocalContext.current
    return viewModel { ConnectViewModel(ctx.sessionRepository) }
}
