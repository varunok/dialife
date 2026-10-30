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
            com.lumenarcanus.dialife.feature.questions.QuestionNotifier.questionTitle(ctx, q),
            color = c.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            com.lumenarcanus.dialife.feature.questions.QuestionNotifier.questionBody(ctx, q, units),
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
                            com.lumenarcanus.dialife.feature.questions.QuestionNotifier.answerText(ctx, it.answer)
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
        entry?.let { tr ->
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
        if (q.asks(myId)) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (double) {
                    Button(onClick = { onAnswer("one", null) }) { Text(stringResource(R.string.q_btn_one)) }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        onAnswer("two", null)
                    }) { Text(stringResource(R.string.q_btn_two), color = c.text) }
                    return@FlowRow
                }
                // food with no dose near it: the rapid dose before it may have been for it all the same
                val food = treatments.firstOrNull { q.kind == "bolus" && (it.carbs ?: 0.0) > 0 && it.t == q.from }
                val foodIds = treatments.filter { (it.carbs ?: 0.0) > 0 }.map { it.id }.toSet()
                val before = food?.let { f ->
                    treatments.filter {
                        com.lumenarcanus.dialife.data.Meals.isRapid(it) &&
                            it.t in (f.t - 3 * 3_600_000L)..f.t &&
                            (it.mealId == null || it.mealId == f.id || it.mealId !in foodIds)
                    }.maxByOrNull { it.t }
                }
                if (entry != null) {
                    Button(onClick = { onAnswer(if (rise) "food" else "insulin", entry.id) }) { Text(stringResource(R.string.q_btn_yes)) }
                } else if (before != null) {
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
        } else if (q.waiting.isNotEmpty()) {
            Text(stringResource(R.string.q_waiting, q.waiting.joinToString(", ")), color = c.textMuted, fontSize = 13.sp)
        }
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
        Text(com.lumenarcanus.dialife.feature.questions.QuestionNotifier.watchBody(ctx, q), color = c.text, fontSize = 14.sp)
    }
}
