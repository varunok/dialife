package com.lumenarcanus.dialife.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.ui.theme.DiaTheme

@Composable
internal fun Tiles(state: HomeState, onOpenStats: () -> Unit, onOpenLog: () -> Unit) {
    val tiles = listOf(
        Triple(state.uploaderName ?: stringResource(R.string.tile_uploader_phone), state.phoneBattery?.let { "$it%" } ?: "—", null),
        Triple(stringResource(R.string.tile_time_in_range), state.timeInRange?.let { "$it%" } ?: "—", onOpenStats),
        Triple(
            stringResource(R.string.tile_alarms),
            if (state.alarms ==
                0
            ) {
                stringResource(R.string.tile_alarms_quiet)
            } else {
                pluralStringResource(R.plurals.tile_alarms_active, state.alarms, state.alarms)
            },
            onOpenLog
        )
    )
    // three tiles in a row cut words in half with a large system font: then one per line
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { (label, value, open) -> WideTile(label, value, open) }
        }
        return
    }
    // equal heights even when one label wraps
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        tiles.forEach { (label, value, open) ->
            Tile(label, value, Modifier.weight(1f).let { m -> if (open != null) m.clickable(onClick = open) else m })
        }
    }
}

@Composable
internal fun WideTile(label: String, value: String, onClick: (() -> Unit)?) {
    val c = DiaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(18.dp))
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.textMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Tile(label: String, value: String, modifier: Modifier) {
    val c = DiaTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(c.surface, RoundedCornerShape(18.dp))
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = c.textMuted, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        // "Todo tranquilo" stays whole: up to two lines, shrinking rather than being cut
        androidx.compose.foundation.text.BasicText(
            value,
            style = androidx.compose.material3.LocalTextStyle.current.merge(
                androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            ),
            maxLines = 2,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 20.sp, stepSize = 0.5.sp)
        )
    }
}
