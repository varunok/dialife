package com.lumenarcanus.dialife.feature.home

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Bg
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Untrusted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeStateTest {
    private val now = 1_761_400_000_000L
    private val min = 60_000L

    private fun home(untrusted: Untrusted?, at: Long) =
        HomeUi(state = StateResponse(now = now, bg = Bg(sgv = 92, mills = now), untrusted = untrusted), now = at).toHomeState(Units.MgDl)!!

    /** After a shower the home screen knows the number is not to be believed, until the window ends on this phone's clock. */
    @Test
    fun aShowerMakesTheReadingUntrustedUntilItsWindowEnds() {
        val w = Untrusted(from = now - 20 * min, until = now + 25 * min)
        assertEquals(w, home(w, now).untrusted)
        assertNull(home(w, now + 25 * min).untrusted)
        assertNull(home(null, now).untrusted)
    }
}
