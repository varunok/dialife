package com.lumenarcanus.dialife.feature.meal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.data.inLang
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.TreatmentQueue
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.ui.treatment.FoodLookup
import com.lumenarcanus.dialife.ui.treatment.FoodPicker
import com.lumenarcanus.dialife.ui.treatment.TimeDialog
import com.lumenarcanus.dialife.ui.treatment.atLabel
import com.lumenarcanus.dialife.ui.treatment.foodItemsOf
import com.lumenarcanus.dialife.ui.treatment.newFoodRow
import com.lumenarcanus.dialife.ui.treatment.parseMeter

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
    repo: LiveRepository?,
    inputs: MealInputs,
    editing: Treatment?,
    initialTime: Long?,
    // the food entry, a glucometer value typed here, the planned dose: saved together
    onSave: (MealSave) -> Unit,
    onClose: () -> Unit,
    // carbs given for a low: no insulin is worked out, no planned dose, no meal draft
    forLow: Boolean = false,
    // a fixed moment in the screenshot tests
    clock: () -> Long = System::currentTimeMillis
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
            kotlinx.coroutines.delay(6000)
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
    val given = if (joined) meal!!.given else 0.0
    val planOn = form.plan && m.canPlan

    fun finish(withPlan: Boolean) {
        form.asking = false
        val food = Treatment(
            id = editing?.id ?: TreatmentQueue.newId(),
            t = time,
            carbs = current.toDouble(),
            notes = editing?.notes,
            foodItems = foodItemsOf(form.rows)
        )
        // a fresh glucometer value typed here is a reading of its own
        val reading = typed?.let { Treatment(id = TreatmentQueue.newId(), t = now, glucose = it) }
        val pd = if (forLow) null else plannedFor(d, food.id, now, withPlan) { java.util.UUID.randomUUID().toString() }
        if (keepsDraft) MealDrafts.clear(ctx)
        onSave(MealSave(food, reading, pd))
    }
    fun save() {
        if (current <= 0) return
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

    // back closes and keeps the draft
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.meal_close)
            OutlinedButton(
                onClick = onClose,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription =
                        back
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("‹", fontSize = 22.sp, color = c.textMuted)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (forLow) {
                            R.string.meal_low_title
                        } else if (editing !=
                            null
                        ) {
                            R.string.meal_edit_title
                        } else {
                            R.string.meal_title
                        }
                    ),
                    color = c.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (keepsDraft) Text(stringResource(R.string.meal_draft_note), color = c.textFaint, fontSize = 11.5.sp)
            }
            if (keepsDraft) {
                TextButton(onClick = {
                    if (form.rows.any { it.name.isNotBlank() }) {
                        form.closing = true
                    } else {
                        MealDrafts.clear(ctx)
                        onClose()
                    }
                }) {
                    Text(stringResource(R.string.meal_discard), color = c.textMuted)
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (meal != null && editing == null && !forLow) {
                Column(
                    Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.meal_join_q, formatClock(ctx, meal.from), meal.carbs, formatAmount(meal.given, locale)),
                        color = c.text,
                        fontSize = 14.sp
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = form.join, onClick = {
                            form.join = true
                        }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                            Text(stringResource(R.string.meal_join_yes), fontSize = 13.sp)
                        }
                        SegmentedButton(selected = !form.join, onClick = {
                            form.join = false
                        }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                            Text(stringResource(R.string.meal_join_no), fontSize = 13.sp)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.meal_eaten),
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                val n = form.rows.count { it.name.isNotBlank() || it.grams != null }
                Text(
                    if (n ==
                        0
                    ) {
                        stringResource(R.string.meal_empty_short)
                    } else {
                        pluralStringResource(R.plurals.meal_dishes, n, n) + " · $current " +
                            stringResource(R.string.unit_carbs)
                    },
                    color = c.textMuted,
                    fontSize = 13.sp
                )
            }
            if (form.rows.isNotEmpty()) Text(stringResource(R.string.meal_swipe_hint), color = c.textFaint, fontSize = 11.5.sp)

            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp))
            ) {
                if (form.rows.isEmpty()) {
                    Text(
                        stringResource(R.string.meal_empty),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                form.rows.forEach { row ->
                    key(row.id) {
                        FoodLookup(row, repo) { f -> form.update(row.id, f) }
                        DishRow(
                            row,
                            lang,
                            open = form.open == row.id,
                            onToggle = { form.open = if (form.open == row.id) null else row.id },
                            onChange = { f -> form.update(row.id, f) },
                            onSplit = { parts ->
                                val i = form.rows.indexOfFirst { it.id == row.id }
                                val made = parts.map { newFoodRow(name = it.replaceFirstChar { ch -> ch.uppercase() }) }
                                form.rows.removeAt(i)
                                form.rows.addAll(i, made)
                                form.open = made.first().id
                            },
                            onRemove = { form.remove(row.id) },
                            onDone = { form.open = null }
                        )
                    }
                }
                val addFocus = remember { FocusRequester() }
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = form.newName,
                        onValueChange = { form.newName = it.replace('\n', ' ').take(80) },
                        placeholder = {
                            Text(
                                stringResource(if (form.rows.isEmpty()) R.string.meal_add_first else R.string.meal_add_more),
                                color = c.textFaint
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { form.addTyped() }),
                        modifier = Modifier.weight(1f).focusRequester(addFocus)
                    )
                    val add = stringResource(R.string.meal_add)
                    Button(
                        onClick = { form.addTyped() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.carbs, contentColor = c.background),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.size(52.dp).semantics { contentDescription = add }
                    ) { Text("+", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            form.removed?.let { (at, row) ->
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(14.dp)).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(
                            R.string.meal_removed,
                            row.name.ifBlank {
                                "—"
                            }
                        ),
                        color = c.text,
                        fontSize = 13.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        form.rows.add(at.coerceAtMost(form.rows.size), row)
                        form.removed = null
                    }) { Text(stringResource(R.string.meal_undo), color = c.carbs, fontWeight = FontWeight.SemiBold) }
                }
            }

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
                        form.meterText =
                            it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5)
                    },
                    plan = planOn, onPlan = { form.plan = it }, openPlan = openPlan
                )
            }

            Text(stringResource(R.string.meal_when), color = c.textMuted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing == null) {
                    TimeShifts.forEachIndexed { i, mins ->
                        FilterChip(selected = form.whenIdx == i, onClick = { form.whenIdx = i }, label = {
                            Text(if (mins == 0) stringResource(R.string.tr_now) else stringResource(R.string.tr_minutes_ago, mins))
                        })
                    }
                }
                FilterChip(selected = form.whenIdx == 3, onClick = { form.pickingTime = true }, label = {
                    Text(if (form.whenIdx == 3) atLabel(time) else stringResource(R.string.tr_other_time))
                })
            }
            Text(stringResource(R.string.meal_not_advice), color = c.textFaint, fontSize = 11.5.sp)
        }

        Column(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val empty = form.rows.count { it.grams == null && it.name.isNotBlank() }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (empty > 0) pluralStringResource(R.plurals.food_empty, empty, empty) else stringResource(R.string.food_total),
                    color = if (empty > 0) c.high else c.textMuted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Text("$current", style = GlucoseHero.copy(fontSize = 30.sp, lineHeight = 34.sp, color = c.carbs))
                Text(" " + stringResource(R.string.unit_carbs), color = c.textMuted, fontSize = 14.sp)
            }
            Button(
                onClick = { save() },
                enabled = current > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.carbs, contentColor = c.background)
            ) {
                Text(
                    stringResource(R.string.meal_save, current) +
                        if (planOn) " " + stringResource(R.string.meal_save_plan, formatAmount(m.toGive, locale)) else "",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (form.picking) {
        FoodPicker(
            tables = rememberCatalog(repo)?.tables.orEmpty(),
            lang = lang,
            onPick = { f, _ ->
                form.picking = false
                form.place(
                    newFoodRow(
                        name = f.names.inLang(lang),
                        carbs = f.carbs.toString(),
                        source = "table",
                        portion = f.portions.inLang(lang),
                        tableId = f.id
                    )
                )
                form.open = null
            },
            onDismiss = { form.picking = false }
        )
    }
    if (form.pickingTime) {
        TimeDialog(initial = time, onPick = {
            form.pickedAt = it
            form.whenIdx = 3
            form.pickingTime = false
        }, onDismiss = {
            form.pickingTime =
                false
        })
    }
    if (form.closing) {
        AlertDialog(
            onDismissRequest = { form.closing = false },
            title = { Text(stringResource(R.string.meal_discard_title)) },
            text = { Text(pluralStringResource(R.plurals.meal_discard_text, form.rows.size, form.rows.size)) },
            confirmButton = {
                TextButton(onClick = {
                    form.closing = false
                    MealDrafts.clear(ctx)
                    onClose()
                }) { Text(stringResource(R.string.meal_discard_yes), color = c.low) }
            },
            dismissButton = { TextButton(onClick = { form.closing = false }) { Text(stringResource(R.string.meal_discard_no)) } }
        )
    }
    if (form.asking) {
        SaveSheet(m, dose, units, locale, planOn, form.okBig, onOkBig = {
            form.okBig = it
        }, onPlan = { finish(true) }, onFoodOnly = { finish(false) }, onBack = {
            form.asking =
                false
        })
    }
}
