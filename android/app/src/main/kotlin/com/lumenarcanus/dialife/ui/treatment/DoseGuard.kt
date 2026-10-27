package com.lumenarcanus.dialife.ui.treatment

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.InsulinIntent
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.ui.home.Origin
import com.lumenarcanus.dialife.ui.home.originOf
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import kotlin.math.abs

// Two doses of one insulin too close together: one injection logged twice, or
// two parents at once. The server uses the same windows for what xDrip+ sends
// (questions.DoubleRapid, RecentRapid, DoubleBasal).
private const val SAME_RAPID_MS = 20 * 60_000L
private const val RECENT_RAPID_MS = 3 * 3_600_000L
private const val SAME_BASAL_MS = 16 * 3_600_000L

enum class DoseLevel { Same, Recent }

data class NearDose(val dose: Treatment, val gapMs: Long, val level: DoseLevel)

/**
 * The dose of the same insulin nearest to [at] (before or after: an entry may
 * be back-dated), if it is close enough to matter. Same: most likely this very
 * injection, ask before saving. Recent: rapid within 3 h, only shown.
 */
fun nearDose(entries: List<Treatment>, type: InsulinType, at: Long, editingId: String?): NearDose? {
    val near = entries
        .filter { it.id != editingId && (it.insulin ?: 0.0) > 0 && InsulinType.of(it.insulinType) == type }
        .minByOrNull { abs(it.t - at) } ?: return null
    val gap = abs(near.t - at)
    val level = when {
        type == InsulinType.Basal && gap < SAME_BASAL_MS -> DoseLevel.Same
        type == InsulinType.Rapid && gap < SAME_RAPID_MS -> DoseLevel.Same
        type == InsulinType.Rapid && gap < RECENT_RAPID_MS -> DoseLevel.Recent
        else -> return null
    }
    return NearDose(near, gap, level)
}

/** "8 min", "1 h 10 min", "9 h". */
fun gapText(ctx: Context, ms: Long): String {
    val min = (ms / 60_000).toInt()
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> ctx.getString(R.string.gap_min, m)
        m == 0 -> ctx.getString(R.string.gap_h, h)
        else -> ctx.getString(R.string.gap_h_min, h, m)
    }
}

/** Who logged it: a parent's phone, or the xDrip+ phone by the family's name for it. */
internal fun whoLogged(ctx: Context, tr: Treatment, st: StateResponse?): String = when (val o = originOf(tr)) {
    is Origin.Here -> o.who.ifBlank { ctx.getString(R.string.dose_by_me) }
    else -> st?.uploaderName ?: "xDrip+"
}

/** "Rapilog One" from this phone's settings, else "rapid" / "long-acting". */
fun insulinLabel(ctx: Context, type: InsulinType): String =
    Prefs.nameOf(type) ?: ctx.getString(if (type == InsulinType.Basal) R.string.ins_basal else R.string.ins_rapid)

fun doseText(ctx: Context, tr: Treatment): String {
    val locale = ctx.resources.configuration.locales[0]
    return formatAmount(tr.insulin ?: 0.0, locale) + " " + ctx.getString(R.string.unit_insulin)
}

/** In the form: someone else has the insulin form open right now. */
@Composable
fun IntentWarning(others: List<InsulinIntent>, now: Long) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        others.forEach { o ->
            Text(intentText(ctx, o, now), color = c.high, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.intent_check), color = c.text, fontSize = 13.sp)
    }
}

/** "Тато вводить Rapilog One · відкрито 2 хв тому". */
fun intentText(ctx: Context, o: InsulinIntent, now: Long): String {
    val min = ((now - o.since) / 60_000).toInt().coerceAtLeast(0)
    return ctx.getString(R.string.intent_giving, o.person, insulinLabel(ctx, InsulinType.of(o.insulin)), gapText(ctx, min * 60_000L))
}

/** The previous rapid dose, 20 min to 3 h away: a fact next to the amount, nothing to confirm. */
@Composable
fun RecentDoseNote(near: NearDose, st: StateResponse?) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    Text(
        stringResource(R.string.dose_recent, doseText(ctx, near.dose), AlarmText.clock(ctx, near.dose.t), whoLogged(ctx, near.dose, st), gapText(ctx, near.gapMs)),
        color = c.textMuted, fontSize = 13.sp,
    )
}

/** Saving a dose next to one already logged: the same injection, or really another one? */
@Composable
fun SameDoseDialog(near: NearDose, type: InsulinType, st: StateResponse?, onSame: () -> Unit, onNew: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d = near.dose
    val basal = type == InsulinType.Basal
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(if (basal) R.string.dose_basal_title else R.string.dose_same_title)) },
        text = {
            Text(
                stringResource(
                    if (basal) R.string.dose_basal_body else R.string.dose_same_body,
                    insulinLabel(ctx, type), doseText(ctx, d), AlarmText.clock(ctx, d.t), whoLogged(ctx, d, st), gapText(ctx, near.gapMs),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onNew) { Text(stringResource(if (basal) R.string.dose_basal_new else R.string.dose_new)) } },
        dismissButton = { TextButton(onClick = onSame) { Text(stringResource(if (basal) R.string.dose_basal_skip else R.string.dose_same)) } },
    )
}
