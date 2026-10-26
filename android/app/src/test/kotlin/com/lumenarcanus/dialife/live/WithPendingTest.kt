package com.lumenarcanus.dialife.live

import com.lumenarcanus.dialife.data.Treatment
import org.junit.Assert.assertEquals
import org.junit.Test

class WithPendingTest {
    private val a = Treatment("a", 100, insulin = 1.0)
    private val b = Treatment("b", 200, carbs = 20.0)

    @Test
    fun unsentEntryShowsAsPending() {
        val out = listOf(a).withPending(listOf(PendingOp("c", treatment = Treatment("c", 150, carbs = 15.0))))
        assertEquals(listOf("a", "c"), out.map { it.id })
        assertEquals(true, out[1].pending)
    }

    @Test
    fun unsentEditReplacesAndDeleteHides() {
        val ops = listOf(PendingOp("a", treatment = a.copy(insulin = 2.0)), PendingOp("b", delete = true))
        val out = listOf(a, b).withPending(ops)
        assertEquals(listOf("a"), out.map { it.id })
        assertEquals(2.0, out[0].insulin)
    }
}
