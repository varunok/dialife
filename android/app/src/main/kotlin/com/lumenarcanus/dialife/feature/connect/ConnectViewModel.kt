package com.lumenarcanus.dialife.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.core.data.SessionRepository
import com.lumenarcanus.dialife.core.data.UploaderTokenException
import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.LinkError
import com.lumenarcanus.dialife.data.LinkException
import com.lumenarcanus.dialife.data.isValidToken
import com.lumenarcanus.dialife.data.normalizeServer
import com.lumenarcanus.dialife.data.parseConnectLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Connects this phone: from a scanned or opened link, or a server and key typed in. */
class ConnectViewModel(private val sessions: SessionRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.revoked.filter { it }.collect { _uiState.value = ConnectUiState(failure = ConnectFailure.Revoked) }
        }
    }

    /** A scanned QR or a dialife://connect deep link. */
    fun onLink(raw: String) {
        parseConnectLink(raw)
            .onSuccess { connect(it.server, it.token) }
            .onFailure { e ->
                val f = when ((e as? LinkException)?.error) {
                    LinkError.BadServer -> ConnectFailure.BadServer
                    LinkError.BadToken -> ConnectFailure.BadToken
                    else -> ConnectFailure.NotDiaLife
                }
                _uiState.value = ConnectUiState(failure = f)
            }
    }

    fun onManual(serverRaw: String, tokenRaw: String) {
        val server = normalizeServer(serverRaw)
        when {
            server == null -> _uiState.value = ConnectUiState(failure = ConnectFailure.BadServer)
            !isValidToken(tokenRaw) -> _uiState.value = ConnectUiState(failure = ConnectFailure.BadToken)
            else -> connect(server, tokenRaw.trim())
        }
    }

    fun onScannerFailed() {
        _uiState.value = ConnectUiState(failure = ConnectFailure.Scanner)
    }

    private fun connect(server: String, token: String) {
        _uiState.value = ConnectUiState(checking = true)
        viewModelScope.launch {
            sessions.connect(server, token)
                .onSuccess { _uiState.value = ConnectUiState() }
                .onFailure { e ->
                    _uiState.value = if (e is UploaderTokenException) {
                        ConnectUiState(failure = ConnectFailure.UploaderToken)
                    } else {
                        failureOf(e)
                    }
                }
        }
    }

    private fun failureOf(e: Throwable) = when (val err = (e as? ApiException)?.error) {
        ApiError.Unauthorized -> ConnectUiState(failure = ConnectFailure.Unauthorized)
        is ApiError.Server -> ConnectUiState(failure = ConnectFailure.Server, serverCode = err.code)
        else -> ConnectUiState(failure = ConnectFailure.Network)
    }
}
