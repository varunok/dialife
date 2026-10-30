package com.lumenarcanus.dialife.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.ui.theme.DiaTheme

internal val Periods = listOf(1, 7, 14, 30)

/** Statistics for the doctor and for us: time in range, averages, variability, the typical day. */
@Composable
fun StatsScreen(uiState: StatsUiState, onPick: (Int) -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = DiaTheme.colors
    val units = uiState.units
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
            Spacer(Modifier.weight(1f))
        }
        Text(stringResource(R.string.stats_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        PeriodChips(uiState.days, onPick)
        when (val l = uiState.load) {
            StatsLoad.Loading -> Text(stringResource(R.string.home_loading), color = c.textMuted)
            StatsLoad.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.err_network), color = c.high)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.stats_retry)) }
            }
            is StatsLoad.Ready -> if (l.stats.count == 0) {
                Text(stringResource(R.string.stats_empty), color = c.textMuted)
            } else {
                StatsContent(l.stats, units)
            }
        }
        uiState.meter?.let { MeterCard(it, units) }
    }
}

@Composable
private fun PeriodChips(days: Int, onPick: (Int) -> Unit) {
    val c = DiaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Periods.forEach { d ->
            val selected = d == days
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.stats_days, d, d),
                color = if (selected) c.text else c.textMuted,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onPick(d) })
                    .heightIn(min = 40.dp)
                    .padding(vertical = 11.dp)
            )
        }
    }
}
