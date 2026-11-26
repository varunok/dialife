package com.lumenarcanus.dialife.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.MeterLag

/** The server measures nothing below this many checks (internal/meter). */
private const val MIN_LAG_CHECKS = 10

/** How far the sensor runs behind the glucometer: the measured delay, how sure it is, and what the comparisons use. */
@Composable
internal fun LagCard(lag: MeterLag) {
    val c = DiaTheme.colors
    Card {
        Text(stringResource(R.string.lag_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.lag_about), color = c.textMuted, fontSize = 13.sp)
        val min = lag.min
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (min != null) stringResource(R.string.lag_value, min) else "—",
                color = c.text,
                fontSize = 40.sp,
                fontWeight = FontWeight.Light
            )
            Column {
                Text(
                    if (min != null && lag.lo != null && lag.hi != null) {
                        stringResource(R.string.lag_range, lag.lo, lag.hi, lag.n)
                    } else {
                        stringResource(R.string.lag_none, lag.n, MIN_LAG_CHECKS)
                    },
                    color = c.textFaint,
                    fontSize = 12.sp
                )
            }
        }
        Text(
            if (lag.source == "measured") {
                stringResource(R.string.lag_measured)
            } else {
                stringResource(R.string.lag_published, lag.used)
            },
            color = if (lag.source == "measured") c.textMuted else c.text,
            fontSize = 13.sp
        )
    }
}
