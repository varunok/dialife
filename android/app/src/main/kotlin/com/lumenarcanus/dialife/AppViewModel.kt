package com.lumenarcanus.dialife

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.core.data.SessionRepository
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.parseConnectLink
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

/** The app's session: connected or not, and a connect link that would replace the connection. */
class AppViewModel(private val sessions: SessionRepository) : ViewModel() {
    val session: StateFlow<Session> = sessions.credentials
        .map { c -> if (c == null) Session.Disconnected else Session.Connected(c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Session.Loading)

    /** A connect link that arrived while this phone is already connected: replace only if confirmed. */
    data class PendingLink(val raw: String, val current: String, val incoming: String?)

    private val _pending = MutableStateFlow<PendingLink?>(null)
    val pending: StateFlow<PendingLink?> = _pending.asStateFlow()

    /** A link to connect with: held for confirmation when connected (true), else it is for connecting now (false). */
    fun holdLink(raw: String): Boolean {
        val current = (session.value as? Session.Connected)?.credentials ?: return false
        _pending.value = PendingLink(raw, current.deviceName, parseConnectLink(raw).getOrNull()?.name)
        return true
    }

    /** The held link, to connect with. */
    fun confirmReplace(): String? = _pending.value?.raw.also { _pending.value = null }

    fun cancelReplace() {
        _pending.value = null
    }

    /** The server stopped accepting the stored key: forget it and say why. */
    fun revoked() {
        viewModelScope.launch { sessions.revoke() }
    }

    fun disconnect() {
        viewModelScope.launch { sessions.disconnect() }
    }
}
