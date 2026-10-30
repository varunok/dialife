package com.lumenarcanus.dialife.feature.planned

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.plannedDoses

/** The planned doses on home, with their own state and actions. */
@Composable
fun PlannedSection() {
    val ctx = LocalContext.current
    val vm = viewModel { PlannedViewModel(ctx.liveRepository, ctx.plannedDoses) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    PlannedCards(uiState, vm::onAction)
}

/** Only draws: a card per dose not given yet. */
@Composable
fun PlannedCards(uiState: PlannedUiState, onAction: (PlannedAction) -> Unit) {
    uiState.cards.forEach { card ->
        key(card.dose.id) { PlannedCard(card, uiState.units, onAction) }
    }
}
