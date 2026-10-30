package com.lumenarcanus.dialife.feature.weblogin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.parseWebLoginLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Looks up a scanned code, allows or declines it, and keeps the list of browsers let in. */
class WebLoginViewModel(private val repository: WebLoginRepository, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val _uiState = MutableStateFlow(WebLoginUiState())
    val uiState: StateFlow<WebLoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { loadSessions() }
    }

    /** What the camera read: a browser's code is looked up, anything else is said to be no code. */
    fun scanned(raw: String) {
        val link = parseWebLoginLink(raw)
        if (link == null) {
            _uiState.update { it.copy(note = WebLoginNote.NotCode) }
            return
        }
        _uiState.update { it.copy(note = null) }
        viewModelScope.launch {
            repository.inspect(link)
                .onSuccess { info -> _uiState.update { it.copy(request = WebLoginRequest(link, info)) } }
                .onFailure { e -> _uiState.update { it.copy(note = failureNote(e)) } }
        }
    }

    /** Allow the browser for [minutes], or decline it (null). */
    fun decide(minutes: Int?) {
        val link = _uiState.value.request?.link ?: return
        viewModelScope.launch {
            val r = if (minutes == null) repository.reject(link) else repository.approve(link, minutes)
            r.onSuccess {
                val note = if (minutes == null) WebLoginNote.Denied else WebLoginNote.Allowed(now() + minutes * 60_000L)
                _uiState.update { it.copy(request = null, note = note) }
                if (minutes == null) loadSessions() else awaitNewSession()
            }.onFailure { e -> _uiState.update { it.copy(note = failureNote(e)) } }
        }
    }

    fun revoke(id: Long) {
        viewModelScope.launch { repository.revoke(id).onSuccess { loadSessions() } }
    }

    /** While the screen is open: last seen, closed elsewhere. */
    suspend fun keepSessionsFresh() {
        while (true) {
            delay(30_000)
            loadSessions()
        }
    }

    private suspend fun loadSessions() {
        repository.sessions().onSuccess { s -> _uiState.update { it.copy(sessions = s) } }
    }

    // the session exists once the browser fetches the approval, a second or two later
    private suspend fun awaitNewSession() {
        val before = _uiState.value.sessions.map { it.id }.toSet()
        repeat(10) {
            loadSessions()
            if (_uiState.value.sessions.any { it.id !in before }) return
            delay(1500)
        }
    }

    private fun failureNote(e: Throwable) =
        if ((e as? ApiException)?.error is ApiError.Server) WebLoginNote.Expired else WebLoginNote.Failed
}
