package io.github.iampopovich.eculense.spike.ondevice

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.litert.LiteRTClientConfig
import ai.koog.prompt.executor.clients.litert.LiteRTLLMClient
import ai.koog.prompt.executor.clients.litert.LiteRTLLModels
import ai.koog.prompt.message.MessagePart
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 3.10: может ли клиент Koog (LiteRT-LM 0.11) исполнять Gemma 4 из своего каталога. -e model gemma-4-E2B-it.litertlm
 * -e backend cpu|gpu -e step bench|chat|tool. Модель — в /data/local/tmp/llm.
 */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class KoogGemma4CheckTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun status(key: String): String =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim().orEmpty()

    private fun backend() = when (args.getString("backend")) {
        "gpu" -> Backend.GPU()
        "npu" -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
    }

    @Test
    fun check() = runBlocking {
        val modelId = args.getString("model") ?: LiteRTLLModels.Gemma4E2B.id
        val model = LiteRTLLModels.models.firstOrNull { it.id == modelId } ?: error("not in Koog catalog: $modelId")
        val step = args.getString("step") ?: "chat"
        val out = StringBuilder("# Koog check $modelId ${args.getString("backend") ?: "cpu"} $step\n")
        fun log(s: String) {
            Log.i(OnDeviceLlmSpikeTest.TAG, s)
            out.append(s).append('\n')
        }
        log("before: VmRSS=${status("VmRSS")}")
        try {
            when (step) {
                "bench" -> {
                    val t0 = SystemClock.elapsedRealtime()
                    val info = runCatching {
                        benchmark("/data/local/tmp/llm/$modelId", backend(), args.getString("prefill")?.toInt() ?: 256, args.getString("decode")?.toInt() ?: 64, context.cacheDir.absolutePath)
                    }
                    log("bench ${SystemClock.elapsedRealtime() - t0} ms: ${info.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }} VmHWM=${status("VmHWM")}")
                }
                else -> {
                    val client = LiteRTLLMClient(LiteRTClientConfig(defaultModel = model, modelsPath = "/data/local/tmp/llm", cacheDir = context.cacheDir.absolutePath, backend = backend()))
                    val tool = ToolDescriptor(
                        name = "dtcHistory",
                        description = "История кодов ошибок (DTC) машины пользователя: код, описание, сколько раз, когда последний раз.",
                        requiredParameters = emptyList(),
                        optionalParameters = listOf(ToolParameterDescriptor("limit", "Сколько последних кодов вернуть", ToolParameterType.Integer)),
                    )
                    val questions = if (step == "tool") listOf("Объясни мои ошибки.") else listOf("Привет! Ответь одним словом.") + OnDeviceLlmSpikeTest.QUESTIONS
                    try {
                        for ((i, q) in questions.withIndex()) {
                            val p = prompt("k-$i") {
                                system("Ты — помощник автодиагноста. Отвечай по-русски, кратко (до 6 предложений), без выдумок." + if (step == "tool") " Для вопросов об ошибках машины вызови инструмент dtcHistory." else "")
                                user(q)
                            }
                            val t0 = SystemClock.elapsedRealtime()
                            val r = runCatching { client.execute(p, model, if (step == "tool") listOf(tool) else emptyList()) }
                            val ms = SystemClock.elapsedRealtime() - t0
                            val text = r.map { a ->
                                a.parts.joinToString(" | ") { part ->
                                    when (part) {
                                        is MessagePart.Text -> part.text
                                        is MessagePart.Tool.Call -> "TOOL_CALL ${part.tool}(${part.args})"
                                        else -> part::class.simpleName.orEmpty()
                                    }
                                }
                            }.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }
                            log("\n## Q$i: $q\n- $ms ms VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}\n\n$text")
                        }
                    } finally {
                        client.close()
                    }
                }
            }
        } finally {
            File(context.filesDir, "koog-$step-${args.getString("backend") ?: "cpu"}-${modelId.substringBefore('.')}.md").writeText(out.toString())
        }
    }
}
