package com.lumenarcanus.dialife.feature.connect

import com.lumenarcanus.dialife.core.data.SessionRepository
import com.lumenarcanus.dialife.core.data.UploaderTokenException
import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val token = "dl_" + "A".repeat(43)

    private class FakeSessions : SessionRepository {
        var result: Result<Unit> = Result.success(Unit)
        val connected = mutableListOf<Pair<String, String>>()
        override val credentials = flowOf<Credentials?>(null)
        override val revoked = MutableStateFlow(false)

        override suspend fun connect(server: String, token: String): Result<Unit> {
            connected += server to token
            return result
        }

        override suspend fun revoke() {
            revoked.value = true
        }

        override suspend fun disconnect() {}
    }

    @Test
    fun aLinkIsCheckedBeforeAnyoneIsAsked() {
        val sessions = FakeSessions()
        val vm = ConnectViewModel(sessions)
        vm.onLink("https://example.com")
        assertEquals(ConnectFailure.NotDiaLife, vm.uiState.value.failure)
        vm.onLink("dialife://connect?server=http://x.example.com&token=$token")
        assertEquals(ConnectFailure.BadServer, vm.uiState.value.failure)
        vm.onManual("https://dia.example.com", "nope")
        assertEquals(ConnectFailure.BadToken, vm.uiState.value.failure)
        assertEquals(emptyList<Pair<String, String>>(), sessions.connected)
    }

    @Test
    fun aGoodLinkConnects() {
        val sessions = FakeSessions()
        val vm = ConnectViewModel(sessions)
        vm.onLink("dialife://connect?server=https://dia.example.com&token=$token")
        assertEquals(listOf("https://dia.example.com" to token), sessions.connected)
        assertEquals(ConnectUiState(), vm.uiState.value)
    }

    @Test
    fun theServersAnswerIsTold() {
        val sessions = FakeSessions()
        val vm = ConnectViewModel(sessions)
        sessions.result = Result.failure(UploaderTokenException())
        vm.onManual("https://dia.example.com", token)
        assertEquals(ConnectFailure.UploaderToken, vm.uiState.value.failure)
        sessions.result = Result.failure(ApiException(ApiError.Server(502)))
        vm.onManual("https://dia.example.com", token)
        assertEquals(ConnectUiState(failure = ConnectFailure.Server, serverCode = 502), vm.uiState.value)
    }

    @Test
    fun aRevokedKeyIsSaid() {
        val sessions = FakeSessions()
        val vm = ConnectViewModel(sessions)
        sessions.revoked.value = true
        assertEquals(ConnectFailure.Revoked, vm.uiState.value.failure)
    }
}
