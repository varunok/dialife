package com.lumenarcanus.dialife.feature.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.foodRepository
import com.lumenarcanus.dialife.insulinIntents
import com.lumenarcanus.dialife.liveRepository

/** The data table with its data; [spanHours] is the chart's range when opened, the window it pages by. */
@Composable
fun DataRoute(spanHours: Int, onBack: () -> Unit, onOpenMeal: (Treatment) -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel {
        val live = ctx.liveRepository
        DataViewModel(live, DataRepository({ live.credentials }), spanHours, createSavedStateHandle())
    }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    DataScreen(
        uiState,
        onAction = vm::onAction,
        onSaveTreatment = vm::save,
        onBack = onBack,
        onOpenMeal = onOpenMeal,
        onAnnounceInsulin = ctx.insulinIntents::announce,
        foods = ctx.foodRepository
    )
}
