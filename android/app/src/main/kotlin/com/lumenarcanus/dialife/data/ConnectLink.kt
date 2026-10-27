package com.lumenarcanus.dialife.data

import java.net.URI
import java.net.URLDecoder

/** What a `dialife://connect?server=&token=&name=` link (the QR from `dl device-add --qr`) carries. */
data class ConnectLink(val server: String, val token: String, val name: String?)

sealed interface LinkError {
    data object NotDiaLife : LinkError
    data object BadServer : LinkError
    data object BadToken : LinkError
}

private val tokenPattern = Regex("^dl_[0-9A-Za-z]{43}$")

/** Parses a scanned QR or deep link. Pure Kotlin so it is unit-tested on the JVM. */
fun parseConnectLink(raw: String): Result<ConnectLink> {
    val uri = runCatching { URI(raw.trim()) }.getOrNull()
    if (uri == null || uri.scheme != "dialife" || uri.host != "connect") return failure(LinkError.NotDiaLife)
    val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
        val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") // the Charset overload is API 33
    }.toMap()
    val server = normalizeServer(params["server"].orEmpty()) ?: return failure(LinkError.BadServer)
    val token = params["token"].orEmpty()
    if (!tokenPattern.matches(token)) return failure(LinkError.BadToken)
    return Result.success(ConnectLink(server, token, params["name"]?.takeIf { it.isNotBlank() }))
}

/** https only, no path, no trailing slash: "https://dia.example.com". Null if unusable. */
fun normalizeServer(raw: String): String? {
    val uri = runCatching { URI(raw.trim().trimEnd('/')) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "https://${uri.host}$port"
}

fun isValidToken(token: String) = tokenPattern.matches(token.trim())

class LinkException(val error: LinkError) : Exception(error.toString())

private fun failure(e: LinkError) = Result.failure<ConnectLink>(LinkException(e))

/** What a web viewer's QR code carries: `dialife://web-login?id=…&code=…`. */
data class WebLoginLink(val id: String, val code: String)

/** The web-login link in a scanned QR, or null when it is something else. */
fun parseWebLoginLink(raw: String): WebLoginLink? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "dialife" || uri.host != "web-login") return null
    val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
        val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }.toMap()
    val hex = Regex("^[0-9a-f]{20,64}$")
    val id = params["id"]?.takeIf { hex.matches(it) } ?: return null
    val code = params["code"]?.takeIf { hex.matches(it) } ?: return null
    return WebLoginLink(id, code)
}
