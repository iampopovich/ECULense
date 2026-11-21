package io.github.iampopovich.eculense.core.mqtt

/**
 * Bounded outgoing queue. Live values are QoS 0 and only the newest matter: when [maxQos0] are waiting, the oldest
 * QoS 0 message is dropped. QoS 1 messages (discovery, status) are kept up to [maxQos1]; beyond that the oldest QoS 1
 * goes too, so a broker that is down for hours never grows memory. FIFO order is kept across both kinds.
 * Not thread-safe on its own: [MqttClient] guards it with its lock.
 */
class OutgoingQueue(private val maxQos0: Int = 32, private val maxQos1: Int = 64) {
    private val items = ArrayDeque<MqttMessage>()

    /** Messages dropped because the queue was full (since creation). */
    var dropped: Int = 0
        private set

    val size: Int get() = items.size

    fun offer(message: MqttMessage) {
        val limit = if (message.qos == MqttQos.AT_MOST_ONCE) maxQos0 else maxQos1
        if (items.count { it.qos == message.qos } >= limit) {
            val oldest = items.indexOfFirst { it.qos == message.qos }
            if (oldest >= 0) {
                items.removeAt(oldest)
                dropped++
            }
        }
        items.addLast(message)
    }

    fun peek(): MqttMessage? = items.firstOrNull()

    fun removeHead(): MqttMessage? = items.removeFirstOrNull()

    fun clear() = items.clear()

    fun snapshot(): List<MqttMessage> = items.toList()
}

/**
 * Keep alive (MQTT 3.1.1 section 3.1.2.10): the client must send a control packet within every keep-alive period, a
 * PINGREQ when it has nothing else to send; the broker closes the link after 1.5 periods of silence. A client should
 * treat a missing PINGRESP «within a reasonable amount of time» as a dead link — here: one keep-alive period after the
 * PINGREQ. Pure timing so it is tested on virtual time.
 */
object KeepAlive {
    sealed interface Action {
        data object SendPing : Action
        data object Dead : Action
        data class Wait(val ms: Long) : Action
    }

    fun next(nowMs: Long, lastSentMs: Long, pingSentMs: Long?, keepAliveMs: Long): Action {
        if (keepAliveMs <= 0) return Action.Wait(Long.MAX_VALUE)
        if (pingSentMs != null) {
            val left = pingSentMs + keepAliveMs - nowMs
            return if (left <= 0) Action.Dead else Action.Wait(left)
        }
        val left = lastSentMs + keepAliveMs - nowMs
        return if (left <= 0) Action.SendPing else Action.Wait(left)
    }
}

/**
 * Reconnect delay: 1, 2, 4 ... s capped at [maxMs] (60 s). Doubling backoff, as recommended for MQTT clients so a
 * broker restart is not hammered (HiveMQ client defaults: 1 s initial, 2 min max).
 */
object Backoff {
    fun delayMs(attempt: Int, initialMs: Long = 1_000, maxMs: Long = 60_000): Long {
        if (attempt <= 0) return 0
        val shift = (attempt - 1).coerceAtMost(30)
        return (initialMs shl shift).coerceIn(initialMs, maxMs)
    }
}
