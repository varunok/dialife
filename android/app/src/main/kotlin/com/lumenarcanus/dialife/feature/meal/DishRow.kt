package com.lumenarcanus.dialife.feature.meal

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.theme.GlucoseHero
import com.lumenarcanus.dialife.feature.entry.FoodRow
import com.lumenarcanus.dialife.feature.entry.FoodSuggestions
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * One dish: folded it reads like a receipt line; a tap opens portion, carbs and
 * suggestions; a swipe left uncovers "rename" and "remove", and nothing happens
 * until one is tapped.
 */
@Composable
internal fun DishRow(
    row: FoodRow,
    lang: String,
    open: Boolean,
    revealed: Boolean,
    onToggle: () -> Unit,
    onReveal: (Boolean) -> Unit,
    onChange: ((FoodRow) -> FoodRow) -> Unit,
    onSplit: (List<String>) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit
) {
    val c = DiaTheme.colors
    Column(Modifier.fillMaxWidth().background(if (open) c.surfaceRaised else Color.Transparent)) {
        SwipeActions(revealed, onReveal, onRename, onRemove) { DishSummary(row, open, onToggle) }
        splitParts(row.name)?.let { parts -> SplitOffer(parts) { onSplit(parts) } }
        if (open) DishEditor(row, lang, onChange, onDone)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

private val ActionWidth = 96.dp

/** The line slides left over its two actions and stays there until slid back, tapped through, or another dish is touched. */
@Composable
private fun SwipeActions(
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit
) {
    val c = DiaTheme.colors
    val scope = rememberCoroutineScope()
    val width = with(LocalDensity.current) { (ActionWidth * 2).toPx() }
    val offset = remember { Animatable(0f) }
    LaunchedEffect(revealed) { offset.animateTo(if (revealed) -width else 0f) }
    val drag = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-width, 0f)) } }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
            SwipeAction(stringResource(R.string.meal_swipe_rename), c.text, c.surfaceRaised, onRename)
            SwipeAction(stringResource(R.string.meal_swipe_remove), c.low, c.lowContainer, onRemove)
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(drag, Orientation.Horizontal, onDragStopped = { v ->
                    val show = offset.value < -width / 2 || v < -1_500f
                    offset.animateTo(if (show) -width else 0f)
                    onReveal(show)
                })
        ) { content() }
    }
}

@Composable
private fun SwipeAction(label: String, ink: Color, fill: Color, onClick: () -> Unit) {
    Box(
        Modifier.width(ActionWidth).fillMaxHeight().background(fill).clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** The dish as a line: name, portion and where the carbs came from, the carbs ("—" in red while there are none). */
@Composable
private fun DishSummary(row: FoodRow, open: Boolean, onToggle: () -> Unit) {
    val c = DiaTheme.colors
    val hinted = row.source == "mine" || row.source == "table" || row.source == "estimate"
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
                    if (row.grams == null) {
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

/** "Борщ і хліб" typed as one: split it into dishes. */
@Composable
private fun SplitOffer(parts: List<String>, onSplit: () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(
            start = 14.dp,
            end = 14.dp,
            bottom = 10.dp
        ).background(c.highContainer, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.meal_split_hint), color = c.text, fontSize = 12.5.sp)
        FilledTonalButton(onClick = onSplit, shape = RoundedCornerShape(10.dp)) {
            Text(stringResource(R.string.meal_split, parts.joinToString(" + ")), fontSize = 13.sp)
        }
    }
}

/** The open dish: its portion and carbs, the suggestions, and "done". */
@Composable
private fun DishEditor(row: FoodRow, lang: String, onChange: ((FoodRow) -> FoodRow) -> Unit, onDone: () -> Unit) {
    val c = DiaTheme.colors
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
                row.grams?.let { stringResource(R.string.meal_done, it) } ?: stringResource(R.string.meal_need_carbs),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
