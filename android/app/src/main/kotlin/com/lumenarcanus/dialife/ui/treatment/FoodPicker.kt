package com.lumenarcanus.dialife.ui.treatment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.FoodTable
import com.lumenarcanus.dialife.data.TableFood
import com.lumenarcanus.dialife.data.inLang
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import com.lumenarcanus.dialife.ui.theme.GlucoseHero

/**
 * The whole carb table to pick a food from: by category or by a word typed in
 * any of its languages. A pick fills the line with the table's portion and
 * carbs, which the parent can still change.
 */
@Composable
fun FoodPicker(tables: List<FoodTable>, lang: String, onPick: (TableFood, FoodTable) -> Unit, onDismiss: () -> Unit) {
    val c = DiaTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    val all = remember(tables) { tables.flatMap { t -> t.items.map { it to t } } }
    val cats = remember(tables) { tables.flatMap { it.categories }.distinctBy { it.id } }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = all.filter { (f, _) ->
        (cat == null || f.cat == cat) && words.all { w -> f.names.values.any { it.lowercase().contains(w) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(c.surface).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.food_pick_title), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val close = stringResource(R.string.food_pick_close)
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = close }) {
                    Text("×", fontSize = 26.sp, color = c.textMuted)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(40) },
                placeholder = { Text(stringResource(R.string.food_pick_search), color = c.textFaint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text(stringResource(R.string.food_pick_all)) })
                cats.forEach { k ->
                    FilterChip(selected = cat == k.id, onClick = { cat = if (cat == k.id) null else k.id }, label = { Text(k.names.inLang(lang)) })
                }
            }
            if (shown.isEmpty()) Text(stringResource(R.string.food_pick_empty), color = c.textMuted, fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { (f, t) -> t.source + "/" + f.id }) { (f, t) ->
                    val portion = f.portions.inLang(lang)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .background(c.surfaceRaised, RoundedCornerShape(14.dp))
                            .clickable { onPick(f, t) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.names.inLang(lang), color = c.text, fontSize = 15.sp)
                            Text(
                                if (f.carbs == 0 && portion.isBlank()) stringResource(R.string.food_table_free) else portion,
                                color = c.textMuted, fontSize = 12.5.sp,
                            )
                        }
                        Text(
                            "${f.carbs} " + stringResource(R.string.unit_carbs),
                            style = GlucoseHero.copy(fontSize = 18.sp, lineHeight = 22.sp, color = if (f.carbs == 0) c.textMuted else c.carbs),
                        )
                    }
                }
            }
            // where the numbers come from, and that they are approximate
            tables.forEach { t ->
                Text(t.title + (t.note.inLang(lang).takeIf { it.isNotBlank() }?.let { ". $it" } ?: ""), color = c.textFaint, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}
