package com.lumenarcanus.dialife.feature.connect

enum class ConnectFailure { NotDiaLife, BadServer, BadToken, Network, Unauthorized, Server, UploaderToken, Scanner, Revoked }

/** The connect screen: checking a key with the server, or why the last try failed. */
data class ConnectUiState(val checking: Boolean = false, val failure: ConnectFailure? = null, val serverCode: Int? = null)
