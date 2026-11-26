package com.lumenarcanus.dialife.feature.recheck

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.info.InfoButton
import com.lumenarcanus.dialife.core.ui.BannerButton
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.Recheck
import com.lumenarcanus.dialife.liveRepository

/** "Measure again" on home: the view model, and the glucometer entry to log the check. */
@Composable
fun RecheckSection(onMeasure: () -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel { RecheckViewModel(ctx.liveRepository, DefaultRecheckRepository({ ctx.liveRepository.credentials })) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            vm.userMessageShown()
        }
    }
    uiState.recheck?.let { RecheckCard(it, uiState.due, uiState.units, onMeasure = onMeasure, onClose = { vm.close(it.id) }) }
}

/**
 * Carbs were given for a low. While waiting: when to measure, calm. Due: amber,
 * "measure with the glucometer". Still low: red, "give more by your plan and
 * measure again". Never how much to give.
 */
@Composable
fun RecheckCard(r: Recheck, due: Boolean, units: Units, onMeasure: () -> Unit, onClose: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val stillLow = r.state == "still_low"
    val (ink, fill) = when {
        stillLow -> c.low to c.lowContainer
        due -> c.high to c.highContainer
        else -> c.textMuted to c.surface
    }
    val grams = formatAmount(r.carbs, locale)
    val title = when {
        stillLow -> stringResource(
            R.string.recheck_still_low,
            formatGlucose(r.checkMg, units, locale),
            stringResource(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
        )
        due -> stringResource(R.string.recheck_due)
        else -> stringResource(R.string.recheck_waiting, formatClock(ctx, r.dueAt))
    }
    val text = when {
        stillLow -> stringResource(R.string.recheck_still_low_text)
        due -> stringResource(R.string.recheck_due_text, formatClock(ctx, r.carbsT), grams)
        else -> stringResource(R.string.recheck_text, formatClock(ctx, r.carbsT), grams)
    }
    Column(
        Modifier.fillMaxWidth().background(fill, RoundedCornerShape(18.dp))
            .border(if (due || stillLow) 1.5.dp else 1.dp, if (due || stillLow) ink else c.line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = if (due ||
                    stillLow
                ) {
                    ink
                } else {
                    c.text
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false)
            )
            InfoButton("recheck")
        }
        Text(text, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
        if (due || stillLow) BannerButton(stringResource(R.string.recheck_measure), ink, c.background, onMeasure)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.recheck_close), color = c.text, maxLines = 1)
        }
    }
}
