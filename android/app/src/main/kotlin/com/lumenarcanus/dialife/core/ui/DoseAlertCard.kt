package com.lumenarcanus.dialife.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.DoseCalc
import java.util.Locale

/** A bright card: red for what must be seen before injecting, amber for a note, green when all agrees. */
@Composable
fun AlertCard(a: DoseCalc.Alert, units: Units, locale: Locale, title: String? = null, text: String? = null) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val u = stringResource(R.string.unit_insulin)
    fun f(x: Double) = formatAmount(x, locale)
    fun g(mg: Int) = formatGlucose(mg, units, locale)
    val (t0, x0) = when (a) {
        is DoseCalc.Alert.MeterOld -> stringResource(R.string.alert_meter_old) to
            stringResource(R.string.alert_meter_old_text, ageText(a.ageMin), a.freshMin)
        is DoseCalc.Alert.NoMeter -> stringResource(R.string.alert_meter_old) to stringResource(R.string.alert_no_meter_text, a.freshMin)
        is DoseCalc.Alert.Low -> stringResource(R.string.alert_low, g(a.mg)) to stringResource(R.string.alert_low_text)
        is DoseCalc.Alert.VeryHigh -> stringResource(R.string.alert_very_high, g(a.mg)) to
            stringResource(R.string.alert_very_high_text, f(a.maxCorr))
        is DoseCalc.Alert.RecentRapid -> stringResource(R.string.alert_recent, f(a.units), formatClock(ctx, a.at)) to
            stringResource(R.string.alert_recent_text)
        is DoseCalc.Alert.GivenLess -> stringResource(R.string.alert_given_less, f(a.dose - a.given)) to
            stringResource(R.string.alert_given_text, f(a.given), f(a.dose))
        is DoseCalc.Alert.GivenMore -> stringResource(R.string.alert_given_more, f(a.given - a.dose)) to
            stringResource(R.string.alert_given_more_text, f(a.given), f(a.dose))
        is DoseCalc.Alert.GivenSame -> stringResource(R.string.alert_given_same) to "${f(a.given)} $u"
        is DoseCalc.Alert.CarbsHigh -> stringResource(R.string.alert_carbs_high, a.carbs) to
            stringResource(R.string.alert_carbs_high_text, a.max)
        is DoseCalc.Alert.CarbsUnusual -> stringResource(R.string.alert_carbs_unusual, a.carbs) to
            stringResource(R.string.alert_carbs_unusual_text, a.usual)
        is DoseCalc.Alert.DoseAbsurd -> stringResource(R.string.alert_dose_absurd, f(a.dose)) to
            stringResource(R.string.alert_dose_absurd_text, f(a.max))
        is DoseCalc.Alert.DoseHigh -> stringResource(R.string.alert_dose_high, f(a.dose)) to
            stringResource(R.string.alert_dose_high_text, f(a.max))
        is DoseCalc.Alert.DoseUnusual -> stringResource(R.string.alert_dose_unusual, f(a.dose)) to
            stringResource(R.string.alert_dose_unusual_text, f(a.usual))
    }
    val head = title ?: t0
    val body = text ?: x0
    val ok = a is DoseCalc.Alert.GivenSame
    val (bg, line, ink) = when {
        ok -> Triple(c.inRangeContainer, c.inRange, c.onInRangeContainer)
        a.bad -> Triple(c.lowContainer, c.low, c.text)
        else -> Triple(c.highContainer, c.high, c.text)
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(14.dp)).border(1.5.dp, line, RoundedCornerShape(14.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (ok) "✓" else "!", color = line, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(head, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = ink, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** "40 min", "5 h", "6 days": how long ago, in a unit that reads at a glance. */
@Composable
internal fun ageText(min: Int): String = when {
    min < 120 -> stringResource(R.string.age_min, min)
    min < 48 * 60 -> stringResource(R.string.age_hours, min / 60)
    else -> stringResource(R.string.age_days, min / (24 * 60))
}
