package com.lumenarcanus.dialife.data

import kotlinx.serialization.Serializable

/** docs/METHODS.md in short, per feature and language: the server's /web/methods.json, shared with the web viewer. */
@Serializable
data class Methods(val doc: String = "", val guidelines: String = "", val sections: Map<String, Map<String, MethodText>> = emptyMap()) {
    /** The section [id] in [lang], English when that language is missing; null when unknown. */
    fun section(id: String, lang: String): MethodText? = sections[id]?.let { it[lang] ?: it["en"] }

    /** The full text of section [id], with its sources. */
    fun link(id: String): String = "$doc#$id"
}

/** One feature: what it is, what it is based on, and what is our own, from our observations. */
@Serializable
data class MethodText(val title: String, val short: String, val based: List<String> = emptyList(), val ours: List<String> = emptyList())
