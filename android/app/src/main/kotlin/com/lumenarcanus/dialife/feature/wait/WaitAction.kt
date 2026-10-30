package com.lumenarcanus.dialife.feature.wait

/** What a parent does with the wait; each closes it on every phone. */
sealed interface WaitAction {
    val doseId: String
    val kind: String // the server's name for it

    data class Eaten(override val doseId: String) : WaitAction {
        override val kind get() = "wait"
    }

    data class CloseWarning(override val doseId: String) : WaitAction {
        override val kind get() = "more"
    }
}
