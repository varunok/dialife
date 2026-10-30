package com.lumenarcanus.dialife.feature.data

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Treatment

/**
 * The chart as a table: every reading (or one per step) with what was logged
 * around it. Upright, a list grouped by hour; "full screen" turns the phone
 * sideways for one column per kind of data, with totals.
 */
@Composable
fun DataScreen(
    uiState: DataUiState,
    onAction: (DataAction) -> Unit,
    onSaveTreatment: (Treatment) -> Unit,
    onBack: () -> Unit,
    // food has a screen of its own
    onOpenMeal: (Treatment) -> Unit = {},
    // the entry sheet's: "… is giving insulin" on the other phones, food suggestions
    onAnnounceInsulin: (com.lumenarcanus.dialife.data.InsulinType?) -> Unit = {},
    foods: com.lumenarcanus.dialife.core.data.FoodRepository? = null
) {
    val c = DiaTheme.colors
    val units = uiState.units
    val thresholds = uiState.thresholds
    // a tapped entry opens the same sheet as on the chart; food its own screen
    var editing by remember { mutableStateOf<Treatment?>(null) }
    editing?.takeIf { (it.carbs ?: 0.0) > 0 && (it.insulin ?: 0.0) <= 0 && it.glucose == null && it.duration == null }?.let { tr ->
        LaunchedEffect(tr.id) {
            editing = null
            onOpenMeal(tr)
        }
    }
    editing?.takeIf { !((it.carbs ?: 0.0) > 0 && (it.insulin ?: 0.0) <= 0 && it.glucose == null && it.duration == null) }?.let { tr ->
        com.lumenarcanus.dialife.feature.entry.TreatmentSheet(
            com.lumenarcanus.dialife.feature.entry.TreatmentKind.Insulin,
            tr,
            onSave = {
                onSaveTreatment(it)
                editing = null
            },
            onDismiss = { editing = null },
            recent = uiState.recent,
            units = units,
            state = uiState.state,
            myId = uiState.myId,
            onAnnounce = onAnnounceInsulin,
            foods = foods
        )
    }
    val onEdit: (Treatment) -> Unit = { editing = it }
    val page = uiState.page
    val from = uiState.from
    val to = uiState.to
    val h = uiState.history
    val entries = uiState.entries
    val rows = uiState.rows
    val step = uiState.step
    val loading = uiState.loading
    val ctx = LocalContext.current
    val day = if (android.text.format.DateUtils.isToday(to)) {
        stringResource(R.string.data_today)
    } else {
        android.text.format.DateUtils.formatDateTime(
            ctx,
            to,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH
        )
    }
    val window = stringResource(R.string.data_window, day, formatClock(ctx, from), formatClock(ctx, to))

    if (uiState.wide) {
        WideTable(rows, entries, h, from, to, window, units, thresholds, step, {
            onAction(DataAction.Step(it))
        }, onEdit, onClose = { onAction(DataAction.Wide(false)) }, onChart = onBack)
        return
    }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(c.background).safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_chart), stringResource(R.string.data_chart), tint = c.textMuted)
            }
            IconButton(onClick = {
                onAction(DataAction.Wide(true))
            }) { Icon(painterResource(R.drawable.ic_fullscreen), stringResource(R.string.data_wide), tint = c.textMuted) }
        }
        Text(stringResource(R.string.data_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAction(DataAction.Older) }) { Text("‹ " + stringResource(R.string.data_prev), color = c.textMuted) }
            Text(
                window,
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { onAction(DataAction.Newer) }, enabled = page > 0) {
                Text(
                    stringResource(R.string.data_next) + " ›",
                    color = if (page > 0) {
                        c.textMuted
                    } else {
                        c.textFaint
                    }
                )
            }
        }
        // two rows: "Лише записи" and "Поза межами" do not fit beside the step
        Segments(
            listOf(
                DataFilter.All to stringResource(R.string.data_all),
                DataFilter.Entries to stringResource(R.string.data_events),
                DataFilter.Out to stringResource(R.string.data_out)
            ),
            uiState.filter,
            Modifier.fillMaxWidth(),
            fill = true
        ) { onAction(DataAction.Filter(it)) }
        StepSegments(step) { onAction(DataAction.Step(it)) }
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .background(c.surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .border(1.dp, c.line, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeadCell(stringResource(R.string.data_time), timeColumn())
                HeadCell(if (units == Units.MgDl) "mg/dL" else stringResource(R.string.unit_mmol), 74.dp)
                HeadCell(stringResource(R.string.data_delta), 44.dp)
                HeadCell(stringResource(R.string.data_entries), null)
            }
            when {
                loading -> Text(stringResource(R.string.data_loading), color = c.textMuted, modifier = Modifier.padding(14.dp))
                rows.isEmpty() -> Text(stringResource(R.string.data_empty), color = c.textMuted, modifier = Modifier.padding(14.dp))
                else -> {
                    val groups = rows.groupBy { hourOf(it.t) }
                    val readings = h?.sgv
                    LazyColumn(Modifier.fillMaxSize()) {
                        groups.forEach { (hourStart, rs) ->
                            item(key = "h$hourStart") {
                                val vs = readings?.t.orEmpty().indices.filter {
                                    readings!!.t[it] in
                                        maxOf(hourStart, from)..minOf(hourStart + HOUR, to)
                                }.map { readings!!.v[it] }
                                HourHeader(hourStart, vs, units)
                            }
                            items(rs, key = { "r${it.t}" }) { r -> ListRow(r, units, thresholds, onEdit) }
                        }
                    }
                }
            }
        }
    }
}
