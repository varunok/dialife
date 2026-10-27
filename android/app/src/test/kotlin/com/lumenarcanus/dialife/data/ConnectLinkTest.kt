package com.lumenarcanus.dialife.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectLinkTest {
    private val token = "dl_" + "a".repeat(43)

    @Test
    fun parsesLinkFromDlDeviceAdd() {
        // exactly what tools/dl prints (python urlencode)
        val raw = "dialife://connect?server=https%3A%2F%2Fdialife.example.com&token=$token&name=%D0%A2%D0%B0%D1%82%D0%BE"
        val link = parseConnectLink(raw).getOrThrow()
        assertEquals("https://dialife.example.com", link.server)
        assertEquals(token, link.token)
        assertEquals("Тато", link.name)
    }

    @Test
    fun rejectsWrongOrUnsafeLinks() {
        val cases = mapOf(
            "https://dialife.example.com" to LinkError.NotDiaLife,
            "dialife://other?server=https%3A%2F%2Fx.com&token=$token" to LinkError.NotDiaLife,
            "dialife://connect?server=http%3A%2F%2Fdialife.example.com&token=$token" to LinkError.BadServer, // no plain http
            "dialife://connect?server=https%3A%2F%2Fx.com%2Fevil&token=$token" to LinkError.BadServer,
            "dialife://connect?token=$token" to LinkError.BadServer,
            "dialife://connect?server=https%3A%2F%2Fx.com&token=dl_short" to LinkError.BadToken,
            "dialife://connect?server=https%3A%2F%2Fx.com" to LinkError.BadToken,
            "not a url at all" to LinkError.NotDiaLife,
        )
        for ((raw, want) in cases) {
            val err = (parseConnectLink(raw).exceptionOrNull() as? LinkException)?.error
            assertEquals(raw, want, err)
        }
    }

    @Test
    fun normalizesServerForManualEntry() {
        assertEquals("https://dialife.example.com", normalizeServer(" https://dialife.example.com/ "))
        assertEquals("https://x.com:8443", normalizeServer("https://x.com:8443"))
        assertNull(normalizeServer("dialife.example.com"))
        assertNull(normalizeServer("http://dialife.example.com"))
        assertNull(normalizeServer("https://x.com/api"))
        assertTrue(isValidToken(" $token "))
    }

    @Test
    fun webLoginLink() {
        val id = "0123456789abcdef0123456789abcdef"
        val code = "a".repeat(40)
        assertEquals(WebLoginLink(id, code), parseWebLoginLink("dialife://web-login?id=$id&code=$code"))
        assertNull(parseWebLoginLink("dialife://connect?id=$id&code=$code"))
        assertNull(parseWebLoginLink("dialife://web-login?id=$id"))
        assertNull(parseWebLoginLink("dialife://web-login?id=x%27or&code=$code"))
        assertNull(parseWebLoginLink("https://example.com"))
    }
}
