package com.lumenarcanus.dialife.ui.treatment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.lumenarcanus.dialife.data.CarbSuggestion
import com.lumenarcanus.dialife.data.FoodItem
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero

/** One line of the meal being typed. carbs is the field's text; source says where it came from. */
data class FoodRow(
    val id: Long,
    val name: String = "",
    val portion: String = "",
    val carbs: String = "",
    val source: String? = null, // "mine" | "estimate" | "manual"
    val loading: Boolean = false,
    val asked: Boolean = false, // a suggestion was looked for (and maybe none came)
    val suggestion: CarbSuggestion? = null,
    val models: Boolean = false, // the server has models: no estimate means they did not know
) {
    val grams get() = carbs.toIntOrNull()
    fun toItem() = FoodItem(name.trim(), portion.trim().ifBlank { null }, grams?.toDouble(), source)
}

private var nextRowId = 1L
fun newFoodRow(name: String = "", carbs: String = "", source: String? = null, portion: String = "") = FoodRow(nextRowId++, name, portion, carbs, source)

/** The rows of a saved meal, or one row for an entry logged with only a total. */
fun foodRowsOf(items: List<FoodItem>?, carbs: Double?): List<FoodRow> = when {
    !items.isNullOrEmpty() -> items.map { newFoodRow(it.name, it.carbs?.let { c -> kotlin.math.round(c).toInt().toString() }.orEmpty(), it.source, it.portion.orEmpty()) }
    (carbs ?: 0.0) > 0 -> listOf(newFoodRow(carbs = kotlin.math.round(carbs!!).toInt().toString(), source = "manual"))
    else -> listOf(newFoodRow())
}

/** What goes into the entry: the lines worth keeping; none for a lone unnamed total. */
fun foodItemsOf(rows: List<FoodRow>): List<FoodItem>? {
    val kept = rows.filter { it.name.isNotBlank() || it.grams != null }
    return kept.takeUnless { it.isEmpty() || (it.size == 1 && it[0].name.isBlank()) }?.map { it.toItem() }
}

private val QuickCarbs = listOf(10, 15, 20, 30)

/**
 * The food form: a list, each line with its own carbs. A suggestion comes from
 * the family's earlier entries and from the server's models, and only ever
 * lands in the field on a tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodList(rows: SnapshotStateList<FoodRow>, repo: LiveRepository?) {
    val c = DiaTheme.colors
    fun update(id: Long, f: (FoodRow) -> FoodRow) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = f(rows[i])
    }
    Row(
        Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.food_disclaimer), color = c.text, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
    rows.forEach { row ->
        key(row.id) {
            // look the food up once the typing stops; a picked suggestion follows a new portion
            LaunchedEffect(row.name, row.portion) {
                val name = row.name.trim()
                if (repo == null) return@LaunchedEffect // a preview: keep what it was given
                if (name.length < 2) {
                    update(row.id) { it.copy(loading = false, asked = false, suggestion = null) }
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(700)
                update(row.id) { it.copy(loading = true) }
                val r = repo.suggestCarbs(name, row.portion.trim())
                update(row.id) { cur ->
                    val res = r.getOrNull()
                    val s = res?.items?.firstOrNull()
                    val follow = when (cur.source) {
                        "mine" -> s?.mine?.carbs
                        "estimate" -> s?.estimate?.carbs
                        else -> null
                    }
                    cur.copy(
                        loading = false, asked = r.isSuccess, suggestion = s, models = res?.models ?: false,
                        carbs = follow?.toString() ?: cur.carbs,
                    )
                }
            }
            FoodRowCard(row, canRemove = rows.size > 1, onChange = { f -> update(row.id, f) }, onRemove = { rows.removeAll { it.id == row.id } })
        }
    }
    OutlinedButton(onClick = { rows.add(newFoodRow()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.food_add), color = c.carbs, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodRowCard(row: FoodRow, canRemove: Boolean, onChange: ((FoodRow) -> FoodRow) -> Unit, onRemove: () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surfaceRaised, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // a long name wraps onto up to three lines, so all of it can be seen and edited;
            // it is still one line of text (Enter moves on to the portion)
            OutlinedTextField(
                value = row.name,
                onValueChange = { v -> onChange { it.copy(name = v.replace('\n', ' ').take(80)) } },
                label = { Text(stringResource(R.string.food_name)) },
                minLines = 1,
                maxLines = 3,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            if (canRemove) {
                val remove = stringResource(R.string.food_remove)
                IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = remove }) {
                    Text("×", fontSize = 24.sp, color = c.textMuted)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = row.portion,
                onValueChange = { v -> onChange { it.copy(portion = v.take(40)) } },
                label = { Text(stringResource(R.string.food_portion)) },
                placeholder = { Text(stringResource(R.string.food_portion_hint), color = c.textFaint) },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.carbs,
                onValueChange = { v -> onChange { it.copy(carbs = v.filter(Char::isDigit).take(3), source = "manual") } },
                label = { Text(stringResource(R.string.food_carbs)) },
                singleLine = true,
                colors = fieldColors(),
                textStyle = GlucoseHero.copy(fontSize = 20.sp, lineHeight = 24.sp, color = c.carbs),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
        }
        when {
            row.loading -> Text(stringResource(R.string.food_looking), color = c.textMuted, fontSize = 12.5.sp)
            row.suggestion?.mine != null || row.suggestion?.estimate != null -> {
                row.suggestion.mine?.let { m ->
                    SuggestionButton(
                        "≈ ${m.carbs}", stringResource(R.string.food_mine),
                        stringResource(R.string.food_mine_note, m.portion.ifBlank { "—" }, m.times),
                        on = row.source == "mine" && row.grams == m.carbs,
                    ) { onChange { it.copy(carbs = m.carbs.toString(), source = "mine") } }
                }
                row.suggestion.estimate?.let { e ->
                    SuggestionButton(
                        "≈ ${e.carbs}", stringResource(R.string.food_estimate, e.lo, e.hi),
                        pluralStringResource(R.plurals.food_models, e.models, e.models),
                        on = row.source == "estimate" && row.grams == e.carbs,
                    ) { onChange { it.copy(carbs = e.carbs.toString(), source = "estimate") } }
                }
            }
            row.asked && row.name.isNotBlank() -> Text(
                stringResource(if (row.models) R.string.food_unknown else R.string.food_no_models),
                color = c.textMuted, fontSize = 12.5.sp,
            )
        }
        // a low needs 15 g now, not a recipe: the usual amounts in one tap
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickCarbs.forEach { q ->
                FilterChip(
                    selected = row.grams == q && row.source == "manual",
                    onClick = { onChange { it.copy(carbs = q.toString(), source = "manual") } },
                    label = { Text("$q " + stringResource(R.string.unit_carbs)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.carbsContainer, selectedLabelColor = c.carbs),
                    shape = CircleShape,
                )
            }
        }
        Text(
            stringResource(
                when {
                    row.grams == null -> R.string.food_src_none
                    row.source == "mine" -> R.string.food_src_mine
                    row.source == "estimate" -> R.string.food_src_estimate
                    else -> R.string.food_src_manual
                },
            ),
            color = if (row.grams == null) c.high else c.textMuted, fontSize = 11.5.sp,
        )
    }
}

/** Fields that stand out on the card: the default outline is lost on the raised surface. */
@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = DiaTheme.colors.textFaint,
    focusedBorderColor = DiaTheme.colors.carbs,
    focusedLabelColor = DiaTheme.colors.carbs,
)

@Composable
private fun SuggestionButton(value: String, label: String, note: String, on: Boolean, onClick: () -> Unit) {
    val c = DiaTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (on) c.carbs else c.textFaint),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = if (on) c.carbsContainer else androidx.compose.ui.graphics.Color.Transparent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value + " " + stringResource(R.string.unit_carbs), color = if (on) c.carbs else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))
            Column {
                Text(label, color = if (on) c.carbs else c.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(note, color = c.textMuted, fontSize = 11.5.sp)
            }
        }
    }
}

/** "64 г · 2 з підказки · 1 вручну" under the total. */
@Composable
fun FoodTotal(rows: List<FoodRow>) {
    val c = DiaTheme.colors
    val filled = rows.filter { it.grams != null }
    val total = filled.sumOf { it.grams ?: 0 }
    val hinted = filled.count { it.source == "mine" || it.source == "estimate" }
    val manual = filled.size - hinted
    val empty = rows.count { it.grams == null && it.name.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.food_total), color = c.textMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            Text("$total", style = GlucoseHero.copy(fontSize = 40.sp, lineHeight = 44.sp, color = c.carbs))
            Text(stringResource(R.string.unit_carbs), color = c.textMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        listOfNotNull(
            pluralStringResource(R.plurals.food_hinted, hinted, hinted).takeIf { hinted > 0 },
            pluralStringResource(R.plurals.food_manual, manual, manual).takeIf { manual > 0 },
        ).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = c.textFaint, fontSize = 12.sp) }
        if (empty > 0) Text(pluralStringResource(R.plurals.food_empty, empty, empty), color = c.high, fontSize = 12.sp)
    }
}
