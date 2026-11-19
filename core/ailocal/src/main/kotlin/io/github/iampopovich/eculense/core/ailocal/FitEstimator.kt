package io.github.iampopovich.eculense.core.ailocal

import kotlin.math.roundToInt

/**
 * What the phone can offer a local model (probed before download, see [DeviceProbe]).
 * [gpu]: whether there is an OpenCL driver for the LiteRT-LM GPU backend; [npu]: NPU name if vendor libraries were found
 * (without a model compiled for that NPU the NPU backend still won't work); [aicore]: whether Google AICore is installed.
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

/** Preliminary estimate before download. UNKNOWN: file size is unknown (custom link without size). */
enum class Fit(val label: String) {
    OK("Потянет"),
    TIGHT("Впритык"),
    UNLIKELY("Скорее всего нет"),
    UNKNOWN("Не знаю"),
}

data class FitEstimate(val fit: Fit, val reasons: List<String>, val needRamBytes: Long?)

/**
 * "Will it run" heuristic from file size and phone memory, before download, with a margin. Only the test after
 * download ([BenchVerdict]) gives an exact answer; the estimate only warns and never forbids downloading.
 *
 * LiteRT-LM working set ≈ weights (file is memory-mapped and read almost entirely) + KV cache and runtime buffers
 * ([RUNTIME_OVERHEAD]). Android doesn't give the app all memory: the system and background apps take 3–4 GB even
 * on a flagship, so we compare against total RAM with ratios: ×[OK_RATIO] = with margin, ×[TIGHT_RATIO] = tight.
 * Calibration (14T Pro, 11 GB): Gemma 4 E2B 2.6 GB works; Redmi Note 9 (3.6 GB): Qwen3 0.6 GB = 1.8 GB RSS.
 */
object FitEstimator {
    const val RUNTIME_OVERHEAD = 600L shl 20
    const val OK_RATIO = 2.5
    const val TIGHT_RATIO = 1.6
    /** Space reserve beyond file size: engine cache (XNNPack) and temp files. */
    const val STORAGE_MARGIN = 1L shl 30
    const val MIN_RAM_FAR_BELOW = 0.75

    fun estimate(sizeBytes: Long?, device: DeviceProfile, downloaded: Boolean = false, minRamGb: Int? = null): FitEstimate {
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
        // Minimum RAM from the Google AI Edge Gallery allowlist (in GiB, as totalMem/1024³). "12 GB" phones report
        // ≈ 11 GiB, so below the minimum is not a ban but "tight"; far below (< 75 %) means "most likely not".
        if (minRamGb != null) {
            val totalGib = device.totalRamBytes.toDouble() / (1L shl 30)
            if (totalGib < minRamGb) {
                val capped = if (totalGib < minRamGb * MIN_RAM_FAR_BELOW) Fit.UNLIKELY else Fit.TIGHT
                if (capped.ordinal > fit.ordinal) fit = capped
                reasons += "Google AI Edge Gallery рекомендует для неё от $minRamGb ГБ ОЗУ."
            }
        }
        if (!downloaded && device.freeStorageBytes < sizeBytes + STORAGE_MARGIN) {
            fit = Fit.UNLIKELY
            reasons += "Не хватит места: нужно ≈ ${gb(sizeBytes + STORAGE_MARGIN)} ГБ, свободно ${gb(device.freeStorageBytes)} ГБ."
        }
        return FitEstimate(fit, reasons, need)
    }

    /** «2,6»: GB (decimal, as in Android settings and on Hugging Face) with one decimal, Russian formatting. */
    fun gb(bytes: Long): String {
        val tenths = (bytes / 1e8).roundToInt()
        return "${tenths / 10},${tenths % 10}"
    }

    /** Human-readable size: below 0.1 GB in MB («73 МБ»), above that «2,6 ГБ». */
    fun size(bytes: Long): String = if (bytes < 100_000_000L) "${(bytes / 1_000_000L).coerceAtLeast(0)} МБ" else "${gb(bytes)} ГБ"
}
