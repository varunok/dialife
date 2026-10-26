package com.lumenarcanus.dialife

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.LinkError
import com.lumenarcanus.dialife.data.LinkException
import com.lumenarcanus.dialife.data.isValidToken
import com.lumenarcanus.dialife.data.normalizeServer
import com.lumenarcanus.dialife.data.parseConnectLink
import com.lumenarcanus.dialife.live.LiveService
import com.lumenarcanus.dialife.widget.GlucoseWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Session {
    data object Loading : Session
    data object Disconnected : Session
    data class Connected(val credentials: Credentials) : Session
}

enum class ConnectFailure { NotDiaLife, BadServer, BadToken, Network, Unauthorized, Server, UploaderToken, Scanner, Revoked }

data class ConnectUiState(val checking: Boolean = false, val failure: ConnectFailure? = null, val serverCode: Int? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CredentialStore(app)
    private val api = DiaLifeApi()

    val session: StateFlow<Session> = store.credentials
        .map { c -> if (c == null) Session.Disconnected else Session.Connected(c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Session.Loading)

    private val _connect = MutableStateFlow(ConnectUiState())
    val connect: StateFlow<ConnectUiState> = _connect.asStateFlow()

    /** A connect link that arrived while this phone is already connected: replace only if confirmed. */
    data class PendingLink(val raw: String, val current: String, val incoming: String?)

    private val _pending = MutableStateFlow<PendingLink?>(null)
    val pending: StateFlow<PendingLink?> = _pending.asStateFlow()

    /** A scanned QR or a dialife://connect deep link. */
    fun onLink(raw: String) {
        val current = (session.value as? Session.Connected)?.credentials
        if (current != null) {
            _pending.value = PendingLink(raw, current.deviceName, parseConnectLink(raw).getOrNull()?.name)
            return
        }
        applyLink(raw)
    }

    fun confirmReplace() {
        val p = _pending.value ?: return
        _pending.value = null
        applyLink(p.raw)
    }

    fun cancelReplace() {
        _pending.value = null
    }

    private fun applyLink(raw: String) {
        parseConnectLink(raw)
            .onSuccess { connect(it.server, it.token) }
            .onFailure { e ->
                val f = when ((e as? LinkException)?.error) {
                    LinkError.BadServer -> ConnectFailure.BadServer
                    LinkError.BadToken -> ConnectFailure.BadToken
                    else -> ConnectFailure.NotDiaLife
                }
                _connect.value = ConnectUiState(failure = f)
            }
    }

    fun onManual(serverRaw: String, tokenRaw: String) {
        val server = normalizeServer(serverRaw)
        when {
            server == null -> _connect.value = ConnectUiState(failure = ConnectFailure.BadServer)
            !isValidToken(tokenRaw) -> _connect.value = ConnectUiState(failure = ConnectFailure.BadToken)
            else -> connect(server, tokenRaw.trim())
        }
    }

    fun onScannerFailed() {
        _connect.value = ConnectUiState(failure = ConnectFailure.Scanner)
    }

    /** Checks the token against the server before keeping it. */
    private fun connect(server: String, token: String) {
        _connect.value = ConnectUiState(checking = true)
        viewModelScope.launch {
            api.me(server, token)
                .onSuccess { me ->
                    if (me.role == "uploader") {
                        _connect.value = ConnectUiState(failure = ConnectFailure.UploaderToken)
                    } else {
                        store.save(Credentials(server, token, me.name, me.role, me.displayUnits, me.id))
                        _connect.value = ConnectUiState()
                    }
                }
                .onFailure { e ->
                    _connect.value = when (val err = (e as? ApiException)?.error) {
                        ApiError.Unauthorized -> ConnectUiState(failure = ConnectFailure.Unauthorized)
                        is ApiError.Server -> ConnectUiState(failure = ConnectFailure.Server, serverCode = err.code)
                        else -> ConnectUiState(failure = ConnectFailure.Network)
                    }
                }
        }
    }

    /** The server stopped accepting the stored key: forget it and say why. */
    fun revoked() {
        _connect.value = ConnectUiState(failure = ConnectFailure.Revoked)
        LiveService.stop(getApplication())
        viewModelScope.launch { store.clear() }
    }

    fun disconnect() {
        LiveService.stop(getApplication())
        viewModelScope.launch {
            store.clear()
            GlucoseWidget.refresh(getApplication())
        }
    }
}
