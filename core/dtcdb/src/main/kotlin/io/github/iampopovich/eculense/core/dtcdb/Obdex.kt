package io.github.iampopovich.eculense.core.dtcdb

import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackPostProcessor
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** What we need from an OBDex entry (schema `schema/code.schema.json`); the rest (sources, links) is ignored. */
@Serializable
internal data class ObdexCode(
    val code: String,
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val common_causes: List<ObdexCause> = emptyList(),
    val symptoms: List<Map<String, String>> = emptyList(),
)

@Serializable
internal data class ObdexCause(val label: Map<String, String> = emptyMap(), val likelihood: String? = null)

/**
 * OBDex `generic.min.json` (30 MB) → `obdex-index.tsv`: one line per code (code, name, description, causes, symptoms).
 * The JSON is stream-parsed once when the pack is installed; the screen reads a single line by offset.
 * The original JSON stays alongside, unchanged.
 */
object ObdexIndex : PackPostProcessor {
    const val SOURCE = "generic.min.json"
    const val INDEX = "obdex-index.tsv"
    private const val SEP = " | "

    override val packId: String = PackCatalog.OBDEX

    override fun process(dir: File) {
        File(dir, SOURCE).inputStream().buffered().use { input ->
            File(dir, INDEX).bufferedWriter().use { out ->
                write(input) { out.write(it); out.write("\n") }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun write(input: InputStream, line: (String) -> Unit): Int {
        val json = Json { ignoreUnknownKeys = true }
        var n = 0
        json.decodeToSequence(input, ObdexCode.serializer() as DeserializationStrategy<ObdexCode>, DecodeSequenceMode.ARRAY_WRAPPED).forEach { c ->
            if (!DtcCodes.isValid(c.code)) return@forEach
            val causes = c.common_causes.mapNotNull { it.label["en"] }
            val symptoms = c.symptoms.mapNotNull { it["en"] }
            line(listOf(c.code, c.title["en"].orEmpty(), c.description["en"].orEmpty(), causes.joinToString(SEP), symptoms.joinToString(SEP)).joinToString("\t") { clean(it) })
            n++
        }
        return n
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    internal fun parse(line: String): DtcInfo? {
        val f = line.split('\t')
        if (f.size < 5) return null
        fun list(s: String) = if (s.isBlank()) emptyList() else s.split(SEP)
        return DtcInfo(f[0], f[1].ifBlank { null }, DtcSource.OBDEX, f[2].ifBlank { null }, list(f[3]), list(f[4]))
    }
}

/** OBDex index reader: only line offsets (~9.5k) are kept in memory, text is read from disk on demand. */
class ObdexReader(private val index: File) : GenericDtcSource {
    private val offsets: Map<String, Long> by lazy {
        val map = HashMap<String, Long>(16_384)
        RandomAccessFile(index, "r").use { raf ->
            val buf = raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length())
            var lineStart = 0
            var i = 0
            val limit = buf.limit()
            while (i < limit) {
                if (buf.get(i) == '\n'.code.toByte()) {
                    if (i - lineStart >= 5) {
                        val code = ByteArray(5).also { for (k in 0 until 5) it[k] = buf.get(lineStart + k) }
                        map[String(code, Charsets.US_ASCII)] = lineStart.toLong()
                    }
                    lineStart = i + 1
                }
                i++
            }
        }
        map
    }

    override fun lookup(code: String): DtcInfo? {
        val at = offsets[code] ?: return null
        val line = RandomAccessFile(index, "r").use { raf ->
            raf.seek(at)
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = raf.read()
                if (b < 0 || b == '\n'.code) break
                bytes.write(b)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
        return ObdexIndex.parse(line)
    }
}

fun interface GenericDtcSource {
    fun lookup(code: String): DtcInfo?
}
