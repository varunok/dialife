package com.lumenarcanus.dialife.ui.meal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.FoodCatalog
import com.lumenarcanus.dialife.data.MealDraft
import com.lumenarcanus.dialife.data.MealDrafts
import com.lumenarcanus.dialife.data.Meals
import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.data.inLang
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.TreatmentQueue
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.ui.treatment.FoodLookup
import com.lumenarcanus.dialife.ui.treatment.FoodPicker
import com.lumenarcanus.dialife.ui.treatment.FoodRow
import com.lumenarcanus.dialife.ui.treatment.FoodSuggestions
import com.lumenarcanus.dialife.ui.treatment.TimeDialog
import com.lumenarcanus.dialife.ui.treatment.atLabel
import com.lumenarcanus.dialife.ui.treatment.foodItemsOf
import com.lumenarcanus.dialife.ui.treatment.foodRowsOf
import com.lumenarcanus.dialife.ui.treatment.formatAmount
import com.lumenarcanus.dialife.ui.treatment.newFoodRow
import com.lumenarcanus.dialife.ui.treatment.parseMeter
import kotlinx.coroutines.launch

private val TimeShifts = listOf(0, 15, 30) // minutes ago; the last chip picks a time

/** "борщ з хлібом" → ["борщ", "хлібом"]: a hint to split, never done by itself. */
fun splitParts(name: String): List<String>? =
    name.split(Regex("\\s*(?:,|\\+|\\sз\\s|\\sі\\s|\\sта\\s|\\sй\\s|\\sy\\s|\\scon\\s|\\swith\\s|\\sand\\s)\\s*", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size > 1 }

/** "гречка 150 г" → "гречка", "150 г": the portion may come with the name. */
fun parseEntry(text: String): Pair<String, String> {
    val m = Regex("^(.*?)[\\s,]+(\\d.*)$").find(text.trim()) ?: return text.trim() to ""
    return m.groupValues[1] to m.groupValues[2]
}

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
    repo: LiveRepository?,
    treatments: List<Treatment>, // the loaded history, pending entries included
    dose: DoseSettings,
    planned: List<PlannedDose>,
    units: Units,
    editing: Treatment?,
    initialTime: Long?,
    onSave: (Treatment) -> Unit,
    onSaved: (food: Treatment) -> Unit,
    onClose: () -> Unit,
    forLow: Boolean = false, // carbs given for a low: no insulin is worked out, no planned dose, no meal draft
) {
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
    val startedAt = remember { draft?.startedAt?.takeIf { it > 0 } ?: System.currentTimeMillis() }
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
    var failed by remember { mutableStateOf(false) }

    // the draft follows every change
    if (keepsDraft) LaunchedEffect(Unit) {
        snapshotFlow {
            MealDraft(rows.map { MealDraft.Row(it.name, it.portion, it.carbs, it.source, it.tableId) }, whenIdx, pickedAt, plan, join, meterText, startedAt)
        }.collect { if (it.isEmpty) MealDrafts.clear(ctx) else MealDrafts.save(ctx, it) }
    }
    LaunchedEffect(removed) { if (removed != null) { kotlinx.coroutines.delay(6000); removed = null } }

    val now = System.currentTimeMillis()
    val time = if (whenIdx == 3) pickedAt ?: now else now - TimeShifts[whenIdx] * 60_000L
    val current = rows.sumOf { it.grams ?: 0 }

    // the meal this belongs to: the one being edited, or the one it joins
    val others = treatments.filter { it.id != editing?.id }
    val gap = Meals.gapMin(treatments)
    val meal = remember(treatments, editing, time, gap) {
        if (editing != null) Meals.group(treatments, gap).firstOrNull { m -> m.foods.any { it.id == editing.id } }
        else Meals.joining(Meals.group(others, gap), time, gap)
    }
    val joined = meal != null && (editing != null || join)
    val earlier = if (!joined) 0 else meal!!.carbs - (editing?.let { (it.carbs ?: 0.0).toInt() } ?: 0)
    val mealIds = meal?.foods?.map { it.id }.orEmpty().toSet() + listOfNotNull(editing?.id)
    val openPlan = planned.firstOrNull { it.state == "planned" && (it.mealId in mealIds) }
    val given = if (joined) meal!!.given else 0.0

    val typed = parseMeter(meterText, units)
    val lastMeter = treatments.filter { it.glucose != null && it.t <= now }.maxByOrNull { it.t }
    val meter = typed ?: lastMeter?.glucose
    val meterAt = if (typed != null) now else lastMeter?.t
    val recent = treatments.filter {
        Meals.isRapid(it) && it.t in (now - 3 * 3_600_000L)..now && (meal == null || meal.insulin.none { m -> m.id == it.id })
    }.maxByOrNull { it.t }?.let { (it.insulin ?: 0.0) to it.t }
    val span = (treatments.maxOfOrNull { it.t } ?: 0) - (treatments.minOfOrNull { it.t } ?: 0)
    val usualDose = if (span >= 24 * 3_600_000L) treatments.filter(Meals::isRapid).maxOfOrNull { it.insulin ?: 0.0 } else null
    val usualCarbs = Meals.group(others, gap).takeIf { it.size >= 5 }?.maxOfOrNull { it.carbs }
    val m = DoseCalc.meal(dose, earlier + current, meter, meterAt, now, given, recent, usualDose, usualCarbs)
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
            foodItems = foodItemsOf(rows),
        )
        onSave(food)
        // a fresh glucometer value typed here is a reading of its own
        if (typed != null) onSave(Treatment(id = TreatmentQueue.newId(), t = now, glucose = typed))
        val p = openPlan
        if (!forLow && (withPlan || p != null) && m.toGive > 0 && repo != null) {
            val pd = PlannedDose(
                id = p?.id ?: java.util.UUID.randomUUID().toString(),
                mealId = p?.mealId ?: food.id,
                dose = m.toGive, food = m.food, corr = m.corr, carbs = m.carbs,
                meter = if (m.fresh) meter else null, meterAt = if (m.fresh) meterAt else null,
                createdAt = p?.createdAt ?: now,
            )
            scope.launch { repo.planDose(pd).onFailure { failed = true } }
        }
        if (keepsDraft) MealDrafts.clear(ctx)
        onSaved(food)
    }
    fun save() {
        if (current <= 0) return
        if (forLow) return finish(false)
        when {
            planOn && !m.asks -> finish(true)
            !m.canPlan && !m.asks -> finish(false)
            else -> { okBig = false; asking = true }
        }
    }

    // back closes and keeps the draft
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.meal_close)
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(48.dp).semantics { contentDescription = back },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("‹", fontSize = 22.sp, color = c.textMuted) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (forLow) R.string.meal_low_title else if (editing != null) R.string.meal_edit_title else R.string.meal_title), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (keepsDraft) Text(stringResource(R.string.meal_draft_note), color = c.textFaint, fontSize = 11.5.sp)
            }
            if (keepsDraft) TextButton(onClick = { if (rows.any { it.name.isNotBlank() }) closing = true else { MealDrafts.clear(ctx); onClose() } }) {
                Text(stringResource(R.string.meal_discard), color = c.textMuted)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (meal != null && editing == null && !forLow) {
                Column(
                    Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.meal_join_q, AlarmText.clock(ctx, meal.from), meal.carbs, formatAmount(meal.given, locale)), color = c.text, fontSize = 14.sp)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = join, onClick = { join = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.meal_join_yes), fontSize = 13.sp) }
                        SegmentedButton(selected = !join, onClick = { join = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.meal_join_no), fontSize = 13.sp) }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.meal_eaten), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val n = rows.count { it.name.isNotBlank() || it.grams != null }
                Text(if (n == 0) stringResource(R.string.meal_empty_short) else pluralStringResource(R.plurals.meal_dishes, n, n) + " · $current " + stringResource(R.string.unit_carbs), color = c.textMuted, fontSize = 13.sp)
            }
            if (rows.isNotEmpty()) Text(stringResource(R.string.meal_swipe_hint), color = c.textFaint, fontSize = 11.5.sp)

            Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
                if (rows.isEmpty()) Text(stringResource(R.string.meal_empty), color = c.textMuted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                rows.forEach { row ->
                    key(row.id) {
                        FoodLookup(row, repo) { f -> update(row.id, f) }
                        DishRow(
                            row, lang, open = open == row.id,
                            onToggle = { open = if (open == row.id) null else row.id },
                            onChange = { f -> update(row.id, f) },
                            onSplit = { parts ->
                                val i = rows.indexOfFirst { it.id == row.id }
                                val made = parts.map { newFoodRow(name = it.replaceFirstChar { ch -> ch.uppercase() }) }
                                rows.removeAt(i); rows.addAll(i, made); open = made.first().id
                            },
                            onRemove = { remove(row.id) },
                            onDone = { open = null },
                        )
                    }
                }
                val addFocus = remember { FocusRequester() }
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.replace('\n', ' ').take(80) },
                        placeholder = { Text(stringResource(if (rows.isEmpty()) R.string.meal_add_first else R.string.meal_add_more), color = c.textFaint) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTyped() }),
                        modifier = Modifier.weight(1f).focusRequester(addFocus),
                    )
                    val add = stringResource(R.string.meal_add)
                    Button(
                        onClick = { addTyped() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.carbs, contentColor = c.background),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.size(52.dp).semantics { contentDescription = add },
                    ) { Text("+", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            removed?.let { (at, row) ->
                Row(Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(14.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.meal_removed, row.name.ifBlank { "—" }), color = c.text, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { rows.add(at.coerceAtMost(rows.size), row); removed = null }) { Text(stringResource(R.string.meal_undo), color = c.carbs, fontWeight = FontWeight.SemiBold) }
                }
            }

            QuickFoods(repo, lang, onAdd = { name, portion -> place(newFoodRow(name = name, portion = portion)) }, onAll = { picking = true })

            if (forLow) Text(
                stringResource(R.string.meal_low_note), color = c.text, fontSize = 13.5.sp,
                modifier = Modifier.fillMaxWidth().background(c.inRangeContainer, RoundedCornerShape(14.dp)).padding(12.dp),
            ) else DoseCard(m, dose, units, locale, earlier, given, typedMeter = meterText, onMeter = { meterText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5) },
                plan = planOn, onPlan = { plan = it }, openPlan = openPlan)

            Text(stringResource(R.string.meal_when), color = c.textMuted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing == null) TimeShifts.forEachIndexed { i, mins ->
                    FilterChip(selected = whenIdx == i, onClick = { whenIdx = i }, label = {
                        Text(if (mins == 0) stringResource(R.string.tr_now) else stringResource(R.string.tr_minutes_ago, mins))
                    })
                }
                FilterChip(selected = whenIdx == 3, onClick = { pickingTime = true }, label = {
                    Text(if (whenIdx == 3) atLabel(time) else stringResource(R.string.tr_other_time))
                })
            }
            Text(stringResource(R.string.meal_not_advice), color = c.textFaint, fontSize = 11.5.sp)
        }

        Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val empty = rows.count { it.grams == null && it.name.isNotBlank() }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (empty > 0) pluralStringResource(R.plurals.food_empty, empty, empty) else stringResource(R.string.food_total),
                    color = if (empty > 0) c.high else c.textMuted, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
                )
                Text("$current", style = GlucoseHero.copy(fontSize = 30.sp, lineHeight = 34.sp, color = c.carbs))
                Text(" " + stringResource(R.string.unit_carbs), color = c.textMuted, fontSize = 14.sp)
            }
            Button(
                onClick = { save() },
                enabled = current > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.carbs, contentColor = c.background),
            ) {
                Text(
                    stringResource(R.string.meal_save, current) + if (planOn) " " + stringResource(R.string.meal_save_plan, formatAmount(m.toGive, locale)) else "",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (picking) FoodPicker(
        tables = rememberCatalog(repo)?.tables.orEmpty(), lang = lang,
        onPick = { f, _ ->
            picking = false
            place(newFoodRow(name = f.names.inLang(lang), carbs = f.carbs.toString(), source = "table", portion = f.portions.inLang(lang), tableId = f.id))
            open = null
        },
        onDismiss = { picking = false },
    )
    if (pickingTime) TimeDialog(initial = time, onPick = { pickedAt = it; whenIdx = 3; pickingTime = false }, onDismiss = { pickingTime = false })
    if (closing) AlertDialog(
        onDismissRequest = { closing = false },
        title = { Text(stringResource(R.string.meal_discard_title)) },
        text = { Text(pluralStringResource(R.plurals.meal_discard_text, rows.size, rows.size)) },
        confirmButton = { TextButton(onClick = { closing = false; MealDrafts.clear(ctx); onClose() }) { Text(stringResource(R.string.meal_discard_yes), color = c.low) } },
        dismissButton = { TextButton(onClick = { closing = false }) { Text(stringResource(R.string.meal_discard_no)) } },
    )
    if (failed) AlertDialog(
        onDismissRequest = { failed = false },
        title = { Text(stringResource(R.string.meal_plan_failed)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.tr_ok)) } },
    )
    if (asking) SaveSheet(m, dose, units, locale, planOn, okBig, onOkBig = { okBig = it }, onPlan = { finish(true) }, onFoodOnly = { finish(false) }, onBack = { asking = false })
}

@Composable
private fun rememberCatalog(repo: LiveRepository?): FoodCatalog? {
    var catalog by remember { mutableStateOf<FoodCatalog?>(null) }
    LaunchedEffect(repo) { catalog = repo?.foods()?.getOrNull() }
    return catalog
}

/** "Often: + Борщ, + Хліб…" and the whole table: a quick button adds the name and portion, the carbs come as a suggestion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFoods(repo: LiveRepository?, lang: String, onAdd: (String, String) -> Unit, onAll: () -> Unit) {
    val c = DiaTheme.colors
    val catalog = rememberCatalog(repo) ?: return
    if (catalog.frequent.isEmpty() && catalog.tables.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (catalog.frequent.isNotEmpty()) Text(stringResource(R.string.food_often), color = c.textFaint, fontSize = 12.sp)
        catalog.frequent.forEach { f ->
            OutlinedButton(onClick = { onAdd(f.name, f.portion) }, shape = CircleShape, modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("+ " + f.name, color = c.text, fontSize = 13.sp) }
        }
        if (catalog.tables.isNotEmpty()) FilledTonalButton(onClick = onAll, shape = CircleShape, modifier = Modifier.heightIn(min = 40.dp)) {
            Text(stringResource(R.string.food_all), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One dish: folded it reads like a receipt line; a tap opens portion, carbs and suggestions; a swipe left takes it away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DishRow(
    row: FoodRow,
    lang: String,
    open: Boolean,
    onToggle: () -> Unit,
    onChange: ((FoodRow) -> FoodRow) -> Unit,
    onSplit: (List<String>) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    val c = DiaTheme.colors
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false },
        positionalThreshold = { it * 0.35f },
    )
    val hinted = row.source == "mine" || row.source == "table" || row.source == "estimate"
    Column(Modifier.fillMaxWidth().background(if (open) c.surfaceRaised else Color.Transparent)) {
        SwipeToDismissBox(
            state = swipe,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(Modifier.fillMaxSize().background(c.lowContainer).padding(end = 18.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(stringResource(R.string.meal_swipe_remove), color = c.low, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            },
        ) {
            Row(
                Modifier.fillMaxWidth().background(if (open) c.surfaceRaised else c.surface).clickable(onClick = onToggle).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name.ifBlank { "—" }, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        (row.portion.ifBlank { stringResource(R.string.meal_no_portion) }) +
                            if (row.grams == null) "" else " · " + stringResource(if (hinted) R.string.meal_from_hint else R.string.meal_by_hand),
                        color = c.textMuted, fontSize = 12.sp,
                    )
                    if ((row.grams ?: 0) > 150) Text(stringResource(R.string.meal_dish_check), color = c.high, fontSize = 12.sp)
                }
                val empty = row.grams == null
                Text(
                    if (empty) "— " + stringResource(R.string.unit_carbs) else (if (hinted) "≈ " else "") + row.carbs + " " + stringResource(R.string.unit_carbs),
                    style = GlucoseHero.copy(fontSize = 16.sp, lineHeight = 20.sp, color = if (empty) c.high else c.carbs),
                    modifier = Modifier
                        .then(if (empty) Modifier.border(BorderStroke(1.dp, c.high), RoundedCornerShape(10.dp)) else Modifier.background(c.carbsContainer, RoundedCornerShape(10.dp)))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        splitParts(row.name)?.let { parts ->
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp).background(c.highContainer, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.meal_split_hint), color = c.text, fontSize = 12.5.sp)
                FilledTonalButton(onClick = { onSplit(parts) }, shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.meal_split, parts.joinToString(" + ")), fontSize = 13.sp)
                }
            }
        }
        if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = row.portion,
                    onValueChange = { v -> onChange { it.copy(portion = v.take(40)) } },
                    label = { Text(stringResource(R.string.food_portion)) },
                    placeholder = { Text(stringResource(R.string.food_portion_hint), color = c.textFaint) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.carbs,
                    onValueChange = { v -> onChange { it.copy(carbs = v.filter(Char::isDigit).take(3), source = "manual") } },
                    label = { Text(stringResource(R.string.food_carbs)) },
                    singleLine = true,
                    textStyle = GlucoseHero.copy(fontSize = 20.sp, lineHeight = 24.sp, color = c.carbs),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(116.dp),
                )
            }
            FoodSuggestions(row, lang) { f ->
                onChange(f)
                // a picked suggestion finishes the line
                if (f(row).source != "manual") onDone()
            }
            Button(
                onClick = onDone,
                enabled = row.grams != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.carbs, contentColor = c.background, disabledContainerColor = Color.Transparent, disabledContentColor = c.textMuted),
                border = if (row.grams == null) BorderStroke(1.dp, c.textFaint) else null,
            ) {
                Text(if (row.grams == null) stringResource(R.string.meal_need_carbs) else stringResource(R.string.meal_done, row.grams!!), fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** The worked-out dose, line by line, with what to look at before injecting. */
@Composable
private fun DoseCard(
    m: DoseCalc.Meal,
    dose: DoseSettings,
    units: Units,
    locale: java.util.Locale,
    earlier: Int,
    given: Double,
    typedMeter: String,
    onMeter: (String) -> Unit,
    plan: Boolean,
    onPlan: (Boolean) -> Unit,
    openPlan: PlannedDose?,
) {
    val c = DiaTheme.colors
    val u = stringResource(R.string.unit_insulin)
    fun f(x: Double) = formatAmount(x, locale)
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(18.dp)).border(1.dp, c.insulinContainer, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            Text(stringResource(R.string.meal_dose_title), color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.meal_ratio, dose.ratio) + " · " + stringResource(R.string.meal_step, f(dose.step)), color = c.textFaint, fontSize = 11.5.sp)
        }
        Line(
            (if (earlier > 0) stringResource(R.string.meal_line_carbs_join, earlier, m.carbs - earlier, m.carbs) else "${m.carbs} " + stringResource(R.string.unit_carbs)) + " ÷ ${dose.ratio}",
            String.format(locale, "%.2f", m.food).trimEnd('0').trimEnd(',', '.') + " $u",
        )
        Line(
            if (m.fresh) stringResource(R.string.meal_line_corr, formatGlucose(m.meter ?: 0, units, locale), m.meterAgeMin ?: 0) else stringResource(R.string.meal_line_corr_none),
            if (m.fresh) "+" + f(m.corr) + " $u" else stringResource(R.string.meal_not_counted),
        )
        Line(stringResource(R.string.meal_line_total, f(m.raw)), f(m.dose) + " $u")
        if (given > 0) Line(stringResource(R.string.meal_line_given), "−" + f(given) + " $u")
        Text(stringResource(R.string.meal_rule_note, ruleName(dose.rule)), color = c.textFaint, fontSize = 11.5.sp)
        m.alerts.forEach { AlertCard(it, units, locale) }
        if (!m.fresh) OutlinedTextField(
            value = typedMeter,
            onValueChange = onMeter,
            label = { Text(stringResource(R.string.meal_fresh_meter, if (units == Units.Mmol) stringResource(R.string.unit_mmol) else "mg/dL")) },
            singleLine = true,
            textStyle = GlucoseHero.copy(fontSize = 22.sp, lineHeight = 26.sp, color = c.text),
            keyboardOptions = KeyboardOptions(keyboardType = if (units == Units.Mmol) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (given > 0) R.string.meal_to_give_more else R.string.meal_to_give), color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text((if (given > 0 && m.toGive > 0) "+" else "") + f(m.toGive), style = GlucoseHero.copy(fontSize = 44.sp, lineHeight = 48.sp, color = c.insulin))
            Text(" $u", color = c.textMuted, fontSize = 16.sp)
        }
        when {
            m.level == DoseCalc.Level.Absurd && m.carbs > 0 -> Text(stringResource(R.string.meal_cannot_plan), color = c.low, fontSize = 13.sp)
            openPlan != null -> Text(stringResource(R.string.meal_plan_follows, f(openPlan.dose), f(m.toGive)), color = c.insulin, fontSize = 13.sp)
            m.canPlan -> Row(
                Modifier.fillMaxWidth()
                    .border(if (plan) 1.5.dp else 1.dp, if (plan) c.insulin else c.textFaint, RoundedCornerShape(14.dp))
                    .background(if (plan) c.insulinContainer.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onPlan(!plan) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Switch(checked = plan, onCheckedChange = onPlan)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.meal_plan, f(m.toGive)), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.meal_plan_note), color = c.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    val c = DiaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = c.textMuted, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = GlucoseHero.copy(fontSize = 14.sp, lineHeight = 18.sp, color = c.text))
    }
}

@Composable
fun ruleName(rule: String): String = stringResource(
    when (rule) {
        "nearest" -> R.string.rule_nearest
        "down" -> R.string.rule_down
        "up" -> R.string.rule_up
        else -> R.string.rule_half_down
    },
)

/** A bright card: red for what must be seen before injecting, amber for a note, green when all agrees. */
@Composable
fun AlertCard(a: DoseCalc.Alert, units: Units, locale: java.util.Locale, title: String? = null, text: String? = null) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val u = stringResource(R.string.unit_insulin)
    fun f(x: Double) = formatAmount(x, locale)
    fun g(mg: Int) = formatGlucose(mg, units, locale)
    val (t0, x0) = when (a) {
        is DoseCalc.Alert.MeterOld -> stringResource(R.string.alert_meter_old) to stringResource(R.string.alert_meter_old_text, a.ageMin, a.freshMin)
        is DoseCalc.Alert.NoMeter -> stringResource(R.string.alert_meter_old) to stringResource(R.string.alert_no_meter_text, a.freshMin)
        is DoseCalc.Alert.Low -> stringResource(R.string.alert_low, g(a.mg)) to stringResource(R.string.alert_low_text)
        is DoseCalc.Alert.VeryHigh -> stringResource(R.string.alert_very_high, g(a.mg)) to stringResource(R.string.alert_very_high_text, f(a.maxCorr))
        is DoseCalc.Alert.RecentRapid -> stringResource(R.string.alert_recent, f(a.units), AlarmText.clock(ctx, a.at)) to stringResource(R.string.alert_recent_text)
        is DoseCalc.Alert.GivenLess -> stringResource(R.string.alert_given_less, f(a.dose - a.given)) to stringResource(R.string.alert_given_text, f(a.given), f(a.dose))
        is DoseCalc.Alert.GivenMore -> stringResource(R.string.alert_given_more, f(a.given - a.dose)) to stringResource(R.string.alert_given_more_text, f(a.given), f(a.dose))
        is DoseCalc.Alert.GivenSame -> stringResource(R.string.alert_given_same) to "${f(a.given)} $u"
        is DoseCalc.Alert.CarbsHigh -> stringResource(R.string.alert_carbs_high, a.carbs) to stringResource(R.string.alert_carbs_high_text, a.max)
        is DoseCalc.Alert.CarbsUnusual -> stringResource(R.string.alert_carbs_unusual, a.carbs) to stringResource(R.string.alert_carbs_unusual_text, a.usual)
        is DoseCalc.Alert.DoseAbsurd -> stringResource(R.string.alert_dose_absurd, f(a.dose)) to stringResource(R.string.alert_dose_absurd_text, f(a.max))
        is DoseCalc.Alert.DoseHigh -> stringResource(R.string.alert_dose_high, f(a.dose)) to stringResource(R.string.alert_dose_high_text, f(a.max))
        is DoseCalc.Alert.DoseUnusual -> stringResource(R.string.alert_dose_unusual, f(a.dose)) to stringResource(R.string.alert_dose_unusual_text, f(a.usual))
    }
    val head = title ?: t0
    val body = text ?: x0
    val ok = a is DoseCalc.Alert.GivenSame
    val (bg, line, ink) = when {
        ok -> Triple(c.inRangeContainer, c.inRange, c.onInRangeContainer)
        a.bad -> Triple(c.lowContainer, c.low, c.text)
        else -> Triple(c.highContainer, c.high, c.text)
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(14.dp)).border(1.5.dp, line, RoundedCornerShape(14.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (ok) "✓" else "!", color = line, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(head, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = ink, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** Before saving: what to know, an extra tap for a dose over the limit, and the choice to plan the dose or not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(
    m: DoseCalc.Meal,
    dose: DoseSettings,
    units: Units,
    locale: java.util.Locale,
    planOn: Boolean,
    okBig: Boolean,
    onOkBig: (Boolean) -> Unit,
    onPlan: () -> Unit,
    onFoodOnly: () -> Unit,
    onBack: () -> Unit,
) {
    val c = DiaTheme.colors
    fun f(x: Double) = formatAmount(x, locale)
    val needOk = m.level == DoseCalc.Level.High
    ModalBottomSheet(onDismissRequest = onBack, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (planOn || !m.canPlan) R.string.meal_check_title else R.string.meal_plan_q), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (m.canPlan) Text(stringResource(R.string.meal_plan_q_text, f(m.toGive)), color = c.textMuted, fontSize = 13.5.sp)
            m.alerts.forEach { AlertCard(it, units, locale) }
            if (m.canPlan && needOk) Row(
                Modifier.fillMaxWidth().border(1.5.dp, c.low, RoundedCornerShape(14.dp)).clickable { onOkBig(!okBig) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = okBig, onCheckedChange = onOkBig)
                Text(stringResource(R.string.meal_ok_big, f(m.toGive)), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (m.canPlan) Button(
                onClick = onPlan,
                enabled = !needOk || okBig,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background),
            ) { Text(stringResource(if (m.asks) R.string.meal_plan_yes_seen else R.string.meal_plan_yes, f(m.toGive)), fontWeight = FontWeight.SemiBold) }
            else if (m.level == DoseCalc.Level.Absurd) Text(stringResource(R.string.meal_cannot_plan), color = c.low, fontSize = 13.5.sp)
            OutlinedButton(onClick = onFoodOnly, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.meal_food_only), color = c.carbs, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tr_cancel)) }
        }
    }
}
