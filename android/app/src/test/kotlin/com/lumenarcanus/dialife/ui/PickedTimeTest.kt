package com.lumenarcanus.dialife.ui

import com.lumenarcanus.dialife.ui.treatment.daysAgo
import com.lumenarcanus.dialife.ui.treatment.pickedTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PickedTimeTest {
    private fun at(day: Int, h: Int, m: Int) =
        LocalDateTime.of(2026, 10, day, h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun earlierTodayStaysToday() {
        assertEquals(at(27, 12, 15), pickedTime(at(27, 14, 0), 12, 15))
        assertEquals(at(27, 14, 0), pickedTime(at(27, 14, 0), 14, 0))
    }

    @Test
    fun aTimeStillToComeIsRefused() {
        // at 14:00 "14:30" used to land on yesterday without a word
        assertNull(pickedTime(at(27, 14, 0), 14, 30))
        assertNull(pickedTime(at(27, 14, 0), 23, 0))
    }

    @Test
    fun lateEveningJustAfterMidnightIsYesterday() {
        assertEquals(at(26, 23, 40), pickedTime(at(27, 0, 20), 23, 40))
        assertEquals(at(26, 13, 0), pickedTime(at(27, 0, 20), 13, 0))
    }

    @Test
    fun days() {
        assertEquals(0, daysAgo(at(27, 0, 5), at(27, 23, 0)))
        assertEquals(1, daysAgo(at(26, 23, 40), at(27, 0, 20)))
        assertEquals(3, daysAgo(at(24, 12, 0), at(27, 12, 0)))
    }
}
