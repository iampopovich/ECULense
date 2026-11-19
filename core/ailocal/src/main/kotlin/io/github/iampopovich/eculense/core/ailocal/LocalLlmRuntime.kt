package io.github.iampopovich.eculense.core.ailocal

import ai.koog.prompt.executor.clients.litert.LiteRTClientConfig
import ai.koog.prompt.executor.clients.litert.LiteRTLLMClient
import ai.koog.prompt.executor.clients.litert.LiteRTLLMProvider
import ai.koog.prompt.executor.clients.litert.LiteRTLLModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import io.github.iampopovich.eculense.core.ai.OnDeviceLlm
import io.github.iampopovich.eculense.core.ai.OnDeviceSession
import io.github.iampopovich.eculense.core.ai.OnDeviceSummary
import io.github.iampopovich.eculense.core.ai.ProviderInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Execution via Koog: `LiteRTLLMClient` (prompt-executor-litert-client 1.3.0-beta, LiteRT-LM 0.11) behind
 * [OnDeviceLlm]; the :core:ai gateway builds on it the same tool-enabled agent as for the cloud. Verified on
 * Xiaomi 14T Pro: Gemma 4 E2B runs on CPU and GPU. The client (and the engine with gigabytes of weights) lives between questions
 * and is recreated only when the model/backend changes or a cloud provider is selected.
 */
class LocalLlmRuntime(
    private val store: LocalModelStore,
    private val models: LocalModels,
    private val cacheDir: File,
    /** The app's native library directory, for the NPU backend (vendor dispatchers). */
    private val nativeLibDir: String? = null,
) : OnDeviceLlm {
    private val lock = Mutex()
    private var client: LiteRTLLMClient? = null
    private var executor: PromptExecutor? = null
    private var key: Pair<String, BackendKind>? = null

    override val summary: Flow<OnDeviceSummary?> = combine(store.active, store.reports, models.models) { active, reports, list ->
        active ?: return@combine null
        val model = list.firstOrNull { it.packId == active.packId } ?: return@combine null
        val run = reports[active.packId]?.runs?.firstOrNull { it.backend == active.backend && it.ok }
        val verdict = reports[active.packId]?.verdict
        val detail = listOfNotNull(
            active.backend.label,
            run?.let { String.format(java.util.Locale.getDefault(), "%.1f ток/с", it.decodeTokensPerSecond) },
            verdict?.label,
        ).joinToString(" · ")
        OnDeviceSummary(model.title, detail)
    }

    override suspend fun session(): OnDeviceSession? = lock.withLock {
        val active = store.active.first() ?: return@withLock null
        val model = models.find(active.packId) ?: return@withLock null
        val file = models.file(active.packId) ?: throw IllegalStateException("файл модели «${model.title}» удалён — скачайте её снова или выберите другую")
        val llm = LiteRTLLModels.models.firstOrNull { it.id == file.name }
            ?: LLModel(LiteRTLLMProvider, file.name, listOf(LLMCapability.Tools, LLMCapability.Completion), contextLength = 4_096)
        val wanted = active.packId to active.backend
        if (key != wanted || executor == null) {
            closeLocked()
            val created = LiteRTLLMClient(
                LiteRTClientConfig(
                    defaultModel = llm,
                    modelsPath = file.parentFile!!.absolutePath,
                    cacheDir = File(cacheDir, "litert").apply { mkdirs() }.absolutePath,
                    backend = backend(active.backend),
                ),
            )
            client = created
            executor = MultiLLMPromptExecutor(created)
            key = wanted
            Log.i(BenchService.TAG, "client for ${file.name} on ${active.backend}")
        }
        OnDeviceSession(
            info = ProviderInfo(PROVIDER_KEY, "На устройстве", "${model.title} (${active.backend.label})", onDevice = true),
            model = llm,
            executor = executor!!,
            streaming = false,
            // Gemma 4 via the Koog client does not call tools (measured in 3.10), so vehicle data goes in as a digest (VehicleToolbox.digest).
            supportsTools = false,
        )
    }

    override suspend fun deactivate() {
        store.deactivate()
        release()
    }

    /** Release the engine (model/backend change, file deletion, cloud selected). */
    suspend fun release() = lock.withLock { closeLocked() }

    private fun closeLocked() {
        runCatching { client?.close() }
        client = null
        executor = null
        key = null
    }

    private fun backend(kind: BackendKind): Backend = when (kind) {
        BackendKind.CPU -> Backend.CPU()
        BackendKind.GPU -> Backend.GPU()
        BackendKind.NPU -> if (nativeLibDir != null) Backend.NPU(nativeLibDir) else Backend.NPU()
    }

    companion object {
        const val PROVIDER_KEY = "on-device"
    }
}
