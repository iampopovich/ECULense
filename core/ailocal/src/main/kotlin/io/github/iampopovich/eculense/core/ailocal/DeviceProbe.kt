package io.github.iampopovich.eculense.core.ailocal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import java.io.File

/** Снимает [DeviceProfile]: память, SoC, ABI, место, драйвер OpenCL для GPU, вендорские библиотеки NPU, AICore. */
class DeviceProbe(private val context: Context) {
    fun profile(): DeviceProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val (maker, model) = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MANUFACTURER.takeIf { it != Build.UNKNOWN } to Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }
        } else {
            null to Build.HARDWARE
        }
        return DeviceProfile(
            totalRamBytes = mem.totalMem,
            availRamBytes = mem.availMem,
            socManufacturer = maker,
            socModel = model,
            abis = Build.SUPPORTED_ABIS.toList(),
            freeStorageBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L),
            gpu = OPENCL.any { File(it).exists() },
            npu = NPU.firstOrNull { (paths, _) -> paths.any { File(it).exists() } }?.second,
            aicore = installed("com.google.android.aicore"),
            sdk = Build.VERSION.SDK_INT,
        )
    }

    @Suppress("DEPRECATION")
    private fun installed(pkg: String): Boolean = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private companion object {
        val OPENCL = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so", "/vendor/lib64/libOpenCL-pixel.so")
        val NPU = listOf(
            listOf("/vendor/lib64/libneuron_runtime.so", "/vendor/lib64/libapu_mdw.so") to "MediaTek APU",
            listOf("/vendor/lib64/libQnnHtp.so", "/vendor/lib64/libcdsprpc.so") to "Qualcomm Hexagon",
            listOf("/vendor/lib64/libedgetpu_util.so", "/vendor/lib64/libedgetpu_client.google.so") to "Google Tensor TPU",
            listOf("/vendor/lib64/libeden_nn_on_system.so", "/vendor/lib64/libenn_public_api_cpp.so") to "Samsung NPU",
        )
    }
}

/** Gemini Nano через AICore: доступен ли и почему нет. */
data class NanoStatus(val available: Boolean, val text: String)

class NanoProbe {
    suspend fun status(aicoreInstalled: Boolean): NanoStatus {
        if (!aicoreInstalled) return NanoStatus(false, "Gemini Nano недоступен: на телефоне нет Google AICore.")
        val model = runCatching { Generation.getClient() }.getOrElse { return NanoStatus(false, "Gemini Nano недоступен: ${it.message}") }
        return try {
            when (val s = model.checkStatus()) {
                FeatureStatus.AVAILABLE -> NanoStatus(true, "Gemini Nano доступен (встроенная модель телефона).")
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                    NanoStatus(true, "Gemini Nano поддерживается, модель скачивает система (AICore).")
                FeatureStatus.UNAVAILABLE ->
                    NanoStatus(false, "Gemini Nano недоступен: AICore есть, но для этого телефона Google не включил модель (статус UNAVAILABLE).")
                else -> NanoStatus(false, "Gemini Nano: неизвестный статус $s.")
            }
        } catch (e: Exception) {
            NanoStatus(false, "Gemini Nano недоступен: ${e.message ?: e::class.simpleName}")
        } finally {
            runCatching { model.close() }
        }
    }
}
