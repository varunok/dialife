package com.lumenarcanus.dialife.feature.meal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.feature.entry.FoodRow
import com.lumenarcanus.dialife.feature.entry.FoodSuggestions
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts

/** One dish: folded it reads like a receipt line; a tap opens portion, carbs and suggestions; a swipe left takes it away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DishRow(
    row: FoodRow,
    lang: String,
    open: Boolean,
    onToggle: () -> Unit,
    onChange: ((FoodRow) -> FoodRow) -> Unit,
    onSplit: (List<String>) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit
) {
    val c = DiaTheme.colors
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else {
                false
            }
        },
        positionalThreshold = { it * 0.35f }
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
            }
        ) {
            Row(
                Modifier.fillMaxWidth().background(
                    if (open) c.surfaceRaised else c.surface
                ).clickable(onClick = onToggle).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name.ifBlank { "—" }, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        (row.portion.ifBlank { stringResource(R.string.meal_no_portion) }) +
                            if (row.grams ==
                                null
                            ) {
                                ""
                            } else {
                                " · " + stringResource(if (hinted) R.string.meal_from_hint else R.string.meal_by_hand)
                            },
                        color = c.textMuted,
                        fontSize = 12.sp
                    )
                    if ((row.grams ?: 0) > 150) Text(stringResource(R.string.meal_dish_check), color = c.high, fontSize = 12.sp)
                }
                val empty = row.grams == null
                Text(
                    if (empty) {
                        "— " + stringResource(R.string.unit_carbs)
                    } else {
                        (if (hinted) "≈ " else "") + row.carbs + " " +
                            stringResource(R.string.unit_carbs)
                    },
                    style = GlucoseHero.copy(fontSize = 16.sp, lineHeight = 20.sp, color = if (empty) c.high else c.carbs),
                    modifier = Modifier
                        .then(
                            if (empty) {
                                Modifier.border(
                                    BorderStroke(1.dp, c.high),
                                    RoundedCornerShape(10.dp)
                                )
                            } else {
                                Modifier.background(c.carbsContainer, RoundedCornerShape(10.dp))
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        splitParts(row.name)?.let { parts ->
            Column(
                Modifier.fillMaxWidth().padding(
                    start = 14.dp,
                    end = 14.dp,
                    bottom = 10.dp
                ).background(c.highContainer, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.meal_split_hint), color = c.text, fontSize = 12.5.sp)
                FilledTonalButton(onClick = { onSplit(parts) }, shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.meal_split, parts.joinToString(" + ")), fontSize = 13.sp)
                }
            }
        }
        if (open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = row.portion,
                        onValueChange = { v -> onChange { it.copy(portion = v.take(40)) } },
                        label = { Text(stringResource(R.string.food_portion)) },
                        placeholder = { Text(stringResource(R.string.food_portion_hint), color = c.textFaint) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = row.carbs,
                        onValueChange = { v -> onChange { it.copy(carbs = v.filter(Char::isDigit).take(3), source = "manual") } },
                        label = { Text(stringResource(R.string.food_carbs)) },
                        singleLine = true,
                        textStyle = GlucoseHero.copy(fontSize = 20.sp, lineHeight = 24.sp, color = c.carbs),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(116.dp)
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.carbs,
                        contentColor = c.background,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = c.textMuted
                    ),
                    border = if (row.grams == null) BorderStroke(1.dp, c.textFaint) else null
                ) {
                    Text(
                        if (row.grams ==
                            null
                        ) {
                            stringResource(R.string.meal_need_carbs)
                        } else {
                            stringResource(R.string.meal_done, row.grams!!)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}
