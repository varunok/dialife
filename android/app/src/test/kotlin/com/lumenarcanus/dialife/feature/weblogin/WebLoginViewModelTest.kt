package com.lumenarcanus.dialife.feature.weblogin

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.WebLoginInfo
import com.lumenarcanus.dialife.data.WebLoginLink
import com.lumenarcanus.dialife.data.WebSession
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class WebLoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = 1_761_400_000_000L
    private val id = "a".repeat(24)
    private val code = "b".repeat(24)
    private val link = "dialife://web-login?id=$id&code=$code"

    private class FakeRepo : WebLoginRepository {
        var inspected: Result<WebLoginInfo> = Result.success(WebLoginInfo("x", "Firefox", "Linux", 0))
        var sessions = listOf<WebSession>()
        val approved = mutableListOf<Pair<WebLoginLink, Int>>()

        override suspend fun inspect(link: WebLoginLink) = inspected

        override suspend fun approve(link: WebLoginLink, minutes: Int): Result<Unit> {
            approved += link to minutes
            sessions = sessions + WebSession(1, "Firefox", "Linux", createdAt = 0, expiresAt = 0)
            return Result.success(Unit)
        }

        override suspend fun reject(link: WebLoginLink) = Result.success(Unit)

        override suspend fun sessions() = Result.success(sessions)

        override suspend fun revoke(id: Long) = Result.success(Unit)
    }

    @Test
    fun anythingButABrowsersCodeIsSaidSo() {
        val vm = WebLoginViewModel(FakeRepo()) { now }
        vm.scanned("https://example.com")
        assertEquals(WebLoginNote.NotCode, vm.uiState.value.note)
        assertNull(vm.uiState.value.request)
    }

    @Test
    fun allowingLetsTheBrowserInUntilTheChosenTime() {
        val repo = FakeRepo()
        val vm = WebLoginViewModel(repo) { now }
        vm.scanned(link)
        assertEquals("Firefox", vm.uiState.value.request?.info?.browser)
        vm.decide(60)
        assertEquals(listOf(WebLoginLink(id, code) to 60), repo.approved)
        assertEquals(WebLoginNote.Allowed(now + 60 * 60_000L), vm.uiState.value.note)
        assertNull(vm.uiState.value.request)
        assertEquals(1, vm.uiState.value.sessions.size)
    }

    @Test
    fun aCodeTheServerNoLongerKnowsHasExpired() {
        val repo = FakeRepo().apply { inspected = Result.failure(ApiException(ApiError.Server(404))) }
        val vm = WebLoginViewModel(repo) { now }
        vm.scanned(link)
        assertEquals(WebLoginNote.Expired, vm.uiState.value.note)
        repo.inspected = Result.failure(ApiException(ApiError.Network))
        vm.scanned(link)
        assertEquals(WebLoginNote.Failed, vm.uiState.value.note)
    }
}
