package com.lumenarcanus.dialife.feature.alarms

import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Untrusted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** After a shower an alarm still rings, and says to check with the glucometer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class AlarmTextShowerTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val now = 1_761_400_000_000L
    private val low = AlarmView(id = 1, kind = "low", level = "warn", startedAt = now - 60_000L, sgv = 68)

    private fun state(w: Untrusted?) = StateResponse(now = now, untrusted = w)

    @Test
    fun saysToCheckWithTheGlucometerAfterAShower() {
        val w = Untrusted(from = now - 10 * 60_000L, until = now + 30 * 60_000L)
        val r = AlarmText.reason(ctx, null, low, state(w), now)
        assertTrue(r, r.endsWith(" · After a shower — check with the glucometer"))
        // without a shower, and for a silent uploader (not about the numbers), as before
        assertEquals(AlarmText.reason(ctx, null, low, state(null), now), r.substringBefore(" · After"))
        assertEquals(null, AlarmText.afterShower(ctx, low.copy(kind = "stale"), state(w), now))
        assertEquals(null, AlarmText.afterShower(ctx, low, state(w), w.until))
    }
}
