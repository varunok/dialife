package com.lumenarcanus.dialife.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes of the server API (.local/notes/api-app.md); glucose is always mg/dL, time unix ms.
// Browsers let into the web viewer.

/** A browser asking to see the data, as the server describes it. */
@Serializable
data class WebLoginInfo(
    val id: String,
    val browser: String,
    val os: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("max_minutes") val maxMinutes: Int = 90 * 24 * 60,
    val ip: String? = null, // where the browser asked from
    // the same address this phone has now: at home, the family's own computer
    @SerialName("same_network") val sameNetwork: Boolean = false
)

/** A browser allowed in: who allowed it, until when, last seen. */
@Serializable
data class WebSession(
    val id: Long,
    val browser: String,
    val os: String,
    val by: String = "",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null
)
