package com.lumenarcanus.dialife.feature.meal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.FoodRepository
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.feature.entry.foodItemsOf
import com.lumenarcanus.dialife.feature.entry.newFoodRow
import com.lumenarcanus.dialife.feature.entry.parseMeter
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts
import com.lumenarcanus.dialife.live.TreatmentQueue
import kotlinx.coroutines.delay

/**
 * The meal: every dish its own line, the rapid dose worked out from the
 * family's settings, and nothing recorded as injected. A new meal is a draft
 * kept on the phone until it is saved or thrown away.
 *
 * @param editing an existing food entry to change, or null for a new meal
 * @param initialTime a question's "log it": when the rise began
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MealScreen(
    // food lookup and the carb tables; null in previews
    repo: FoodRepository?,
    inputs: MealInputs,
    editing: Treatment?,
    initialTime: Long?,
    // the food entry, a glucometer value typed here, the planned dose: saved together
    onSave: (MealSave) -> Unit,
    onClose: () -> Unit,
    // carbs given for a low: no insulin is worked out, no planned dose, no meal draft
    forLow: Boolean = false,
    // a fixed moment in the screenshot tests
    clock: () -> Long = System::currentTimeMillis,
    // the form waits on the server: no second save, no leaving half way
    saveStatus: MealSaveStatus = MealSaveStatus.Editing,
    onFailureSeen: () -> Unit = {}
) {
    val dose = inputs.dose
    val units = inputs.units
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val lang = locale.language
    val scope = rememberCoroutineScope()

    val keepsDraft = editing == null && !forLow
    val form = rememberMealFormState(editing, initialTime, keepsDraft, clock)

    // the draft follows every change
    if (keepsDraft) {
        LaunchedEffect(Unit) {
            snapshotFlow { form.toDraft() }.collect { if (it.isEmpty) MealDrafts.clear(ctx) else MealDrafts.save(ctx, it) }
        }
    }
    LaunchedEffect(form.removed) {
        if (form.removed != null) {
            delay(6000)
            form.removed = null
        }
    }

    val now = clock()
    val time = form.time(now)
    val current = form.current

    val typed = parseMeter(form.meterText, units)
    val d = mealDose(inputs, editing, time, now, form.join, current, typed)
    val meal = d.meal
    val joined = d.joined
    val earlier = d.earlier
    val openPlan = d.openPlan
    val meter = d.meter
    val m = d.calc
    val given = d.given
    val planOn = form.plan && m.canPlan
    val saving = saveStatus == MealSaveStatus.Saving

    fun finish(withPlan: Boolean) {
        form.asking = false
        val food = Treatment(
            id = editing?.id ?: form.newFoodId,
            t = time,
            carbs = current.toDouble(),
            notes = editing?.notes,
            foodItems = foodItemsOf(form.rows)
        )
        // a fresh glucometer value typed here is a reading of its own
        val reading = typed?.let { Treatment(id = TreatmentQueue.newId(), t = now, glucose = it) }
        val pd = if (forLow) null else plannedFor(d, food.id, now, withPlan) { form.newPlanId }
        onSave(MealSave(food, reading, pd))
    }
    fun save() {
        if (current <= 0 || saving) return
        if (forLow) return finish(false)
        when {
            planOn && !m.asks -> finish(true)
            !m.canPlan && !m.asks -> finish(false)
            else -> {
                form.okBig = false
                form.asking = true
            }
        }
    }

    // back closes and keeps the draft; not while the server is being asked
    BackHandler(onBack = { if (!saving) onClose() })

    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding().imePadding()) {
        MealTopBar(forLow, editing != null, keepsDraft, onClose = { if (!saving) onClose() }, onDiscard = {
            when {
                saving -> Unit // the server is being asked: nothing to discard yet
                form.rows.any { it.name.isNotBlank() } -> form.closing = true
                else -> {
                    MealDrafts.clear(ctx)
                    onClose()
                }
            }
        })

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (meal != null && editing == null && !forLow) JoinQuestion(meal, form.join) { form.join = it }

            DishesHeader(form, current)
            if (form.rows.isNotEmpty()) Text(stringResource(R.string.meal_swipe_hint), color = c.textFaint, fontSize = 11.5.sp)
            Dishes(form, repo, lang)
            form.removed?.let { (at, row) -> RemovedNote(row) { form.undoRemove(at, row) } }

            QuickFoods(repo, lang, onAdd = { name, portion ->
                form.place(newFoodRow(name = name, portion = portion))
            }, onAll = { form.picking = true })

            if (forLow) {
                Text(
                    stringResource(R.string.meal_low_note),
                    color = c.text,
                    fontSize = 13.5.sp,
                    modifier = Modifier.fillMaxWidth().background(c.inRangeContainer, RoundedCornerShape(14.dp)).padding(12.dp)
                )
            } else {
                DoseCard(
                    m, dose, units, locale, earlier, given, typedMeter = form.meterText, onMeter = {
                        form.meterText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5)
                    },
                    plan = planOn, onPlan = { form.plan = it }, openPlan = openPlan
                )
            }

            Text(stringResource(R.string.meal_when), color = c.textMuted, fontSize = 13.sp)
            WhenChips(form, isNew = editing == null, time)

            Text(stringResource(R.string.meal_not_advice), color = c.textFaint, fontSize = 11.5.sp)
        }

        SaveBar(form, current, if (planOn) m.toGive else null, saving, onSave = ::save)
    }
    MealDialogs(form, repo, lang, time, m, dose, units, locale, planOn, onDiscard = {
        form.closing = false
        MealDrafts.clear(ctx)
        onClose()
    }, onFinish = ::finish)
    if (saveStatus == MealSaveStatus.Failed) {
        AlertDialog(
            onDismissRequest = onFailureSeen,
            title = { Text(stringResource(R.string.meal_plan_failed)) },
            confirmButton = { TextButton(onClick = onFailureSeen) { Text(stringResource(R.string.tr_ok)) } }
        )
    }
}
