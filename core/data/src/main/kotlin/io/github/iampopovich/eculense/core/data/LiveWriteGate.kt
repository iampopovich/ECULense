package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.isSimulated

/**
 * The one switch every writer of live-connection data asks before persisting to the active car (DTC and readiness
 * history, trip events, health checks, alert history, automatic recordings). A demo or replayed link has no real car
 * behind it: its results are shown on screen, never stored. Only a recording the user starts by hand is written.
 */
fun interface LiveWriteGate {
    /** true = the current link belongs to a real car and its data may be stored. */
    fun allowed(): Boolean

    companion object {
        val ALWAYS = LiveWriteGate { true }

        fun of(controller: ConnectionController) = LiveWriteGate { !controller.state.value.isSimulated }
    }
}
