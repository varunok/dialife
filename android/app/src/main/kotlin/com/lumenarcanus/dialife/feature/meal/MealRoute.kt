package com.lumenarcanus.dialife.feature.meal

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.Treatment
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
    val vm = viewModel {
        MealViewModel(ctx.liveRepository, ctx.plannedDoses, ctx.questionAnswers) {
            Toast.makeText(ctx.applicationContext, R.string.meal_plan_failed, Toast.LENGTH_LONG).show()
        }
    }
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    MealScreen(
        repo = ctx.liveRepository,
        inputs = inputs,
        editing = editingId?.let { id -> inputs.treatments.firstOrNull { it.id == id } ?: entry?.takeIf { it.id == id } },
        initialTime = initialTime,
        onSave = { s ->
            vm.save(s, questionId)
            onDone()
        },
        onClose = onDone,
        forLow = forLow
    )
}
