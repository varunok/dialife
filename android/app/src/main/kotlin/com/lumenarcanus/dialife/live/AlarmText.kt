package com.lumenarcanus.dialife.live

import android.content.Context
import android.text.format.DateFormat
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.AlarmEvent
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import java.util.Date

/** The words of an alarm, shared by notifications, the alarm screen and the home banner. */
object AlarmText {
    fun kind(ctx: Context, a: AlarmView): String = ctx.getString(
        when (a.kind) {
            "low" -> R.string.kind_low
            "high" -> R.string.kind_high
            "predicted_low" -> R.string.kind_predicted_low
            "predicted_high" -> R.string.kind_predicted_high
            "stale" -> R.string.kind_stale
            else -> R.string.kind_other
        },
    )

    /** "Низький цукор: 52 mg/dL" */
    fun title(ctx: Context, a: AlarmView, units: Units): String {
        val v = value(ctx, a, units) ?: return kind(ctx, a)
        return ctx.getString(R.string.alarm_title_value, kind(ctx, a), v)
    }

    fun value(ctx: Context, a: AlarmView, units: Units): String? {
        if (a.sgv == null || a.kind == "stale") return null
        val locale = ctx.resources.configuration.locales[0]
        val unit = ctx.getString(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
        return formatGlucose(a.sgv, units, locale) + " " + unit
    }

    /** The xDrip+ phone as a title: the family's own name for it from the server, else a neutral one. */
    fun uploader(ctx: Context, st: com.lumenarcanus.dialife.data.StateResponse?): String =
        st?.uploaderName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.tile_uploader_phone)

    /** The same inside a sentence: the family's name in quotes, or "телефон із xDrip+". */
    fun uploaderInSentence(ctx: Context, st: com.lumenarcanus.dialife.data.StateResponse?): String =
        st?.uploaderName?.takeIf { it.isNotBlank() }?.let { "«$it»" } ?: ctx.getString(R.string.uploader_in_sentence)

    fun clock(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))

    /** Why it rings (again), as the server's reason says. */
    fun reason(ctx: Context, e: AlarmEvent?, a: AlarmView, now: Long = System.currentTimeMillis()): String {
        val was = e?.wasHandledBy?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.someone)
        val since = clock(ctx, a.startedAt)
        return when (e?.reason ?: a.reason) {
            "repeat" -> ctx.getString(R.string.reason_repeat, since)
            "escalated" -> ctx.getString(R.string.reason_escalated, since)
            "worse" -> ctx.getString(R.string.reason_worse, was)
            "ceiling" -> ctx.getString(R.string.reason_ceiling, ((now - a.startedAt) / 60_000).toInt(), was)
            "no_response" -> ctx.getString(R.string.reason_no_response, was)
            "quiet_over" -> ctx.getString(R.string.reason_quiet_over)
            else -> if (a.kind == "stale") {
                val phone = uploaderInSentence(ctx, (ctx.applicationContext as? com.lumenarcanus.dialife.DiaLifeApp)?.live?.live?.value?.state)
                ctx.getString(if (a.level == "urgent") R.string.reason_stale_30 else R.string.reason_stale_15, phone)
            } else {
                ctx.getString(R.string.reason_since, since)
            }
        }
    }

    fun clear(ctx: Context, a: AlarmView): String = ctx.getString(
        when (a.kind) {
            "low" -> R.string.clear_low
            "high" -> R.string.clear_high
            "predicted_low" -> R.string.clear_predicted_low
            "predicted_high" -> R.string.clear_predicted_high
            "stale" -> R.string.clear_stale
            else -> R.string.clear_other
        },
    )

    /** "Зараз 88 mg/dL · тривало 34 хв · вів(ла) Тато" */
    fun clearBody(ctx: Context, e: AlarmEvent, units: Units): String = buildList {
        value(ctx, e.alarm, units)?.let { add(ctx.getString(R.string.clear_now, it)) }
        e.summary?.let { s ->
            add(ctx.resources.getQuantityString(R.plurals.clear_lasted, s.durationMin, s.durationMin))
            when {
                s.manual && !s.resolvedBy.isNullOrBlank() -> add(ctx.getString(R.string.clear_resolved_by, s.resolvedBy))
                !s.handledBy.isNullOrBlank() -> add(ctx.getString(R.string.clear_handled_by, s.handledBy))
            }
        }
    }.joinToString(" · ")

    /** "22 хв, 52 → 58 mg/dL" for a check-in. */
    fun progress(ctx: Context, a: AlarmView, units: Units, now: Long = System.currentTimeMillis()): String {
        val locale = ctx.resources.configuration.locales[0]
        val parts = buildList {
            a.handledAt?.let { add(ctx.resources.getQuantityString(R.plurals.minutes_short, ((now - it) / 60_000).toInt(), ((now - it) / 60_000).toInt())) }
            if (a.handledSgv != null && a.sgv != null) {
                add(formatGlucose(a.handledSgv, units, locale) + " → " + formatGlucose(a.sgv, units, locale))
            }
        }
        return parts.joinToString(", ")
    }
}
