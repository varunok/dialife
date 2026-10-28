package com.lumenarcanus.dialife.live

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lumenarcanus.dialife.MainActivity
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose

/**
 * "Did you forget to log?" on this phone: shown while this person still has to
 * answer, asked again (with what the others said) when the answers differ,
 * re-shown on every server reminder, gone once answered here or anywhere.
 */
class QuestionNotifier(private val app: Context) {
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val shown = mutableMapOf<Long, Int>() // question id -> round on screen

    /** The whole current list: show what asks us, drop what no longer does. */
    fun sync(questions: List<Question>, myId: Long, units: Units) {
        val asking = questions.filter { it.asks(myId) }
        (shown.keys - asking.map { it.id }.toSet()).forEach { cancel(it) }
        asking.filter { shown[it.id] != it.round }.forEach { show(it, units) }
    }

    /** A single change from the stream; a reminder re-shows it even when nothing changed. */
    fun onEvent(q: Question, myId: Long, units: Units) {
        if (q.watch) showWatch(q)
        if (!q.asks(myId)) cancel(q.id)
        else if (q.remind || shown[q.id] != q.round) show(q, units)
    }

    fun cancel(id: Long) {
        shown.remove(id)
        nm.cancel(TAG, id.toInt())
    }

    private fun show(q: Question, units: Units) {
        val locale = app.resources.configuration.locales[0]
        fun g(v: Int) = formatGlucose(v, units, locale)
        val rise = q.kind == "rise"
        var body = questionBody(app, q, units)
        q.previous().takeIf { it.isNotEmpty() }?.let { prev ->
            body += " " + app.getString(R.string.q_again, prev.joinToString(", ") { app.getString(R.string.q_said, it.person, answerText(app, it.answer)) })
        }
        val open = PendingIntent.getActivity(
            app, q.id.toInt(), Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(answer: String, label: Int) = Notification.Action.Builder(
            null, app.getString(label),
            PendingIntent.getBroadcast(
                app, (q.id * 10 + answer.hashCode() % 10).toInt(),
                Intent(app, ActionReceiver::class.java)
                    .putExtra(ActionReceiver.EXTRA_ACTION, ActionReceiver.ANSWER)
                    .putExtra(ActionReceiver.EXTRA_ID, q.id)
                    .putExtra(ActionReceiver.EXTRA_ANSWER, answer),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        // at night it waits quietly in the shade; by day it sounds like any message
        val b = Notification.Builder(app, if (q.quiet) Notifications.CHANNEL_QUESTIONS_NIGHT else Notifications.CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(questionTitle(app, q))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(false)
        if (q.kind == "planned") {
            // answered on the dose's card: "Given" asks how much and when
            b.addAction(Notification.Action.Builder(null, app.getString(R.string.q_btn_open), open).build())
        } else if (q.kind == "double") {
            b.addAction(action("one", R.string.q_btn_one))
            b.addAction(action("two", R.string.q_btn_two))
        } else {
            if (rise) b.addAction(action("food_unknown", R.string.q_btn_food_unknown))
            b.addAction(action("nothing", if (q.kind == "bolus") R.string.q_btn_no_insulin else R.string.q_btn_nothing))
            b.addAction(Notification.Action.Builder(null, app.getString(R.string.q_btn_add), open).build())
        }
        nm.notify(TAG, q.id.toInt(), b.build())
        shown[q.id] = q.round
    }

    private val watched = mutableSetOf<Long>()

    /** "Two doses": once, to everyone, sounding like a message even at night. */
    private fun showWatch(q: Question) {
        if (!watched.add(q.id)) return
        val open = PendingIntent.getActivity(
            app, q.id.toInt(), Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = watchBody(app, q)
        val n = Notification.Builder(app, Notifications.CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(app.getString(R.string.watch_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(WATCH_TAG, q.id.toInt(), n)
    }

    companion object {
        const val TAG = "question"
        private const val WATCH_TAG = "watch"

        /** "Rapilog One: two doses, 2 U and 2 U. Watch for a low until 15:10." */
        fun watchBody(ctx: Context, q: Question): String {
            val doses = q.doses.sortedBy { it.t }
            return ctx.getString(
                R.string.watch_body, doubleInsulin(ctx, q),
                doses.getOrNull(0)?.let { units(ctx, it.units) }.orEmpty(), doses.getOrNull(1)?.let { units(ctx, it.units) }.orEmpty(),
                AlarmText.clock(ctx, q.watchUntil ?: q.to),
            )
        }

        fun doubleInsulin(ctx: Context, q: Question): String = com.lumenarcanus.dialife.ui.treatment.insulinLabel(
            ctx, com.lumenarcanus.dialife.data.InsulinType.of(q.insulin),
        )

        private fun units(ctx: Context, u: Double) =
            com.lumenarcanus.dialife.ui.treatment.formatAmount(u, ctx.resources.configuration.locales[0]) + " " + ctx.getString(R.string.unit_insulin)

        fun questionTitle(ctx: Context, q: Question): String = if (q.kind == "planned") ctx.getString(R.string.q_planned_title, AlarmText.clock(ctx, q.from)) else ctx.getString(
            when (q.kind) { "rise" -> R.string.q_rise_title; "bolus" -> R.string.q_bolus_title; "double" -> R.string.q_double_title; else -> R.string.q_fall_title },
        )

        /** "Glucose rose from 118 to 171 since 13:20", or for a meal without insulin "Food was logged at 19:10…". */
        fun questionBody(ctx: Context, q: Question, units: Units): String {
            val locale = ctx.resources.configuration.locales[0]
            fun g(v: Int) = formatGlucose(v, units, locale)
            return when (q.kind) {
                "double" -> {
                    val (a, b) = q.doses.sortedBy { it.t }.let { (it.getOrNull(0) ?: return "") to (it.getOrNull(1) ?: it[0]) }
                    ctx.getString(
                        R.string.q_double_body, doubleInsulin(ctx, q),
                        units(ctx, a.units), AlarmText.clock(ctx, a.t), a.by, units(ctx, b.units), AlarmText.clock(ctx, b.t), b.by,
                    )
                }
                "bolus" -> ctx.getString(R.string.q_bolus_body, AlarmText.clock(ctx, q.from))
                "planned" -> ctx.getString(R.string.q_planned_body, units(ctx, q.dose ?: 0.0))
                "rise" -> ctx.getString(R.string.q_rise_body, g(q.sgvFrom), g(q.sgvTo), AlarmText.clock(ctx, q.from))
                else -> ctx.getString(R.string.q_fall_body, g(q.sgvFrom), g(q.sgvTo), AlarmText.clock(ctx, q.from))
            }
        }

        fun answerText(ctx: Context, answer: String): String = ctx.getString(
            when (answer) {
                "food" -> R.string.q_ans_food
                "food_unknown" -> R.string.q_ans_food_unknown
                "asleep" -> R.string.q_ans_asleep
                "insulin" -> R.string.q_ans_insulin
                "one" -> R.string.q_ans_one
                "two" -> R.string.q_ans_two
                else -> R.string.q_ans_nothing
            },
        )
    }
}
