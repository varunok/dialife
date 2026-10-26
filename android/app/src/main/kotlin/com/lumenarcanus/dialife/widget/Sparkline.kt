package com.lumenarcanus.dialife.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Thresholds

/**
 * The last hours as a picture: Glance widgets cannot draw, so the chart is a
 * bitmap. Band of the family's range, the line coloured by state, "now" at the right.
 */
internal object Sparkline {
    const val HOURS = 3

    fun render(history: History?, th: Thresholds, now: Long, dark: Boolean, w: Int = 600, h: Int = 200): Bitmap? {
        val from = now - HOURS * 3_600_000L
        val ts = history?.sgv?.t.orEmpty()
        val vs = history?.sgv?.v.orEmpty()
        val idx = ts.indices.filter { ts[it] in from..now }
        if (idx.size < 2) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // scale to the data, not to 40–400: a small widget must show the movement
        val minV = idx.minOf { vs[it] }
        val maxV = idx.maxOf { vs[it] }
        val mid = (minV + maxV) / 2f
        val half = maxOf((maxV - minV) / 2f + 30f, 45f)
        val lo = mid - half
        val hi = mid + half
        val pad = h * 0.06f
        fun x(t: Long) = (t - from).toFloat() / (now - from) * (w - 6f)
        fun y(v: Int) = pad + (h - 2 * pad) * (1 - (v - lo) / (hi - lo))

        val inRange = if (dark) 0xFF4FD1C5.toInt() else 0xFF0B7D73.toInt()
        val high = if (dark) 0xFFF5A524.toInt() else 0xFFB86E00.toInt()
        val low = if (dark) 0xFFFF5C7A.toInt() else 0xFFD12E52.toInt()
        val faint = if (dark) 0x33FFFFFF else 0x33000000

        cv.clipRect(0, 0, w, h) // thresholds outside the window simply fall off the picture
        cv.drawRect(0f, y(th.high), w.toFloat(), y(th.low), Paint().apply { color = inRange; alpha = 28 })
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f, 10f), 0f)
        }
        cv.drawLine(0f, y(th.high), w.toFloat(), y(th.high), dash.apply { color = high; alpha = 140 })
        cv.drawLine(0f, y(th.low), w.toFloat(), y(th.low), dash.apply { color = low; alpha = 150 })

        // the line, one colour per segment; a gap over 15 min breaks it
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun colour(v: Int) = when {
            v < th.low -> low
            v > th.high -> high
            else -> inRange
        }
        for (k in 1 until idx.size) {
            val a = idx[k - 1]
            val b = idx[k]
            if (ts[b] - ts[a] > 15 * 60_000L) continue
            line.color = colour(vs[b])
            val p = Path().apply { moveTo(x(ts[a]), y(vs[a])); lineTo(x(ts[b]), y(vs[b])) }
            cv.drawPath(p, line)
        }
        val lastI = idx.last()
        cv.drawCircle(x(ts[lastI]), y(vs[lastI]), 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour(vs[lastI]) })
        // hour marks
        val tick = Paint().apply { color = faint; strokeWidth = 2f }
        var t = (from / 3_600_000L + 1) * 3_600_000L
        while (t < now) {
            cv.drawLine(x(t), 0f, x(t), h.toFloat(), tick)
            t += 3_600_000L
        }
        return bmp
    }
}
