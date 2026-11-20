package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Who produced a log entry: the app (TX), the adapter (RX), bytes dropped to keep in sync (DROP), a link event (NOTE). */
enum class TrafficDirection { TX, RX, DROP, NOTE }

/**
 * One exchange step. [text] is the escaped raw text ([TrafficText.escape]) with VINs masked; [durationMs] is the time
 * from the command to the end of its reply (RX only).
 */
data class TrafficEntry(
    val timestampMs: Long,
    val direction: TrafficDirection,
    val text: String,
    val durationMs: Long? = null,
)

/**
 * In-memory log of the traffic with the adapter, for bug reports from the car and for replay without it.
 * Bounded (oldest entries are dropped); nothing is written to disk until [export] is called by the user.
 */
interface ObdTrafficLog {
    val enabled: StateFlow<Boolean>

    /** Changes on every new entry or clear; UIs re-read [snapshot] when it changes. */
    val revision: StateFlow<Long>

    fun setEnabled(enabled: Boolean)
    fun record(direction: TrafficDirection, raw: ByteArray, durationMs: Long? = null)
    fun note(text: String)
    fun snapshot(): List<TrafficEntry>
    fun clear()

    /** Plain-text export readable by people and by [ReplayTransport]. */
    fun export(zone: ZoneId = ZoneId.systemDefault()): String = TrafficText.export(snapshot(), zone)
}

/** Ring buffer of the last [capacity] entries. Thread-safe. */
class RingTrafficLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    enabled: Boolean = true,
) : ObdTrafficLog {
    private val entries = ArrayDeque<TrafficEntry>()
    private val lock = Any()
    private val _enabled = MutableStateFlow(enabled)
    private val _revision = MutableStateFlow(0L)
    private var lastCommand = ""
    private val knownVins = mutableSetOf<String>()

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    override fun record(direction: TrafficDirection, raw: ByteArray, durationMs: Long?) {
        if (!_enabled.value) return
        synchronized(lock) {
            var text = String(raw, Charsets.ISO_8859_1)
            when (direction) {
                TrafficDirection.TX -> lastCommand = text.replace(" ", "").trim().uppercase()
                TrafficDirection.RX, TrafficDirection.DROP -> if (lastCommand.startsWith("0902")) {
                    VinMask.vinFrom0902(text)?.let { knownVins += it }
                    text = VinMask.mask0902Reply(text)
                }
                TrafficDirection.NOTE -> Unit
            }
            add(TrafficEntry(clock(), direction, TrafficText.escape(VinMask.maskVins(text, knownVins)), durationMs))
        }
    }

    override fun note(text: String) {
        if (!_enabled.value) return
        synchronized(lock) { add(TrafficEntry(clock(), TrafficDirection.NOTE, TrafficText.escape(text))) }
    }

    private fun add(entry: TrafficEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
        _revision.value = _revision.value + 1
    }

    override fun snapshot(): List<TrafficEntry> = synchronized(lock) { entries.toList() }

    override fun clear() {
        synchronized(lock) {
            entries.clear()
            knownVins.clear()
            _revision.value = _revision.value + 1
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 5_000
    }
}

/** Escaping and the export format: `timestamp<TAB>DIR<TAB>duration|-<TAB>escaped text`, '#' lines are comments. */
object TrafficText {
    const val HEADER = "# ECULense adapter traffic log v1"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    /** CR → \r, LF → \n, backslash → \\, '<' and non-printables → <XX>. */
    fun escape(text: String): String = buildString(text.length + 8) {
        for (ch in text) {
            val c = ch.code
            when {
                ch == '\r' -> append("\\r")
                ch == '\n' -> append("\\n")
                ch == '\\' -> append("\\\\")
                ch == '<' || c !in 0x20..0x7E -> append("<%02X>".format(c and 0xFF))
                else -> append(ch)
            }
        }
    }

    fun unescape(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\\' && i + 1 < text.length -> {
                    when (text[i + 1]) {
                        'r' -> out.write(0x0D)
                        'n' -> out.write(0x0A)
                        else -> out.write(text[i + 1].code)
                    }
                    i += 2
                }
                ch == '<' && i + 3 < text.length && text[i + 3] == '>' -> {
                    out.write(text.substring(i + 1, i + 3).toInt(16))
                    i += 4
                }
                else -> {
                    out.write(ch.code and 0xFF)
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    fun export(entries: List<TrafficEntry>, zone: ZoneId): String = buildString {
        append(HEADER).append('\n')
        append("# entries: ").append(entries.size).append('\n')
        append("# columns: time, direction (TX app->adapter, RX adapter->app, DROP discarded, NOTE event), ms, text\n")
        for (e in entries) {
            append(timeFormat.format(Instant.ofEpochMilli(e.timestampMs).atZone(zone))).append('\t')
            append(e.direction.name).append('\t')
            append(e.durationMs?.toString() ?: "-").append('\t')
            append(e.text).append('\n')
        }
    }

    /** Entries of an exported log (timestamps are not restored: replay does not need them). */
    fun parse(exported: String): List<TrafficEntry> = exported.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val direction = runCatching { TrafficDirection.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
            TrafficEntry(0L, direction, parts[3], parts[2].toLongOrNull())
        }
        .toList()
}

/** VIN masking: mode 09 PID 02 payload bytes and any VIN seen in them; also VIN-like ASCII anywhere. */
internal object VinMask {
    private const val HEX = "0123456789ABCDEFabcdef"
    private val lengthLine = Regex("^\\s*[0-9A-Fa-f]{3}\\s*$")
    private val framePrefix = Regex("^\\s*[0-9A-Fa-f]:\\s*")

    // ASCII VIN alphabet (no I, O, Q), 17 chars, with at least one non-hex letter and one digit, not inside a longer word.
    private val vinLike = Regex("(?<![A-Za-z0-9])(?=[A-HJ-NPR-Z0-9]{0,16}[G-HJ-NPR-Z])(?=[A-HJ-NPR-Z0-9]{0,16}[0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Za-z0-9])")
    private const val MASK17 = "*****************"

    /** Hex digits after "49 02 xx" (and in continuation frames) become '*'; length lines and frame numbers stay. */
    fun mask0902Reply(text: String): String {
        val out = StringBuilder()
        var line = StringBuilder()
        fun flush() {
            out.append(maskLine(line.toString()))
            line = StringBuilder()
        }
        for (ch in text) {
            if (ch == '\r' || ch == '\n') {
                flush()
                out.append(ch)
            } else {
                line.append(ch)
            }
        }
        flush()
        return out.toString()
    }

    private fun maskLine(line: String): String {
        if (line.isBlank() || lengthLine.matches(line)) return line
        val prefix = framePrefix.find(line)?.value ?: ""
        val rest = line.substring(prefix.length)
        val compact = rest.filter { it in HEX }.uppercase()
        val start = compact.indexOf("4902")
        if (start < 0 && prefix.isEmpty() && compact.length != rest.count { !it.isWhitespace() }) return line // not hex
        val keep = if (start >= 0) start + 6 else 0
        var seen = 0
        val masked = rest.map { ch ->
            if (ch in HEX) {
                seen++
                if (seen > keep) '*' else ch
            } else {
                ch
            }
        }.joinToString("")
        return prefix + masked
    }

    /** The VIN in a 0902 reply (headers off, single or multi frame), or null. */
    fun vinFrom0902(text: String): String? {
        val compact = text.split('\r', '\n')
            .filterNot { it.isBlank() || lengthLine.matches(it) }
            .joinToString("") { line -> line.replace(framePrefix, "").filter { it in HEX } }
            .uppercase()
        val start = compact.indexOf("4902")
        if (start < 0) return null
        val hex = compact.substring(start + 6)
        val ascii = hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16).toChar() }
            .filter { it.isLetterOrDigit() }.joinToString("")
        return ascii.takeIf { it.length >= 17 }?.take(17)
    }

    fun maskVins(text: String, known: Set<String>): String {
        var result = text
        for (vin in known) result = result.replace(vin, MASK17)
        return vinLike.replace(result, MASK17)
    }
}
