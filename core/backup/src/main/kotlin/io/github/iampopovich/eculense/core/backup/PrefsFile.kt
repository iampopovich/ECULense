package io.github.iampopovich.eculense.core.backup

import java.io.ByteArrayOutputStream

/**
 * DataStore Preferences file (`*.preferences_pb`) — protobuf `PreferenceMap { map<string, Value> preferences = 1; }`:
 * a sequence of field 1 (length-delimited), each with key = 1 (string) and value = 2. Entries are split into public and
 * secret by key name without parsing values: entry bytes are copied as is, so the format is preserved.
 */
object PrefsFile {
    /** API keys, tokens, passwords — never go into the backup. */
    private val SECRET = Regex("(?i)(api[_-]?key|token|secret|passw|credential|private[_-]?key|cipher|auth)")

    fun isSecret(key: String): Boolean = SECRET.containsMatchIn(key)

    data class Split(val public: ByteArray, val secret: ByteArray, val publicKeys: List<String>, val secretCount: Int)

    /** Throws IllegalArgumentException on an unrecognized file — the caller then leaves the file out of the backup entirely. */
    fun split(bytes: ByteArray): Split {
        val pub = ByteArrayOutputStream()
        val sec = ByteArrayOutputStream()
        val keys = mutableListOf<String>()
        var secrets = 0
        var i = 0
        while (i < bytes.size) {
            val start = i
            val (tag, afterTag) = varint(bytes, i)
            require(tag == 0x0AL) { "unknown field $tag" }
            val (len, afterLen) = varint(bytes, afterTag)
            val end = afterLen + len.toInt()
            require(len >= 0 && end <= bytes.size) { "truncated entry" }
            val key = entryKey(bytes, afterLen, end)
            val target = if (isSecret(key)) { secrets++; sec } else { keys += key; pub }
            target.write(bytes, start, end - start)
            i = end
        }
        return Split(pub.toByteArray(), sec.toByteArray(), keys, secrets)
    }

    /** Keys of all entries (for checks and the manifest). */
    fun keys(bytes: ByteArray): List<String> = split(bytes).let { s -> s.publicKeys + List(s.secretCount) { "<secret>" } }

    private fun entryKey(b: ByteArray, from: Int, to: Int): String {
        var i = from
        while (i < to) {
            val (tag, afterTag) = varint(b, i)
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when (wire) {
                2 -> {
                    val (len, afterLen) = varint(b, afterTag)
                    if (field == 1) return String(b, afterLen, len.toInt(), Charsets.UTF_8)
                    i = afterLen + len.toInt()
                }
                0 -> i = varint(b, afterTag).second
                1 -> i = afterTag + 8
                5 -> i = afterTag + 4
                else -> throw IllegalArgumentException("wire type $wire")
            }
        }
        throw IllegalArgumentException("entry without a key")
    }

    private fun varint(b: ByteArray, from: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = from
        while (true) {
            require(i < b.size && shift < 64) { "truncated varint" }
            val v = b[i++].toInt() and 0xFF
            result = result or ((v and 0x7F).toLong() shl shift)
            if (v and 0x80 == 0) return result to i
            shift += 7
        }
    }
}
