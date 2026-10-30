package com.lumenarcanus.dialife.feature.questions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.Meals
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Treatment

/**
 * "Did you forget to log?" Everyone answers; whoever has answered sees who is
 * still to; when the answers differ, the card shows what each said and asks again.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun QuestionCard(
    q: Question,
    myId: Long,
    treatments: List<Treatment>,
    units: Units,
    onAnswer: (answer: String, treatmentId: String?) -> Unit,
    onLog: () -> Unit,
    // a dose said to be for the meal: saved linked to it
    onLink: (Treatment) -> Unit = {}
) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun g(v: Int) = formatGlucose(v, units, locale)
    val rise = q.kind == "rise"
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.highContainer, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            QuestionNotifier.questionTitle(ctx, q),
            color = c.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            QuestionNotifier.questionBody(ctx, q, units),
            color = c.text,
            fontSize = 14.sp
        )
        q.previous().takeIf { it.isNotEmpty() }?.let { prev ->
            Text(
                stringResource(
                    R.string.q_again,
                    prev.joinToString(", ") {
                        ctx.getString(
                            R.string.q_said,
                            it.person,
                            QuestionNotifier.answerText(ctx, it.answer)
                        )
                    }
                ),
                color = c.high,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        val double = q.kind == "double"
        // an entry someone made for it: the others only confirm it
        val entry = q.treatmentId?.takeIf { !double }?.let { id -> treatments.firstOrNull { it.id == id } }
        entry?.let { EnteredFor(it) }
        if (q.asks(myId)) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnswerButtons(q, entry, treatments, onAnswer, onLog, onLink)
            }
        } else if (q.waiting.isNotEmpty()) {
            Text(stringResource(R.string.q_waiting, q.waiting.joinToString(", ")), color = c.textMuted, fontSize = 13.sp)
        }
    }
}

/** "45 г at 12:40 (Мама)": the entry someone made for it; the others only confirm it. */
@Composable
private fun EnteredFor(tr: Treatment) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val what = listOfNotNull(
        tr.carbs?.takeIf { it > 0 }?.let {
            formatAmount(it, locale) + " " +
                stringResource(R.string.unit_carbs)
        },
        tr.insulin?.takeIf { it > 0 }?.let {
            formatAmount(it, locale) + " " +
                stringResource(R.string.unit_insulin)
        }
    ).joinToString(" · ")
    Text(
        stringResource(R.string.q_entered, what, formatClock(ctx, tr.t), tr.enteredBy.orEmpty()),
        color = c.textMuted,
        fontSize = 13.sp
    )
}

/**
 * Food with no dose near it (a "bolus" question): the rapid dose up to 3 h before it
 * that is not for another meal may have been for it all the same. Null: no such dose.
 */
internal fun doseBeforeFood(q: Question, treatments: List<Treatment>): Pair<Treatment, Treatment>? {
    val food = treatments.firstOrNull { q.kind == "bolus" && (it.carbs ?: 0.0) > 0 && it.t == q.from } ?: return null
    val foodIds = treatments.filter { (it.carbs ?: 0.0) > 0 }.map { it.id }.toSet()
    val before = treatments.filter {
        Meals.isRapid(it) &&
            it.t in (food.t - 3 * 3_600_000L)..food.t &&
            (it.mealId == null || it.mealId == food.id || it.mealId !in foodIds)
    }.maxByOrNull { it.t } ?: return null
    return food to before
}

/** The answers this question takes, from this phone. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.AnswerButtons(
    q: Question,
    entry: Treatment?,
    treatments: List<Treatment>,
    onAnswer: (answer: String, treatmentId: String?) -> Unit,
    onLog: () -> Unit,
    onLink: (Treatment) -> Unit
) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val rise = q.kind == "rise"
    if (q.kind == "double") {
        Button(onClick = { onAnswer("one", null) }) { Text(stringResource(R.string.q_btn_one)) }
        androidx.compose.material3.OutlinedButton(onClick = {
            onAnswer("two", null)
        }) { Text(stringResource(R.string.q_btn_two), color = c.text) }
        return
    }
    val linked = doseBeforeFood(q, treatments)
    if (entry != null) {
        Button(onClick = { onAnswer(if (rise) "food" else "insulin", entry.id) }) { Text(stringResource(R.string.q_btn_yes)) }
    } else if (linked != null) {
        val (food, before) = linked
        Button(onClick = {
            onLink(before.copy(mealId = food.id, insulinRole = "meal"))
            onAnswer("insulin", before.id)
        }) {
            Text(
                stringResource(
                    R.string.q_btn_was_for_it,
                    formatClock(ctx, before.t),
                    formatAmount(before.insulin ?: 0.0, locale)
                )
            )
        }
        androidx.compose.material3.OutlinedButton(onClick = onLog) {
            Text(stringResource(R.string.q_btn_insulin), color = c.text)
        }
    } else {
        Button(onClick = onLog) { Text(stringResource(if (rise) R.string.q_btn_food else R.string.q_btn_insulin)) }
    }
    if (rise) {
        androidx.compose.material3.OutlinedButton(onClick = {
            onAnswer("food_unknown", null)
        }) { Text(stringResource(R.string.q_btn_food_unknown), color = c.text) }
    }
    // asleep she eats nothing: the rise is not food, and no more questions about it for a while
    if (rise) {
        androidx.compose.material3.OutlinedButton(onClick = {
            onAnswer("asleep", null)
        }) { Text(stringResource(R.string.q_btn_asleep), color = c.text) }
    }
    androidx.compose.material3.OutlinedButton(onClick = { onAnswer("nothing", null) }) {
        Text(
            stringResource(
                if (q.kind == "bolus") {
                    R.string.q_btn_no_insulin
                } else {
                    R.string.q_btn_nothing
                }
            ),
            color = c.text
        )
    }
}

/** Two doses were given: everyone watches for a low until the insulin wears off. */
@Composable
internal fun WatchCard(q: Question) {
    val c = DiaTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(c.lowContainer, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.watch_title), color = c.low, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(QuestionNotifier.watchBody(ctx, q), color = c.text, fontSize = 14.sp)
    }
}
