package com.lumenarcanus.dialife.ui.meal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.Wait
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * When to start eating after a rapid dose: the server's suggestion by the sugar,
 * a countdown, and a warning when the dose covers more than the food entered
 * (logged since the dose plus this form). Nothing here blocks or records. On
 * home it goes once the food is logged, unless that food is less than the dose covers.
 */
@Composable
fun WaitCard(wait: Wait, draft: Int, ratio: Int, units: Units, now: Long, home: Boolean = false) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val eatNow = wait.reason == "low" || wait.reason == "falling"
    val total = (wait.eatFrom - wait.doseAt).coerceAtLeast(1)
    val left = (wait.eatFrom - now).coerceAtLeast(0)
    val leftMin = ceil(left / 60_000.0).toInt()
    val food = (wait.foodG + draft).roundToInt()
    val covers = wait.coversG.roundToInt()
    // over a unit's worth of insulin with no food for it, once some food is entered
    val more = food > 0 && covers - food > ratio
    if (home && food > 0 && !more) return // on home, the food is in: nothing left to wait for
    val waitMin = (total / 60_000).toInt()

    val (ink, fill) = when {
        eatNow -> c.low to c.lowContainer
        more -> c.high to c.highContainer
        left == 0L -> c.inRange to c.inRangeContainer
        else -> c.insulin to c.insulinContainer
    }
    val title = when {
        eatNow -> stringResource(R.string.wait_eat_now)
        more -> stringResource(R.string.wait_more, covers, food)
        left == 0L -> stringResource(R.string.wait_go)
        else -> stringResource(R.string.wait_from, AlarmText.clock(ctx, wait.eatFrom))
    }
    val sgv = wait.sgv?.let { formatGlucose(it, units, locale) }
    val sub = when {
        wait.reason == "low" -> stringResource(R.string.wait_why_low, sgv ?: "")
        wait.reason == "falling" -> stringResource(R.string.wait_why_falling, sgv ?: "")
        more -> (if (left > 0) stringResource(R.string.wait_from, AlarmText.clock(ctx, wait.eatFrom)) + ". " else "") + stringResource(R.string.wait_more_check)
        left == 0L -> stringResource(R.string.wait_ago, ((now - wait.doseAt) / 60_000).toInt())
        wait.reason == "high" -> stringResource(R.string.wait_why_high, waitMin)
        wait.reason == "unseen" -> stringResource(R.string.wait_why_unseen, waitMin)
        else -> stringResource(R.string.wait_why_range, waitMin)
    }

    Column(
        Modifier.fillMaxWidth().background(fill, RoundedCornerShape(18.dp)).border(1.dp, ink, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                val track = c.line
                Canvas(Modifier.size(56.dp)) {
                    val w = 5.dp.toPx()
                    val box = Size(size.width - w, size.height - w)
                    val at = Offset(w / 2, w / 2)
                    drawArc(track, 0f, 360f, false, at, box, style = Stroke(w))
                    if (!eatNow && left > 0) drawArc(ink, -90f, 360f * left / total, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
                }
                Text(
                    when { eatNow -> "!"; left > 0 -> "$leftMin′"; else -> "✓" },
                    color = ink, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = c.textMuted, fontSize = 13.sp)
            }
        }
        val facts = listOfNotNull(
            stringResource(R.string.wait_fact_dose, formatUnits(wait.units, locale), AlarmText.clock(ctx, wait.doseAt)),
            sgv?.let { stringResource(R.string.wait_fact_sugar, it) },
            stringResource(R.string.wait_fact_food, food),
        )
        Text(facts.joinToString("  ·  "), color = c.textMuted, fontSize = 12.sp)
        Text(stringResource(R.string.wait_note), color = c.textFaint, fontSize = 11.5.sp)
    }
}

private fun formatUnits(u: Double, locale: java.util.Locale): String =
    if (u % 1.0 == 0.0) u.toInt().toString() else String.format(locale, "%.1f", u)
