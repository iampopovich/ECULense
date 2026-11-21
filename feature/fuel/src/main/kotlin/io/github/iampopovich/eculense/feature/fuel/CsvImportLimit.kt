package io.github.iampopovich.eculense.feature.fuel

import java.io.InputStream

/** Result of reading a picked CSV file with a size limit. */
internal sealed interface CsvRead {
    data class Text(val text: String) : CsvRead
    data object TooLarge : CsvRead
}

/**
 * Size limit of the fuel CSV import: the file is read into memory as one string, so an accidentally picked large file
 * (a video, a backup) must not be. A Fuelio export of 10 years of fill-ups every 3 days is ~1 200 rows of ~200 bytes
 * ≈ 0.25 MB; [MAX_BYTES] leaves 20× headroom.
 */
internal object CsvImportLimit {
    const val MAX_MB = 5
    const val MAX_BYTES = MAX_MB * 1024 * 1024

    /** Reads at most [maxBytes] + 1 bytes: one byte more than allowed means the file is too large. */
    fun read(input: InputStream, maxBytes: Int = MAX_BYTES): CsvRead {
        // InputStream.readNBytes is API 33+; minSdk is 26.
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() <= maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes + 1 - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        return if (bytes.size > maxBytes) CsvRead.TooLarge else CsvRead.Text(bytes.toString(Charsets.UTF_8))
    }
}
