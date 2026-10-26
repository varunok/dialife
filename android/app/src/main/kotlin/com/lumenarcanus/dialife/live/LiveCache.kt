package com.lumenarcanus.dialife.live

import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.StateResponse
import kotlinx.serialization.Serializable
import java.io.File

/** What the screen showed last, so the app opens with data even before the network answers. */
@Serializable
data class LiveSnapshot(
    val server: String,
    val device: String,
    val state: StateResponse? = null,
    val history: History? = null,
    val lastEventId: String? = null,
)

class LiveCache(dir: File) {
    private val file = File(dir, "live.json")

    fun load(server: String, device: String): LiveSnapshot? = try {
        DiaLifeApi.json.decodeFromString<LiveSnapshot>(file.readText())
            .takeIf { it.server == server && it.device == device } // another connection's data is not ours
    } catch (e: Exception) {
        null
    }

    /** The saved snapshot whoever it belongs to (one connection per phone anyway). */
    fun loadAny(): LiveSnapshot? = try {
        DiaLifeApi.json.decodeFromString<LiveSnapshot>(file.readText())
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun save(s: LiveSnapshot) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(DiaLifeApi.json.encodeToString(LiveSnapshot.serializer(), s))
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }
}
