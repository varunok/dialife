package com.lumenarcanus.dialife.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.doseSettings
import com.lumenarcanus.dialife.liveRepository

/** The dose settings with their data. */
@Composable
fun DoseSettingsRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val vm = viewModel { DoseSettingsViewModel(ctx.doseSettings, ctx.liveRepository, locale) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    DoseSettingsScreen(uiState, onEdit = vm::edit, onSave = vm::save, onBack = onBack)
}
