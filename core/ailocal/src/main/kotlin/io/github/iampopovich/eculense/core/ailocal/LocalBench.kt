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

/** Test in progress: which model, which backend right now, what has been measured so far. */
data class BenchProgress(val packId: String, val current: BackendKind?, val done: List<BackendRun>)

/**
 * Model speed test after download: CPU, GPU (if OpenCL is available), NPU (if vendor libraries are found)
 * in turn, each in a fresh `:bench` process ([BenchRunner]); the result is saved, and the selected model's backend
 * switches to the best one. The chat engine is released before the test: two copies of the weights in memory are not needed.
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

    /** Start the test (ignored if another one is already running). */
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
                Log.w(BenchService.TAG, "bench ${file.name} $r")
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
