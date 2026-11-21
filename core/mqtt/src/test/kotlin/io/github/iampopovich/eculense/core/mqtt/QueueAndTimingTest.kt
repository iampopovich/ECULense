package io.github.iampopovich.eculense.core.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

/** Queue bounds, keep-alive timing on virtual time, reconnect backoff. */
class QueueAndTimingTest {
    private fun q0(n: Int) = MqttMessage("s", "v$n")
    private fun q1(n: Int) = MqttMessage("c", "c$n", MqttQos.AT_LEAST_ONCE, retain = true)

    @Test
    fun `full QoS 0 drops the oldest QoS 0, QoS 1 kept up to its own limit`() {
        val q = OutgoingQueue(maxQos0 = 2, maxQos1 = 2)
        q.offer(q1(1)); q.offer(q0(1)); q.offer(q0(2)); q.offer(q0(3))
        assertEquals(listOf("c1", "v2", "v3"), q.snapshot().map { it.payload.decodeToString() })
        assertEquals(1, q.dropped)
        q.offer(q1(2)); q.offer(q1(3))
        assertEquals(listOf("v2", "v3", "c2", "c3"), q.snapshot().map { it.payload.decodeToString() })
        assertEquals(2, q.dropped)
    }

    @Test
    fun `keep alive pings after one idle period and declares the link dead one period after an unanswered ping`() {
        val k = 60_000L
        assertEquals(KeepAlive.Action.Wait(60_000), KeepAlive.next(nowMs = 0, lastSentMs = 0, pingSentMs = null, keepAliveMs = k))
        assertEquals(KeepAlive.Action.Wait(1), KeepAlive.next(59_999, 0, null, k))
        assertEquals(KeepAlive.Action.SendPing, KeepAlive.next(60_000, 0, null, k))
        // any packet sent restarts the period
        assertEquals(KeepAlive.Action.Wait(30_000), KeepAlive.next(60_000, 30_000, null, k))
        assertEquals(KeepAlive.Action.Wait(1_000), KeepAlive.next(119_000, 60_000, pingSentMs = 60_000, keepAliveMs = k))
        assertEquals(KeepAlive.Action.Dead, KeepAlive.next(120_000, 60_000, 60_000, k))
        assertEquals(KeepAlive.Action.Wait(Long.MAX_VALUE), KeepAlive.next(1_000_000, 0, null, 0))
    }

    @Test
    fun `backoff doubles from 1 s and is capped at 60 s`() {
        assertEquals(listOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..8).map { Backoff.delayMs(it) })
        assertEquals(60_000L, Backoff.delayMs(500))
    }
}
