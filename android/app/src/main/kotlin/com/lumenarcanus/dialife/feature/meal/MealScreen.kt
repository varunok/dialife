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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import com.lumenarcanus.dialife.ui.treatment.FoodRow
import com.lumenarcanus.dialife.ui.treatment.TimeDialog
import com.lumenarcanus.dialife.ui.treatment.atLabel
import com.lumenarcanus.dialife.ui.treatment.foodItemsOf
import com.lumenarcanus.dialife.ui.treatment.foodRowsOf
import com.lumenarcanus.dialife.ui.treatment.newFoodRow
import com.lumenarcanus.dialife.ui.treatment.parseMeter

private val TimeShifts = listOf(0, 15, 30) // minutes ago; the last chip picks a time

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
    val draft = remember { if (keepsDraft) MealDrafts.load(ctx) else null }
    val rows = remember {
        mutableStateListOf<FoodRow>().apply {
            when {
                draft != null -> addAll(draft.rows.map { newFoodRow(it.name, it.carbs, it.source, it.portion, it.tableId) })
                editing != null -> addAll(foodRowsOf(editing.foodItems, editing.carbs))
            }
        }
    }
    val startedAt = remember { draft?.startedAt?.takeIf { it > 0 } ?: clock() }
    var whenIdx by remember { mutableStateOf(draft?.whenIdx ?: if (editing != null || initialTime != null) 3 else 0) }
    var pickedAt by remember { mutableStateOf(draft?.pickedAt ?: editing?.t ?: initialTime) }
    var plan by remember { mutableStateOf(draft?.plan ?: false) }
    var join by remember { mutableStateOf(draft?.join ?: true) }
    var meterText by remember { mutableStateOf(draft?.meterText.orEmpty()) }
    var newName by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Long?>(null) }
    var removed by remember { mutableStateOf<Pair<Int, FoodRow>?>(null) }
    var closing by remember { mutableStateOf(false) } // "discard?" asked
    var asking by remember { mutableStateOf(false) } // the save dialog
    var okBig by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    // the draft follows every change
    if (keepsDraft) {
        LaunchedEffect(Unit) {
            snapshotFlow {
                MealDraft(
                    rows.map {
                        MealDraft.Row(it.name, it.portion, it.carbs, it.source, it.tableId)
                    },
                    whenIdx,
                    pickedAt,
                    plan,
                    join,
                    meterText,
                    startedAt
                )
            }.collect { if (it.isEmpty) MealDrafts.clear(ctx) else MealDrafts.save(ctx, it) }
        }
    }
    LaunchedEffect(removed) {
        if (removed != null) {
            kotlinx.coroutines.delay(6000)
            removed = null
        }
    }

    val now = clock()
    val time = if (whenIdx == 3) pickedAt ?: now else now - TimeShifts[whenIdx] * 60_000L
    val current = rows.sumOf { it.grams ?: 0 }

    val typed = parseMeter(meterText, units)
    val d = mealDose(inputs, editing, time, now, join, current, typed)
    val meal = d.meal
    val joined = d.joined
    val earlier = d.earlier
    val openPlan = d.openPlan
    val meter = d.meter
    val m = d.calc
    val given = if (joined) meal!!.given else 0.0
    val planOn = plan && m.canPlan

    fun place(row: FoodRow) {
        val last = rows.lastOrNull()
        if (last != null && last.name.isBlank() && last.carbs.isBlank()) rows[rows.lastIndex] = row else rows.add(row)
        open = row.id
    }
    fun update(id: Long, f: (FoodRow) -> FoodRow) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = f(rows[i])
    }
    fun remove(id: Long) {
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0) return
        removed = i to rows.removeAt(i)
        if (open == id) open = null
    }
    fun addTyped() {
        val (name, portion) = parseEntry(newName)
        if (name.isBlank()) return
        place(newFoodRow(name = name.replaceFirstChar { it.uppercase() }, portion = portion))
        newName = ""
    }
    fun finish(withPlan: Boolean) {
        asking = false
        val food = Treatment(
            id = editing?.id ?: TreatmentQueue.newId(),
            t = time,
            carbs = current.toDouble(),
            notes = editing?.notes,
            foodItems = foodItemsOf(rows)
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
                okBig = false
                asking = true
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
                    if (rows.any { it.name.isNotBlank() }) {
                        closing = true
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
                        SegmentedButton(selected = join, onClick = {
                            join = true
                        }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                            Text(stringResource(R.string.meal_join_yes), fontSize = 13.sp)
                        }
                        SegmentedButton(selected = !join, onClick = {
                            join = false
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
                val n = rows.count { it.name.isNotBlank() || it.grams != null }
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
            if (rows.isNotEmpty()) Text(stringResource(R.string.meal_swipe_hint), color = c.textFaint, fontSize = 11.5.sp)

            Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp))
            ) {
                if (rows.isEmpty()) {
                    Text(
                        stringResource(R.string.meal_empty),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                rows.forEach { row ->
                    key(row.id) {
                        FoodLookup(row, repo) { f -> update(row.id, f) }
                        DishRow(
                            row,
                            lang,
                            open = open == row.id,
                            onToggle = { open = if (open == row.id) null else row.id },
                            onChange = { f -> update(row.id, f) },
                            onSplit = { parts ->
                                val i = rows.indexOfFirst { it.id == row.id }
                                val made = parts.map { newFoodRow(name = it.replaceFirstChar { ch -> ch.uppercase() }) }
                                rows.removeAt(i)
                                rows.addAll(i, made)
                                open = made.first().id
                            },
                            onRemove = { remove(row.id) },
                            onDone = { open = null }
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
                        value = newName,
                        onValueChange = { newName = it.replace('\n', ' ').take(80) },
                        placeholder = {
                            Text(
                                stringResource(if (rows.isEmpty()) R.string.meal_add_first else R.string.meal_add_more),
                                color = c.textFaint
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTyped() }),
                        modifier = Modifier.weight(1f).focusRequester(addFocus)
                    )
                    val add = stringResource(R.string.meal_add)
                    Button(
                        onClick = { addTyped() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.carbs, contentColor = c.background),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.size(52.dp).semantics { contentDescription = add }
                    ) { Text("+", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            removed?.let { (at, row) ->
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
                        rows.add(at.coerceAtMost(rows.size), row)
                        removed = null
                    }) { Text(stringResource(R.string.meal_undo), color = c.carbs, fontWeight = FontWeight.SemiBold) }
                }
            }

            QuickFoods(repo, lang, onAdd = { name, portion ->
                place(newFoodRow(name = name, portion = portion))
            }, onAll = { picking = true })

            if (forLow) {
                Text(
                    stringResource(R.string.meal_low_note),
                    color = c.text,
                    fontSize = 13.5.sp,
                    modifier = Modifier.fillMaxWidth().background(c.inRangeContainer, RoundedCornerShape(14.dp)).padding(12.dp)
                )
            } else {
                DoseCard(
                    m, dose, units, locale, earlier, given, typedMeter = meterText, onMeter = {
                        meterText =
                            it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5)
                    },
                    plan = planOn, onPlan = { plan = it }, openPlan = openPlan
                )
            }

            Text(stringResource(R.string.meal_when), color = c.textMuted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing == null) {
                    TimeShifts.forEachIndexed { i, mins ->
                        FilterChip(selected = whenIdx == i, onClick = { whenIdx = i }, label = {
                            Text(if (mins == 0) stringResource(R.string.tr_now) else stringResource(R.string.tr_minutes_ago, mins))
                        })
                    }
                }
                FilterChip(selected = whenIdx == 3, onClick = { pickingTime = true }, label = {
                    Text(if (whenIdx == 3) atLabel(time) else stringResource(R.string.tr_other_time))
                })
            }
            Text(stringResource(R.string.meal_not_advice), color = c.textFaint, fontSize = 11.5.sp)
        }

        Column(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val empty = rows.count { it.grams == null && it.name.isNotBlank() }
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

    if (picking) {
        FoodPicker(
            tables = rememberCatalog(repo)?.tables.orEmpty(),
            lang = lang,
            onPick = { f, _ ->
                picking = false
                place(
                    newFoodRow(
                        name = f.names.inLang(lang),
                        carbs = f.carbs.toString(),
                        source = "table",
                        portion = f.portions.inLang(lang),
                        tableId = f.id
                    )
                )
                open = null
            },
            onDismiss = { picking = false }
        )
    }
    if (pickingTime) {
        TimeDialog(initial = time, onPick = {
            pickedAt = it
            whenIdx = 3
            pickingTime = false
        }, onDismiss = {
            pickingTime =
                false
        })
    }
    if (closing) {
        AlertDialog(
            onDismissRequest = { closing = false },
            title = { Text(stringResource(R.string.meal_discard_title)) },
            text = { Text(pluralStringResource(R.plurals.meal_discard_text, rows.size, rows.size)) },
            confirmButton = {
                TextButton(onClick = {
                    closing = false
                    MealDrafts.clear(ctx)
                    onClose()
                }) { Text(stringResource(R.string.meal_discard_yes), color = c.low) }
            },
            dismissButton = { TextButton(onClick = { closing = false }) { Text(stringResource(R.string.meal_discard_no)) } }
        )
    }
    if (asking) {
        SaveSheet(m, dose, units, locale, planOn, okBig, onOkBig = {
            okBig = it
        }, onPlan = { finish(true) }, onFoodOnly = { finish(false) }, onBack = {
            asking =
                false
        })
    }
}
