package com.lumenarcanus.dialife.feature.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import com.lumenarcanus.dialife.core.data.FoodRepository
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.Meal
import com.lumenarcanus.dialife.data.inLang
import com.lumenarcanus.dialife.feature.entry.FoodLookup
import com.lumenarcanus.dialife.feature.entry.FoodPicker
import com.lumenarcanus.dialife.feature.entry.FoodRow
import com.lumenarcanus.dialife.feature.entry.TimeDialog
import com.lumenarcanus.dialife.feature.entry.atLabel
import com.lumenarcanus.dialife.feature.entry.newFoodRow
import java.util.Locale

// The food form's parts, top to bottom; MealScreen puts them together.

/** Back, the title, and "discard" for a new meal's draft. */
@Composable
internal fun MealTopBar(forLow: Boolean, editing: Boolean, keepsDraft: Boolean, onClose: () -> Unit, onDiscard: () -> Unit) {
    val c = DiaTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val back = stringResource(R.string.meal_close)
        OutlinedButton(
            onClick = onClose,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(48.dp).semantics { contentDescription = back },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("‹", fontSize = 22.sp, color = c.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when {
                        forLow -> R.string.meal_low_title
                        editing -> R.string.meal_edit_title
                        else -> R.string.meal_title
                    }
                ),
                color = c.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (keepsDraft) Text(stringResource(R.string.meal_draft_note), color = c.textFaint, fontSize = 11.5.sp)
        }
        if (keepsDraft) {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.meal_discard), color = c.textMuted)
            }
        }
    }
}

/** "Add to the meal at 12:40?": food minutes after a meal counts with it, unless said otherwise. */
@Composable
internal fun JoinQuestion(meal: Meal, join: Boolean, onJoin: (Boolean) -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
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
            SegmentedButton(selected = join, onClick = { onJoin(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.meal_join_yes), fontSize = 13.sp)
            }
            SegmentedButton(selected = !join, onClick = { onJoin(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.meal_join_no), fontSize = 13.sp)
            }
        }
    }
}

/** "Eaten" with how many dishes and grams. */
@Composable
internal fun DishesHeader(form: MealFormState, current: Int) {
    val c = DiaTheme.colors
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
            if (n == 0) {
                stringResource(R.string.meal_empty_short)
            } else {
                pluralStringResource(R.plurals.meal_dishes, n, n) + " · $current " + stringResource(R.string.unit_carbs)
            },
            color = c.textMuted,
            fontSize = 13.sp
        )
    }
}

/** The dishes, each looked up as typed, and the field that adds one. */
@Composable
internal fun Dishes(form: MealFormState, repo: FoodRepository?, lang: String) {
    val c = DiaTheme.colors
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
        AddDish(form)
    }
}

/** The field a dish is typed into ("гречка 150 г"), and "+". */
@Composable
private fun AddDish(form: MealFormState) {
    val c = DiaTheme.colors
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
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(52.dp).semantics { contentDescription = add }
        ) { Text("+", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** "Борщ removed · Undo", for a few seconds. */
@Composable
internal fun RemovedNote(row: FoodRow, onUndo: () -> Unit) {
    val c = DiaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(14.dp)).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.meal_removed, row.name.ifBlank { "—" }),
            color = c.text,
            fontSize = 13.5.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onUndo) { Text(stringResource(R.string.meal_undo), color = c.carbs, fontWeight = FontWeight.SemiBold) }
    }
}

/** When it was eaten: now, a little earlier (a new meal only), or a time picked on the clock. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhenChips(form: MealFormState, isNew: Boolean, time: Long) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isNew) {
            TimeShifts.forEachIndexed { i, mins ->
                FilterChip(selected = form.whenIdx == i, onClick = { form.whenIdx = i }, label = {
                    Text(if (mins == 0) stringResource(R.string.tr_now) else stringResource(R.string.tr_minutes_ago, mins))
                })
            }
        }
        FilterChip(selected = form.whenIdx == PICKED_TIME, onClick = { form.pickingTime = true }, label = {
            Text(if (form.whenIdx == PICKED_TIME) atLabel(time) else stringResource(R.string.tr_other_time))
        })
    }
}

/** The total (and dishes still without carbs), and "Save" with the dose to plan when there is one. */
@Composable
internal fun SaveBar(form: MealFormState, current: Int, toPlan: Double?, saving: Boolean, onSave: () -> Unit) {
    val c = DiaTheme.colors
    val locale = LocalConfiguration.current.locales[0]
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
            onClick = onSave,
            enabled = current > 0 && !saving,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.carbs, contentColor = c.background)
        ) {
            Text(
                if (saving) {
                    stringResource(R.string.meal_saving)
                } else {
                    stringResource(R.string.meal_save, current) +
                        toPlan?.let { " " + stringResource(R.string.meal_save_plan, formatAmount(it, locale)) }.orEmpty()
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** What the form opens over itself: the carb table, the clock dial, "discard the meal?", the save sheet. */
@Composable
internal fun MealDialogs(
    form: MealFormState,
    repo: FoodRepository?,
    lang: String,
    time: Long,
    m: DoseCalc.Meal,
    dose: DoseSettings,
    units: Units,
    locale: Locale,
    planOn: Boolean,
    onDiscard: () -> Unit,
    onFinish: (withPlan: Boolean) -> Unit
) {
    val c = DiaTheme.colors
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
            form.whenIdx = PICKED_TIME
            form.pickingTime = false
        }, onDismiss = {
            form.pickingTime = false
        })
    }
    if (form.closing) {
        AlertDialog(
            onDismissRequest = { form.closing = false },
            title = { Text(stringResource(R.string.meal_discard_title)) },
            text = { Text(pluralStringResource(R.plurals.meal_discard_text, form.rows.size, form.rows.size)) },
            confirmButton = {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.meal_discard_yes), color = c.low) }
            },
            dismissButton = { TextButton(onClick = { form.closing = false }) { Text(stringResource(R.string.meal_discard_no)) } }
        )
    }
    if (form.asking) {
        SaveSheet(
            m,
            dose,
            units,
            locale,
            planOn,
            form.okBig,
            onOkBig = { form.okBig = it },
            onPlan = { onFinish(true) },
            onFoodOnly = { onFinish(false) },
            onBack = { form.asking = false }
        )
    }
}
