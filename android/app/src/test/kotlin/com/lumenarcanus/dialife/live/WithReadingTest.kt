package com.lumenarcanus.dialife.live

import com.lumenarcanus.dialife.data.Bg
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.StateResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WithReadingTest {
    private val day = LiveRepository.HISTORY_MS
    private val now = 10 * day

    private fun state(mills: Long, sgv: Int) = StateResponse(now = mills, bg = Bg(sgv = sgv, mills = mills))

    @Test
    fun appendsNewReadingAndDropsOldOnes() {
        val h = History(now - day, now, Series(listOf(now - day - 1, now - 300_000), listOf(100, 110)))
        val out = h.withReading(state(now + 1, 120))
        assertEquals(listOf(now - 300_000, now + 1), out.sgv.t)
        assertEquals(listOf(110, 120), out.sgv.v)
    }

    @Test
    fun ignoresReadingWeAlreadyHave() {
        val h = History(now - day, now, Series(listOf(now), listOf(100)))
        assertSame(h, h.withReading(state(now, 100)))
    }
}
