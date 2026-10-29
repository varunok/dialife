package com.lumenarcanus.dialife.ui.treatment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.lumenarcanus.dialife.data.CarbTableHit
import com.lumenarcanus.dialife.data.FoodCatalog
import com.lumenarcanus.dialife.data.inLang
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
    val source: String? = null, // "mine" | "table" | "estimate" | "manual"
    val loading: Boolean = false,
    val asked: Boolean = false, // a suggestion was looked for (and maybe none came)
    val suggestion: CarbSuggestion? = null,
    val models: Boolean = false, // the server has models: no estimate means they did not know
    val tableId: String? = null, // the table portion picked, so a new portion follows it
) {
    val grams get() = carbs.toIntOrNull()

    /**
     * A suggestion picked for one portion, kept after the portion changed to
     * one it could not be recounted for: the number is not for what is typed.
     */
    val stale: Boolean get() {
        val s = suggestion ?: return false
        if (loading || !asked || portion.isBlank()) return false
        return when (source) {
            "mine" -> s.mine?.carbs != grams
            // a line the lookup by name no longer finds (picked from the full list) cannot be judged
            "table" -> (s.table + s.similar).firstOrNull { it.id == tableId }?.let { h -> h.carbs != grams || otherPortion(h) } ?: false
            "estimate" -> s.estimate?.carbs != grams
            else -> false
        }
    }

    /** A table line for its own portion, not recounted to the one typed. */
    fun otherPortion(h: CarbTableHit): Boolean =
        portion.isNotBlank() && !h.scaled && !(h.carbs == 0 && h.base == 0) &&
            h.portions.values.none { it.trim().equals(portion.trim(), ignoreCase = true) }

    fun toItem() = FoodItem(name.trim(), portion.trim().ifBlank { null }, grams?.toDouble(), source)
}

private var nextRowId = 1L
fun newFoodRow(name: String = "", carbs: String = "", source: String? = null, portion: String = "", tableId: String? = null) =
    FoodRow(nextRowId++, name, portion, carbs, source, tableId = tableId)

/** Looks the food up once the typing stops; a picked suggestion follows a new portion. */
@Composable
fun FoodLookup(row: FoodRow, repo: LiveRepository?, update: ((FoodRow) -> FoodRow) -> Unit) {
    LaunchedEffect(row.name, row.portion) {
        val name = row.name.trim()
        if (repo == null) return@LaunchedEffect // a preview: keep what it was given
        if (name.length < 2) {
            update { it.copy(loading = false, asked = false, suggestion = null) }
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(700)
        update { it.copy(loading = true) }
        val r = repo.suggestCarbs(name, row.portion.trim())
        update { cur ->
            val res = r.getOrNull()
            val s = res?.items?.firstOrNull()
            val follow = when (cur.source) {
                "mine" -> s?.mine?.carbs
                "table" -> (s?.table.orEmpty() + s?.similar.orEmpty()).firstOrNull { it.id == cur.tableId }?.carbs
                "estimate" -> s?.estimate?.carbs
                else -> null
            }
            cur.copy(
                loading = false, asked = r.isSuccess, suggestion = s, models = res?.models ?: false,
                carbs = follow?.toString() ?: cur.carbs,
            )
        }
    }
}

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
    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
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
            FoodLookup(row, repo) { f -> update(row.id, f) }
            FoodRowCard(row, canRemove = rows.size > 1, lang = lang, onChange = { f -> update(row.id, f) }, onRemove = { rows.removeAll { it.id == row.id } })
        }
    }
    OutlinedButton(onClick = { rows.add(newFoodRow()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.food_add), color = c.carbs, fontWeight = FontWeight.SemiBold)
    }

    // a new line, or the empty one at the end when there is one
    fun place(row: FoodRow) {
        val last = rows.lastOrNull()
        if (last != null && last.name.isBlank() && last.carbs.isBlank()) rows[rows.lastIndex] = row else rows.add(row)
    }
    var catalog by remember { mutableStateOf<FoodCatalog?>(null) }
    LaunchedEffect(repo) { catalog = repo?.foods()?.getOrNull() }
    var picking by remember { mutableStateOf(false) }
    val tables = catalog?.tables.orEmpty()
    val often = catalog?.frequent.orEmpty()
    if (often.isNotEmpty() || tables.isNotEmpty()) {
        Text(stringResource(R.string.food_often), color = c.textMuted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // only the name and portion: the carbs come as a suggestion to pick
            often.forEach { f ->
                OutlinedButton(
                    onClick = { place(newFoodRow(name = f.name, portion = f.portion)) },
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) { Text("+ " + f.name, color = c.text, fontSize = 13.sp) }
            }
            if (tables.isNotEmpty()) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { picking = true },
                    shape = CircleShape,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) { Text(stringResource(R.string.food_all), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    if (picking) {
        FoodPicker(
            tables = tables,
            lang = lang,
            onPick = { f, source ->
                picking = false
                place(newFoodRow(name = f.names.inLang(lang), carbs = f.carbs.toString(), source = "table", portion = f.portions.inLang(lang), tableId = f.id))
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodRowCard(row: FoodRow, canRemove: Boolean, lang: String, onChange: ((FoodRow) -> FoodRow) -> Unit, onRemove: () -> Unit) {
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
        FoodSuggestions(row, lang, onChange)
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
                    row.source == "table" -> R.string.food_src_table
                    row.source == "estimate" -> R.string.food_src_estimate
                    else -> R.string.food_src_manual
                },
            ),
            color = if (row.grams == null) c.high else c.textMuted, fontSize = 11.5.sp,
        )
    }
}

/** What the family, the carb table and the models suggest for one line; a tap picks one. */
@Composable
fun FoodSuggestions(row: FoodRow, lang: String, onChange: ((FoodRow) -> FoodRow) -> Unit) {
    val c = DiaTheme.colors
    if (row.stale) Text(
        stringResource(R.string.food_stale, row.portion.trim()),
        color = c.high, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(12.dp)).border(1.dp, c.high, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    )
    when {
        row.loading -> Text(stringResource(R.string.food_looking), color = c.textMuted, fontSize = 12.5.sp)
        row.suggestion?.mine != null || row.suggestion?.table?.isNotEmpty() == true || row.suggestion?.similar?.isNotEmpty() == true || row.suggestion?.estimate != null -> {
            row.suggestion.mine?.let { m ->
                SuggestionButton(
                    "≈ ${m.carbs}", m.like?.let { stringResource(R.string.food_mine_like, it) } ?: stringResource(R.string.food_mine),
                    stringResource(R.string.food_mine_note, m.portion.ifBlank { "—" }, m.times),
                    on = row.source == "mine" && row.grams == m.carbs,
                ) { onChange { it.copy(carbs = m.carbs.toString(), source = "mine") } }
            }
            row.suggestion.table.forEach { h ->
                val portion = h.portions.inLang(lang)
                SuggestionButton(
                    "≈ ${h.carbs}", stringResource(R.string.food_table),
                    when {
                        h.carbs == 0 && h.base == 0 -> stringResource(R.string.food_table_free)
                        h.scaled -> stringResource(R.string.food_table_scaled, h.base, portion)
                        else -> h.names.inLang(lang) + if (portion.isBlank()) "" else " · $portion"
                    },
                    on = row.source == "table" && row.tableId == h.id && row.grams == h.carbs,
                    warn = otherPortion(row, h, portion),
                ) { onChange { it.copy(carbs = h.carbs.toString(), source = "table", tableId = h.id) } }
            }
            row.suggestion.similar.forEach { h ->
                val portion = h.portions.inLang(lang)
                SuggestionButton(
                    "≈ ${h.carbs}", stringResource(R.string.food_similar, h.names.inLang(lang)),
                    if (h.scaled) stringResource(R.string.food_table_scaled, h.base, portion) else portion.ifBlank { stringResource(R.string.food_table_free) },
                    on = row.source == "table" && row.tableId == h.id && row.grams == h.carbs,
                    warn = otherPortion(row, h, portion),
                ) { onChange { it.copy(carbs = h.carbs.toString(), source = "table", tableId = h.id) } }
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
}

/** "For «1 cup (250 ml)», not for «100 g»" when the table could not recount its line. */
@Composable
private fun otherPortion(row: FoodRow, h: CarbTableHit, portion: String): String? =
    if (row.otherPortion(h) && portion.isNotBlank()) stringResource(R.string.food_other_portion, portion, row.portion.trim()) else null

/** Fields that stand out on the card: the default outline is lost on the raised surface. */
@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = DiaTheme.colors.textFaint,
    focusedBorderColor = DiaTheme.colors.carbs,
    focusedLabelColor = DiaTheme.colors.carbs,
)

@Composable
fun SuggestionButton(value: String, label: String, note: String, on: Boolean, warn: String? = null, onClick: () -> Unit) {
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
                if (warn != null) Text(warn, color = c.high, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    val hinted = filled.count { it.source == "mine" || it.source == "table" || it.source == "estimate" }
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
