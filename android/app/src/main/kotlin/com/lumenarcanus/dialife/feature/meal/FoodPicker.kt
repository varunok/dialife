package com.lumenarcanus.dialife.feature.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.FoodCatalog
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.ui.theme.DiaTheme

@Composable
internal fun rememberCatalog(repo: LiveRepository?): FoodCatalog? {
    var catalog by remember { mutableStateOf<FoodCatalog?>(null) }
    LaunchedEffect(repo) { catalog = repo?.foods()?.getOrNull() }
    return catalog
}

/** "Often: + Борщ, + Хліб…" and the whole table: a quick button adds the name and portion, the carbs come as a suggestion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickFoods(repo: LiveRepository?, lang: String, onAdd: (String, String) -> Unit, onAll: () -> Unit) {
    val c = DiaTheme.colors
    val catalog = rememberCatalog(repo) ?: return
    if (catalog.frequent.isEmpty() && catalog.tables.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        if (catalog.frequent.isNotEmpty()) Text(stringResource(R.string.food_often), color = c.textFaint, fontSize = 12.sp)
        catalog.frequent.forEach { f ->
            OutlinedButton(
                onClick = { onAdd(f.name, f.portion) },
                shape = CircleShape,
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
            ) {
                Text("+ " + f.name, color = c.text, fontSize = 13.sp)
            }
        }
        if (catalog.tables.isNotEmpty()) {
            FilledTonalButton(onClick = onAll, shape = CircleShape, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(stringResource(R.string.food_all), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
