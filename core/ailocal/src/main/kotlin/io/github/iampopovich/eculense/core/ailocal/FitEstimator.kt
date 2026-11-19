package io.github.iampopovich.eculense.core.ailocal

import androidx.annotation.StringRes
import io.github.iampopovich.eculense.core.ui.UiText
import java.util.Locale

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
    /** Chip name; null when the phone reports none. */
    val soc: String? get() = listOfNotNull(socManufacturer, socModel).joinToString(" ").ifBlank { null }
}

/** Preliminary estimate before download. UNKNOWN: file size is unknown (custom link without size). */
enum class Fit(@param:StringRes val labelRes: Int) {
    OK(R.string.ailocal_fit_ok),
    TIGHT(R.string.ailocal_fit_tight),
    UNLIKELY(R.string.ailocal_fit_unlikely),
    UNKNOWN(R.string.ailocal_fit_unknown),
}

data class FitEstimate(val fit: Fit, val reasons: List<UiText>, val needRamBytes: Long?)

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
            return FitEstimate(Fit.UNLIKELY, listOf(UiText.Res(R.string.ailocal_fit_no_arm64)), null)
        }
        if (sizeBytes == null) {
            return FitEstimate(Fit.UNKNOWN, listOf(UiText.Res(R.string.ailocal_fit_size_unknown)), null)
        }
        val need = sizeBytes + RUNTIME_OVERHEAD
        val reasons = mutableListOf<UiText>()
        val ratio = device.totalRamBytes.toDouble() / need
        var fit = when {
            ratio >= OK_RATIO -> Fit.OK
            ratio >= TIGHT_RATIO -> Fit.TIGHT
            else -> Fit.UNLIKELY
        }
        reasons += when (fit) {
            Fit.OK -> UiText.Res(R.string.ailocal_fit_ram_ok, listOf(gb(need), gb(device.totalRamBytes)))
            Fit.TIGHT -> UiText.Res(R.string.ailocal_fit_ram_tight, listOf(gb(need), gb(device.totalRamBytes)))
            else -> UiText.Res(R.string.ailocal_fit_ram_low, listOf(gb(need), gb(device.totalRamBytes)))
        }
        // Minimum RAM from the Google AI Edge Gallery allowlist (in GiB, as totalMem/1024³). "12 GB" phones report
        // ≈ 11 GiB, so below the minimum is not a ban but "tight"; far below (< 75 %) means "most likely not".
        if (minRamGb != null) {
            val totalGib = device.totalRamBytes.toDouble() / (1L shl 30)
            if (totalGib < minRamGb) {
                val capped = if (totalGib < minRamGb * MIN_RAM_FAR_BELOW) Fit.UNLIKELY else Fit.TIGHT
                if (capped.ordinal > fit.ordinal) fit = capped
                reasons += UiText.Res(R.string.ailocal_fit_gallery_min, listOf(minRamGb))
            }
        }
        if (!downloaded && device.freeStorageBytes < sizeBytes + STORAGE_MARGIN) {
            fit = Fit.UNLIKELY
            reasons += UiText.Res(R.string.ailocal_fit_storage, listOf(gb(sizeBytes + STORAGE_MARGIN), gb(device.freeStorageBytes)))
        }
        return FitEstimate(fit, reasons, need)
    }

    /** «2.6» / «2,6»: GB (decimal, as in Android settings and on Hugging Face) with one decimal, in the current locale. */
    fun gb(bytes: Long, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", Math.round(bytes / 1e8) / 10.0)

    /** Human-readable size: below 0.1 GB in MB («73 MB»), above that «2.6 GB». */
    fun size(bytes: Long): UiText =
        if (bytes < 100_000_000L) UiText.Res(R.string.ailocal_size_mb, listOf((bytes / 1_000_000L).coerceAtLeast(0).toInt()))
        else UiText.Res(R.string.ailocal_size_gb, listOf(gb(bytes)))
}
