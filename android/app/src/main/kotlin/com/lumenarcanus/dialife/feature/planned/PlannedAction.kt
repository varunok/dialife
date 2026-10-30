package com.lumenarcanus.dialife.feature.planned

import com.lumenarcanus.dialife.data.PlannedDose
import com.lumenarcanus.dialife.data.Treatment

/** What a parent did with a planned dose. */
sealed interface PlannedAction {
    val planned: PlannedDose

    /** Injected: [insulin] is the entry to link, new (to save) or already there. */
    data class Given(override val planned: PlannedDose, val insulin: Treatment, val isNew: Boolean) : PlannedAction
    data class Change(override val planned: PlannedDose, val dose: Double) : PlannedAction
    data class Skip(override val planned: PlannedDose) : PlannedAction
}
