package io.github.iampopovich.eculense.core.backup

import java.io.ByteArrayOutputStream

/**
 * Файл DataStore Preferences (`*.preferences_pb`) — protobuf `PreferenceMap { map<string, Value> preferences = 1; }`:
 * последовательность полей 1 (length-delimited), в каждом key = 1 (строка) и value = 2. Делим записи на публичные и
 * секретные по имени ключа, не разбирая значения: байты записей копируются как есть, формат не теряется.
 */
object PrefsFile {
    /** Ключи API, токены, пароли — в резервную копию не попадают никогда. */
    private val SECRET = Regex("(?i)(api[_-]?key|token|secret|passw|credential|private[_-]?key|cipher|auth)")

    fun isSecret(key: String): Boolean = SECRET.containsMatchIn(key)

    data class Split(val public: ByteArray, val secret: ByteArray, val publicKeys: List<String>, val secretCount: Int)

    /** Бросает IllegalArgumentException на непонятном файле — вызывающий тогда не берёт файл в копию совсем. */
    fun split(bytes: ByteArray): Split {
        val pub = ByteArrayOutputStream()
        val sec = ByteArrayOutputStream()
        val keys = mutableListOf<String>()
        var secrets = 0
        var i = 0
        while (i < bytes.size) {
            val start = i
            val (tag, afterTag) = varint(bytes, i)
            require(tag == 0x0AL) { "неизвестное поле $tag" }
            val (len, afterLen) = varint(bytes, afterTag)
            val end = afterLen + len.toInt()
            require(len >= 0 && end <= bytes.size) { "обрезанная запись" }
            val key = entryKey(bytes, afterLen, end)
            val target = if (isSecret(key)) { secrets++; sec } else { keys += key; pub }
            target.write(bytes, start, end - start)
            i = end
        }
        return Split(pub.toByteArray(), sec.toByteArray(), keys, secrets)
    }

    /** Ключи всех записей (для проверок и манифеста). */
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
        throw IllegalArgumentException("запись без ключа")
    }

    private fun varint(b: ByteArray, from: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = from
        while (true) {
            require(i < b.size && shift < 64) { "обрезанный varint" }
            val v = b[i++].toInt() and 0xFF
            result = result or ((v and 0x7F).toLong() shl shift)
            if (v and 0x80 == 0) return result to i
            shift += 7
        }
    }
}
