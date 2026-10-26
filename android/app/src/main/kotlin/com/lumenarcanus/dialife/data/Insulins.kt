package com.lumenarcanus.dialife.data

enum class InsulinType(val wire: String) {
    Rapid("rapid"), // short-acting, for meals and corrections
    Basal("basal"), // long-acting background insulin
    ;

    companion object {
        /** Entries from before types existed are rapid: that is what was logged then. */
        fun of(wire: String?): InsulinType = entries.firstOrNull { it.wire == wire } ?: Rapid
    }
}

/**
 * Insulins to pick from in settings: originals and their biosimilars, grouped
 * by how they act. Only the type matters to the app; the name is for people.
 */
object Insulins {
    val rapid = listOf(
        "NovoRapid", "Fiasp", "Humalog", "Lyumjev", "Apidra", "Rapilog One",
        "Admelog", "Trurapi", "Kirsty", "Insulin lispro Sanofi",
    )
    val basal = listOf(
        "Lantus", "Toujeo", "Abasaglar", "Semglee", "Rezvoglar", "Basalog",
        "Tresiba", "Levemir",
    )

    fun of(type: InsulinType) = if (type == InsulinType.Basal) basal else rapid
}
