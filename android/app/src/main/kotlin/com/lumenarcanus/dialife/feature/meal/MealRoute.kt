package com.lumenarcanus.dialife.feature.meal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.foodRepository
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.plannedDoses
import com.lumenarcanus.dialife.questionAnswers

/**
 * The food form with its data: a new meal, an entry to edit ([editingId], or
 * [entry] when it is older than the loaded history), carbs for a low, or the
 * food a "did you forget to log?" question asks for.
 */
@Composable
fun MealRoute(editingId: String?, entry: Treatment?, initialTime: Long?, forLow: Boolean, questionId: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel { MealViewModel(ctx.liveRepository, ctx.plannedDoses, ctx.questionAnswers) }
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val editing = editingId?.let { id -> inputs.treatments.firstOrNull { it.id == id } ?: entry?.takeIf { it.id == id } }
    // the form closes only once all of it is saved; the draft goes with it
    LaunchedEffect(status) {
        if (status == MealSaveStatus.Saved) {
            if (editing == null && !forLow) MealDrafts.clear(ctx)
            onDone()
        }
    }
    MealScreen(
        repo = ctx.foodRepository,
        inputs = inputs,
        editing = editing,
        initialTime = initialTime,
        onSave = { s -> vm.save(s, questionId) },
        onClose = onDone,
        forLow = forLow,
        saveStatus = status,
        onFailureSeen = vm::failureSeen
    )
}
