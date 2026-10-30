package com.lumenarcanus.dialife.feature.weblogin

import com.lumenarcanus.dialife.data.WebLoginInfo
import com.lumenarcanus.dialife.data.WebLoginLink
import com.lumenarcanus.dialife.data.WebSession

/** The web login screen: a scanned browser waiting for a decision, the last word to the user, the browsers let in. */
data class WebLoginUiState(
    val request: WebLoginRequest? = null,
    val note: WebLoginNote? = null,
    val sessions: List<WebSession> = emptyList()
)

/** A browser's code, scanned and looked up: which browser, from where. */
data class WebLoginRequest(val link: WebLoginLink, val info: WebLoginInfo)

sealed interface WebLoginNote {
    data object NotCode : WebLoginNote
    data object Expired : WebLoginNote
    data object Failed : WebLoginNote
    data object Denied : WebLoginNote
    data class Allowed(val until: Long) : WebLoginNote
}
