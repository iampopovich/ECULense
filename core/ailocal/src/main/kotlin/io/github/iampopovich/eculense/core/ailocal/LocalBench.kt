package io.github.iampopovich.eculense.core.ailocal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Идёт тест: какая модель, какой бэкенд сейчас, что уже измерено. */
data class BenchProgress(val packId: String, val current: BackendKind?, val done: List<BackendRun>)

/**
 * Тест скорости модели после скачивания: по очереди CPU, GPU (если есть OpenCL), NPU (если найдены вендорские
 * библиотеки) — каждый в свежем процессе `:bench` ([BenchRunner]); итог сохраняется, у выбранной модели бэкенд
 * переключается на лучший. Перед тестом движок чата освобождается — две копии весов в памяти не нужны.
 */
class LocalBench(
    private val runner: BenchRunner,
    private val models: LocalModels,
    private val store: LocalModelStore,
    private val runtime: LocalLlmRuntime,
    private val probe: DeviceProbe,
    private val cacheDir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _progress = MutableStateFlow<BenchProgress?>(null)
    val progress: StateFlow<BenchProgress?> = _progress.asStateFlow()
    private var job: Job? = null

    /** Запустить тест (если другой уже идёт — игнорируется). */
    fun start(packId: String) {
        if (job?.isActive == true) return
        job = scope.launch { run(packId) }
    }

    suspend fun run(packId: String): BenchReport? {
        val file = models.file(packId) ?: return null
        runtime.release()
        val device = probe.profile()
        val backends = buildList {
            add(BackendKind.CPU)
            if (device.gpu) add(BackendKind.GPU)
            if (device.npu != null) add(BackendKind.NPU)
        }
        val done = mutableListOf<BackendRun>()
        try {
            for (b in backends) {
                _progress.value = BenchProgress(packId, b, done.toList())
                val r = runner.run(file, b, File(cacheDir, "bench"))
                Log.i(BenchService.TAG, "bench ${file.name} $r")
                done += r
            }
            val report = BenchReport(packId, done.toList(), clock())
            store.saveReport(report)
            val active = store.active.first()
            val best = report.best
            if (active?.packId == packId && best != null && best.backend != active.backend) store.activate(packId, best.backend)
            return report
        } finally {
            _progress.value = null
        }
    }
}
