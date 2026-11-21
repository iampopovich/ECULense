package io.github.iampopovich.eculense.core.mqtt

import io.github.iampopovich.eculense.core.model.LocalNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Broker connection settings. [tls] = TLS through the platform [SSLSocketFactory] (system trust store, host name
 * verified); plain TCP is accepted only for local-network hosts ([LocalNetwork], the same rule as the AI «Other
 * server»). [will] = Last Will, published by the broker when the link dies without DISCONNECT.
 */
data class MqttConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val clientId: String,
    val username: String? = null,
    val password: String? = null,
    val keepAliveSec: Int = 60,
    val will: MqttMessage? = null,
    val connectTimeoutMs: Int = 5_000,
    val ackTimeoutMs: Long = 10_000,
    /** QoS 1 re-sends (with DUP) before the link is treated as dead and reopened. */
    val maxRetries: Int = 2,
    val backoffInitialMs: Long = 1_000,
    val backoffMaxMs: Long = 60_000,
) {
    override fun toString() = "MqttConfig($host:$port tls=$tls user=${username != null} keepAlive=$keepAliveSec)" // never the password
}

/** Outcome of opening a link (CONNECT → CONNACK); the settings screen shows each one differently. */
sealed interface ConnectResult {
    data object Accepted : ConnectResult

    /** CONNACK with a refusal code (bad user name/password, not authorized, server unavailable ...). */
    data class Refused(val code: ConnackCode) : ConnectResult

    /** No TCP connection (timeout, refused port, unknown host, network down) or no CONNACK in time. */
    data class Unreachable(val reason: String) : ConnectResult

    /** TLS handshake failed (certificate not trusted, wrong host name, broker speaks plain TCP ...). */
    data class TlsFailed(val reason: String) : ConnectResult

    /** The broker answered with something that is not a valid CONNACK. */
    data class Protocol(val reason: String) : ConnectResult

    /** Plain TCP to an internet host: refused before connecting (TLS is required outside the local network). */
    data object PlainTextNotAllowed : ConnectResult

    val badCredentials: Boolean
        get() = this is Refused && (code == ConnackCode.BAD_USERNAME_OR_PASSWORD || code == ConnackCode.NOT_AUTHORIZED)

    /** Retrying cannot help (wrong settings): the client stops instead of reconnecting. */
    val fatal: Boolean
        get() = this is PlainTextNotAllowed || this is TlsFailed ||
            (this is Refused && code != ConnackCode.SERVER_UNAVAILABLE)
}

sealed interface MqttState {
    data object Idle : MqttState
    data class Connecting(val attempt: Int) : MqttState
    data object Connected : MqttState

    /** Lost or failed link, next attempt in [retryInMs]. */
    data class Waiting(val attempt: Int, val error: ConnectResult, val retryInMs: Long) : MqttState

    /** Stopped for good until the settings change ([ConnectResult.fatal]). */
    data class Failed(val error: ConnectResult) : MqttState
}

/** Opens the TCP (or TLS) socket. The default uses the device's default network — never the OBD adapter's Wi-Fi. */
fun interface SocketConnector {
    fun open(host: String, port: Int, tls: Boolean, timeoutMs: Int): Socket
}

object DefaultSocketConnector : SocketConnector {
    override fun open(host: String, port: Int, tls: Boolean, timeoutMs: Int): Socket {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            plain.tcpNoDelay = true
            if (!tls) return plain
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            // SSLSocket does not check the host name by itself (only HTTPS stacks do).
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            return ssl
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw e
        }
    }
}

/** Publish-only MQTT. [start] keeps a link open (reconnecting with backoff), [publish] queues, [stop] ends it. */
interface MqttPublisher {
    val state: StateFlow<MqttState>

    /** [birth] = messages sent first after every (re)connect (online status, discovery configs). */
    fun start(config: MqttConfig, birth: () -> List<MqttMessage> = { emptyList() })

    fun publish(message: MqttMessage)

    /** Sends [final] (e.g. retained «offline»), then DISCONNECT (so the broker does not fire the Last Will). */
    suspend fun stop(final: MqttMessage? = null)

    /** One-shot CONNECT → CONNACK → DISCONNECT for «Check connection». */
    suspend fun probe(config: MqttConfig): ConnectResult
}

/**
 * Own MQTT 3.1.1 client (no dependency): clean session, one QoS 1 message in flight at a time (PUBACK awaited
 * [MqttConfig.ackTimeoutMs], re-sent with DUP up to [MqttConfig.maxRetries]), PINGREQ by [KeepAlive], reconnect by
 * [Backoff], bounded [OutgoingQueue]. A QoS 1 message not acknowledged before the link drops stays queued and is sent
 * again on the next link (clean session: the broker forgot it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttClient(
    private val scope: CoroutineScope,
    private val connector: SocketConnector = DefaultSocketConnector,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    maxQos0: Int = 32,
    maxQos1: Int = 64,
) : MqttPublisher {
    private val lock = Any()
    private val queue = OutgoingQueue(maxQos0, maxQos1)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow<MqttState>(MqttState.Idle)
    override val state: StateFlow<MqttState> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var stopRequest: StopRequest? = null
    private var nextId = 0

    private class StopRequest(val final: MqttMessage?)

    /** Messages waiting (tests, diagnostics). */
    fun queued(): List<MqttMessage> = synchronized(lock) { queue.snapshot() }

    val dropped: Int get() = synchronized(lock) { queue.dropped }

    override fun start(config: MqttConfig, birth: () -> List<MqttMessage>) {
        job?.cancel()
        stopRequest = null
        job = scope.launch(io) { run(config, birth) }
    }

    override fun publish(message: MqttMessage) {
        synchronized(lock) { queue.offer(message) }
        wake.trySend(Unit)
    }

    override suspend fun stop(final: MqttMessage?) {
        val running = job ?: return
        stopRequest = StopRequest(final)
        wake.trySend(Unit)
        // A connected session sends final + DISCONNECT and returns; a waiting/connecting loop is cancelled.
        if (_state.value != MqttState.Connected) running.cancel()
        if (withTimeoutOrNull(STOP_TIMEOUT_MS) { running.join() } == null) running.cancel()
        job = null
        synchronized(lock) { queue.clear() }
        _state.value = MqttState.Idle
    }

    override suspend fun probe(config: MqttConfig): ConnectResult = withContext(io) {
        val (result, socket) = open(config)
        socket?.let {
            runCatching { it.getOutputStream().apply { write(MqttCodec.DISCONNECT_PACKET); flush() } }
            runCatching { it.close() }
        }
        result
    }

    private suspend fun run(config: MqttConfig, birth: () -> List<MqttMessage>) {
        var attempt = 0
        while (stopRequest == null) {
            _state.value = MqttState.Connecting(attempt + 1)
            val (result, socket) = open(config)
            if (socket == null) {
                if (result.fatal) {
                    log("mqtt: connect failed for good: $result")
                    _state.value = MqttState.Failed(result)
                    return
                }
                attempt++
                val wait = Backoff.delayMs(attempt, config.backoffInitialMs, config.backoffMaxMs)
                log("mqtt: connect failed ($result), retry in $wait ms")
                _state.value = MqttState.Waiting(attempt, result, wait)
                delay(wait)
                continue
            }
            attempt = 0
            _state.value = MqttState.Connected
            val error: ConnectResult = try {
                session(socket, config, birth())
                return // stopped
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ConnectResult.Unreachable(e.message ?: e::class.java.simpleName)
            } finally {
                runCatching { socket.close() }
            }
            attempt = 1
            val wait = Backoff.delayMs(attempt, config.backoffInitialMs, config.backoffMaxMs)
            log("mqtt: link lost ($error), reconnect in $wait ms")
            _state.value = MqttState.Waiting(attempt, error, wait)
            delay(wait)
        }
    }

    /** TCP/TLS + CONNECT + CONNACK. A non-null socket only for [ConnectResult.Accepted]. */
    private fun open(config: MqttConfig): Pair<ConnectResult, Socket?> {
        if (!config.tls && !LocalNetwork.isLocalHost(config.host)) return ConnectResult.PlainTextNotAllowed to null
        val socket = try {
            connector.open(config.host, config.port, config.tls, config.connectTimeoutMs)
        } catch (e: SSLException) {
            return ConnectResult.TlsFailed(e.message ?: "TLS handshake failed") to null
        } catch (e: IOException) {
            return ConnectResult.Unreachable(e.message ?: e::class.java.simpleName) to null
        }
        val result = try {
            socket.soTimeout = config.connectTimeoutMs
            val out = socket.getOutputStream()
            out.write(MqttCodec.connect(config.clientId, config.keepAliveSec, true, config.username, config.password, config.will))
            out.flush()
            val code = MqttCodec.parseConnack(MqttCodec.readPacket(socket.getInputStream()))
            if (code == ConnackCode.ACCEPTED) ConnectResult.Accepted else ConnectResult.Refused(code)
        } catch (e: MqttProtocolException) {
            ConnectResult.Protocol(e.message ?: "malformed CONNACK")
        } catch (e: SSLException) {
            ConnectResult.TlsFailed(e.message ?: "TLS failed")
        } catch (_: SocketTimeoutException) {
            ConnectResult.Unreachable("no CONNACK within ${config.connectTimeoutMs} ms")
        } catch (_: EOFException) {
            ConnectResult.Protocol("connection closed before CONNACK")
        } catch (e: IOException) {
            ConnectResult.Unreachable(e.message ?: e::class.java.simpleName)
        }
        if (result != ConnectResult.Accepted) {
            runCatching { socket.close() }
            return result to null
        }
        socket.soTimeout = 0
        return result to socket
    }

    private class InFlight(val message: MqttMessage, val id: Int, var sentAtMs: Long, var tries: Int, val fromBirth: Boolean)

    private suspend fun session(socket: Socket, config: MqttConfig, birthMessages: List<MqttMessage>) = coroutineScope {
        val input: InputStream = socket.getInputStream()
        val out: OutputStream = socket.getOutputStream()
        val incoming = Channel<MqttCodec.Packet>(Channel.UNLIMITED)
        val reader = launch(io) {
            try {
                while (true) incoming.send(MqttCodec.readPacket(input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                incoming.close(e)
            }
        }
        val birth = ArrayDeque(birthMessages)
        val keepMs = config.keepAliveSec * 1_000L
        var lastSent = clock()
        var pingSent: Long? = null
        var inFlight: InFlight? = null

        fun write(bytes: ByteArray) {
            out.write(bytes)
            out.flush()
            lastSent = clock()
        }

        fun handle(packet: MqttCodec.Packet) {
            when (packet.type) {
                MqttCodec.PUBACK -> {
                    val id = MqttCodec.parsePuback(packet)
                    val f = inFlight
                    if (f != null && f.id == id) {
                        if (f.fromBirth) birth.removeFirstOrNull() else synchronized(lock) { if (queue.peek() === f.message) queue.removeHead() }
                        inFlight = null
                    }
                }
                MqttCodec.PINGRESP -> pingSent = null
                else -> log("mqtt: ignored packet type ${packet.type}")
            }
        }

        fun closed(cause: Throwable?): Nothing = throw (cause as? IOException ?: IOException("reader stopped", cause))

        try {
            while (true) {
                stopRequest?.let { stop ->
                    stop.final?.let { write(MqttCodec.publish(MqttMessage(it.topic, it.payload, MqttQos.AT_MOST_ONCE, it.retain))) }
                    write(MqttCodec.DISCONNECT_PACKET)
                    return@coroutineScope
                }
                // incoming packets (non-blocking drain)
                while (true) {
                    val r = incoming.tryReceive()
                    if (r.isClosed) closed(r.exceptionOrNull())
                    handle(r.getOrNull() ?: break)
                }
                val now = clock()
                when (val k = KeepAlive.next(now, lastSent, pingSent, keepMs)) {
                    KeepAlive.Action.Dead -> throw IOException("no PINGRESP within ${config.keepAliveSec} s")
                    KeepAlive.Action.SendPing -> {
                        write(MqttCodec.PINGREQ_PACKET)
                        pingSent = clock()
                    }
                    is KeepAlive.Action.Wait -> Unit
                }
                // QoS 1 in flight: re-send with DUP after the ack timeout, give up after maxRetries
                inFlight?.let { f ->
                    if (now - f.sentAtMs >= config.ackTimeoutMs) {
                        if (f.tries > config.maxRetries) throw IOException("no PUBACK for packet ${f.id}")
                        write(MqttCodec.publish(f.message, f.id, dup = true))
                        f.sentAtMs = clock()
                        f.tries++
                    }
                }
                // next message: birth first, then the queue
                if (inFlight == null) {
                    val fromBirth = birth.isNotEmpty()
                    val next = if (fromBirth) birth.first() else synchronized(lock) { queue.peek() }
                    if (next != null) {
                        if (next.qos == MqttQos.AT_MOST_ONCE) {
                            write(MqttCodec.publish(next))
                            if (fromBirth) birth.removeFirst() else synchronized(lock) { if (queue.peek() === next) queue.removeHead() }
                        } else {
                            nextId = nextId % 0xFFFF + 1
                            write(MqttCodec.publish(next, nextId))
                            inFlight = InFlight(next, nextId, clock(), 1, fromBirth)
                        }
                        continue
                    }
                }
                val t = clock()
                val keepWait = (KeepAlive.next(t, lastSent, pingSent, keepMs) as? KeepAlive.Action.Wait)?.ms ?: 0L
                val ackWait = inFlight?.let { (it.sentAtMs + config.ackTimeoutMs - t).coerceAtLeast(0) } ?: Long.MAX_VALUE
                val waitMs = minOf(keepWait, ackWait, MAX_IDLE_WAIT_MS).coerceAtLeast(1)
                select<Unit> {
                    wake.onReceive { }
                    incoming.onReceiveCatching { r ->
                        if (r.isClosed) closed(r.exceptionOrNull())
                        r.getOrNull()?.let(::handle)
                    }
                    onTimeout(waitMs) { }
                }
            }
        } finally {
            reader.cancel()
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 3_000L
        const val MAX_IDLE_WAIT_MS = 1_000L
    }
}
