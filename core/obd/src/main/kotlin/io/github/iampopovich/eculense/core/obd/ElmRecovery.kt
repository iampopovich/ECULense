package io.github.iampopovich.eculense.core.obd

/** What to do when the adapter answers a command with an error (A2, ELM327 datasheet "Restoring Order"). */
enum class RecoveryAction {
    /** Bus/protocol errors: close the protocol (ATPC), set the known protocol again, retry. */
    PROTOCOL_CLOSE,

    /** Receive/buffer errors: warm start (ATWS), the standard settings and the known protocol again, retry. */
    WARM_START,

    /** The adapter reset itself on low voltage: full re-init (ATZ + settings + protocol), retry. */
    FULL_REINIT,

    /** Nothing to recover ('?', STOPPED, unknown text). */
    NONE,
    ;

    companion object {
        private val protocolErrors = listOf("UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "FB ERROR", "BUS INIT")
        private val receiveErrors = listOf("BUFFER FULL", "DATA ERROR", "RX ERROR", "ERR")

        /** The action for an error line of the adapter. */
        fun forError(message: String): RecoveryAction {
            val m = message.uppercase()
            return when {
                m.contains("LV RESET") -> FULL_REINIT
                protocolErrors.any { m.contains(it) } -> PROTOCOL_CLOSE
                receiveErrors.any { m.contains(it) } -> WARM_START
                else -> NONE
            }
        }
    }
}

/** At most [max] recoveries per [windowMs] (so a dead bus does not turn into a storm of resets). */
internal class RecoveryBudget(
    private val max: Int = 3,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val used = ArrayDeque<Long>()

    fun tryAcquire(): Boolean {
        val now = clock()
        while (used.isNotEmpty() && now - used.first() >= windowMs) used.removeFirst()
        if (used.size >= max) return false
        used.addLast(now)
        return true
    }
}
