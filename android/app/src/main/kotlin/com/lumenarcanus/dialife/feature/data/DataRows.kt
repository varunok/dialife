package com.lumenarcanus.dialife.feature.data

import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Thresholds
import com.lumenarcanus.dialife.data.Treatment

enum class EntryKind { Rapid, Basal, Carbs, Meter, Activity, Note, Suspect }

/** One thing logged or measured, as the table shows it. */
data class Entry(
    val t: Long,
    val kind: EntryKind,
    val treatment: Treatment? = null, // editable when set
    val meter: Int? = null,
    val src: String? = null, // a glucometer reading from xDrip+: "Roche 00:00:5E:…" or "manual"
    val question: Question? = null
)

/**
 * A row: one reading (5 minutes), or a clock-aligned interval from [t] to [end]
 * with the mean of its readings and their range. [span]: a suspect rise or
 * fall that began earlier runs on through it.
 */
data class DataRow(
    val t: Long,
    val sgv: Int?,
    val delta: Int?,
    val entries: List<Entry>,
    val end: Long? = null,
    val min: Int? = null,
    val max: Int? = null,
    val span: Boolean = false
)

internal const val MIN = 60_000L
internal const val HOUR = 60 * MIN

/** Everything in [from, to], one entry per treatment part, glucometer check and suspect mark, oldest first. */
fun entriesOf(h: History?, questions: List<Question>, from: Long, to: Long): List<Entry> {
    val out = mutableListOf<Entry>()
    h?.treatments.orEmpty().filter { it.t in from..to }.forEach { tr ->
        val ins = (tr.insulin ?: 0.0) > 0
        when {
            tr.glucose != null -> out += Entry(tr.t, EntryKind.Meter, tr, meter = tr.glucose)
            tr.duration != null -> out += Entry(tr.t, EntryKind.Activity, tr)
            else -> {
                if (ins) {
                    out +=
                        Entry(tr.t, if (InsulinType.of(tr.insulinType) == InsulinType.Basal) EntryKind.Basal else EntryKind.Rapid, tr)
                }
                if ((tr.carbs ?: 0.0) > 0) out += Entry(tr.t, EntryKind.Carbs, tr)
                if (!ins && (tr.carbs ?: 0.0) <= 0 && !tr.notes.isNullOrBlank()) out += Entry(tr.t, EntryKind.Note, tr)
            }
        }
    }
    h?.mbg?.let { m ->
        m.t.indices.filter { m.t[it] in from..to }.forEach {
            out +=
                Entry(m.t[it], EntryKind.Meter, meter = m.v[it], src = m.d.getOrNull(it))
        }
    }
    questions.filter { it.from in from..to && (it.state == "unknown" || it.state == "open") }.forEach {
        out +=
            Entry(it.from, EntryKind.Suspect, question = it)
    }
    return out.sortedBy { it.t }
}

/**
 * Rows newest first. 5 minutes: every reading, each with the entries since the
 * one before. Longer: intervals on the clock (10:00–10:15…), every reading in
 * them averaged, every entry in the interval it falls in.
 */
fun rowsOf(
    h: History?,
    entries: List<Entry>,
    from: Long,
    to: Long,
    stepMin: Int,
    zone: java.util.TimeZone = java.util.TimeZone.getDefault()
): List<DataRow> {
    val ts = h?.sgv?.t.orEmpty()
    val vs = h?.sgv?.v.orEmpty()
    val idx = ts.indices.filter { ts[it] in from..to }
    val suspects = entries.filter { it.kind == EntryKind.Suspect }.mapNotNull { it.question }
    if (stepMin <= 5) {
        if (idx.isEmpty()) return entries.reversed().map { DataRow(it.t, null, null, listOf(it)) }
        val rows = idx.reversed().map { i ->
            val delta = if (i > 0 && ts[i] - ts[i - 1] <= 10 * MIN) vs[i] - vs[i - 1] else null
            DataRow(ts[i], vs[i], delta, emptyList(), span = suspects.any { q -> q.from < ts[i] && q.to >= ts[i] })
        }.toMutableList()
        for (e in entries) {
            // the oldest row at or after the entry; past the newest reading: the newest row
            val k = rows.indexOfLast { it.t >= e.t }.takeIf { it >= 0 } ?: 0
            rows[k] = rows[k].copy(entries = rows[k].entries + e)
        }
        return rows
    }
    val step = stepMin * MIN
    fun bucket(t: Long): Long {
        val off = zone.getOffset(t)
        return Math.floorDiv(t + off, step) * step - off
    }
    val values = idx.groupBy({ bucket(ts[it]) }, { vs[it] })
    val logged = entries.groupBy { bucket(it.t) }
    return (values.keys + logged.keys).distinct().sortedDescending().map { b ->
        val v = values[b].orEmpty()
        DataRow(
            t = b,
            end = b + step,
            sgv = if (v.isEmpty()) null else Math.round(v.average()).toInt(),
            min = v.minOrNull(),
            max = v.maxOrNull(),
            delta = if (v.size > 1) v.last() - v.first() else null,
            entries = logged[b].orEmpty(),
            span = suspects.any { q -> q.from < b && q.to >= b }
        )
    }
}

enum class DataFilter { All, Entries, Out }

/** The rows the filter keeps: all, those with something logged, or those out of range. */
fun List<DataRow>.filtered(filter: DataFilter, th: Thresholds): List<DataRow> = filter { r ->
    when (filter) {
        DataFilter.All -> true
        DataFilter.Entries -> r.entries.isNotEmpty()
        DataFilter.Out ->
            (r.min ?: r.sgv)?.let { it < th.low } == true ||
                (r.max ?: r.sgv)?.let { it > th.high } == true
    }
}
