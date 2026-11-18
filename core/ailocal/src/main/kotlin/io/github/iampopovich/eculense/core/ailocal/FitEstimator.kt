package io.github.iampopovich.eculense.core.ailocal

import kotlin.math.roundToInt

/**
 * Что телефон может предложить локальной модели (снимается до скачивания, см. [DeviceProbe]).
 * [gpu] — есть ли OpenCL-драйвер для GPU-бэкенда LiteRT-LM; [npu] — название NPU, если вендорские библиотеки найдены
 * (без модели, скомпилированной под этот NPU, бэкенд NPU всё равно не заработает); [aicore] — установлен ли Google AICore.
 */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val socManufacturer: String?,
    val socModel: String?,
    val abis: List<String>,
    val freeStorageBytes: Long,
    val gpu: Boolean,
    val npu: String?,
    val aicore: Boolean,
    val sdk: Int,
) {
    val arm64: Boolean get() = "arm64-v8a" in abis
    val soc: String get() = listOfNotNull(socManufacturer, socModel).joinToString(" ").ifBlank { "неизвестен" }
}

/** Предварительная оценка до скачивания. UNKNOWN — размер файла неизвестен (своя ссылка без размера). */
enum class Fit(val label: String) {
    OK("Потянет"),
    TIGHT("Впритык"),
    UNLIKELY("Скорее всего нет"),
    UNKNOWN("Не знаю"),
}

data class FitEstimate(val fit: Fit, val reasons: List<String>, val needRamBytes: Long?)

/**
 * Эвристика «потянет ли» по размеру файла и памяти телефона — до скачивания, с запасом. Точный ответ даёт только
 * тест после скачивания ([BenchVerdict]); оценка лишь предупреждает и никогда не запрещает скачать.
 *
 * Рабочий набор LiteRT-LM ≈ веса (файл отображается в память и читается почти целиком) + KV-кэш и буферы рантайма
 * ([RUNTIME_OVERHEAD]). Android держит под приложение не всю память: система и фоновые приложения занимают 3–4 ГБ даже
 * на флагмане, поэтому сравниваем с общим объёмом ОЗУ с коэффициентами: ×[OK_RATIO] — с запасом, ×[TIGHT_RATIO] — впритык.
 * Калибровка (14T Pro, 11 ГБ): Gemma 4 E2B 2,6 ГБ — работает; Redmi Note 9 (3,6 ГБ): Qwen3 0,6 ГБ — 1,8 ГБ RSS.
 */
object FitEstimator {
    const val RUNTIME_OVERHEAD = 600L shl 20
    const val OK_RATIO = 2.5
    const val TIGHT_RATIO = 1.6
    /** Запас места сверх размера файла: кэш движка (XNNPack) и временные файлы. */
    const val STORAGE_MARGIN = 1L shl 30

    fun estimate(sizeBytes: Long?, device: DeviceProfile, downloaded: Boolean = false): FitEstimate {
        if (!device.arm64) {
            return FitEstimate(Fit.UNLIKELY, listOf("Нет arm64-v8a: рантайм LiteRT-LM собран только под 64-битные ARM-процессоры."), null)
        }
        if (sizeBytes == null) {
            return FitEstimate(Fit.UNKNOWN, listOf("Размер файла неизвестен — оценить заранее нельзя, покажет тест после скачивания."), null)
        }
        val need = sizeBytes + RUNTIME_OVERHEAD
        val reasons = mutableListOf<String>()
        val ratio = device.totalRamBytes.toDouble() / need
        var fit = when {
            ratio >= OK_RATIO -> Fit.OK
            ratio >= TIGHT_RATIO -> Fit.TIGHT
            else -> Fit.UNLIKELY
        }
        reasons += when (fit) {
            Fit.OK -> "Модели нужно ≈ ${gb(need)} ГБ ОЗУ, в телефоне ${gb(device.totalRamBytes)} ГБ — запас есть."
            Fit.TIGHT -> "Модели нужно ≈ ${gb(need)} ГБ ОЗУ, в телефоне ${gb(device.totalRamBytes)} ГБ — система может закрывать другие приложения или саму модель."
            else -> "Модели нужно ≈ ${gb(need)} ГБ ОЗУ, а в телефоне всего ${gb(device.totalRamBytes)} ГБ — вероятна нехватка памяти."
        }
        if (!downloaded && device.freeStorageBytes < sizeBytes + STORAGE_MARGIN) {
            fit = Fit.UNLIKELY
            reasons += "Не хватит места: нужно ≈ ${gb(sizeBytes + STORAGE_MARGIN)} ГБ, свободно ${gb(device.freeStorageBytes)} ГБ."
        }
        return FitEstimate(fit, reasons, need)
    }

    /** «2,6» — ГБ (десятичные, как в настройках Android и на Hugging Face) с одним знаком, по-русски. */
    fun gb(bytes: Long): String {
        val tenths = (bytes / 1e8).roundToInt()
        return "${tenths / 10},${tenths % 10}"
    }

    /** Размер для людей: до 0,1 ГБ — в МБ («73 МБ»), дальше — «2,6 ГБ». */
    fun size(bytes: Long): String = if (bytes < 100_000_000L) "${(bytes / 1_000_000L).coerceAtLeast(0)} МБ" else "${gb(bytes)} ГБ"
}
