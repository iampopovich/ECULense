package io.github.iampopovich.eculense.spike.ondevice

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.litert.LiteRTClientConfig
import ai.koog.prompt.executor.clients.litert.LiteRTLLMClient
import ai.koog.prompt.executor.clients.litert.LiteRTLLMProvider
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Замер локальной модели (3.6): загрузка, RAM процесса (VmRSS/VmHWM), время ответа, тексты ответов на 5 эталонных
 * вопросов. Результат — в logcat (EcuSpike) и в files/spike-<model>.md тестового пакета (adb pull через run-as).
 * Запуск: adb shell am instrument -w -e model Qwen3-0.6B.litertlm -e class …OnDeviceLlmSpikeTest <test-pkg>/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmSpikeTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun status(key: String): String =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim().orEmpty()

    @Test
    fun measure() = runBlocking {
        val modelId = args.getString("model") ?: "Qwen3-0.6B.litertlm"
        val suffix = args.getString("suffix").orEmpty()
        val model = LLModel(LiteRTLLMProvider, modelId, listOf(LLMCapability.Completion), contextLength = 4_096)
        val client = LiteRTLLMClient(
            LiteRTClientConfig(defaultModel = model, modelsPath = "/data/local/tmp/llm", cacheDir = context.cacheDir.absolutePath, backend = Backend.CPU()),
        )
        val report = StringBuilder("# Spike: $modelId (CPU)\n\n")
        fun log(line: String) {
            Log.i(TAG, line)
            report.append(line).append('\n')
        }
        log("before: VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}")
        val system = args.getString("system") ?: "Ты — помощник автодиагноста. Отвечай по-русски, кратко (до 6 предложений), без выдумок."
        val custom = args.getString("q")
        val questions = if (custom != null) listOf(custom) else listOf("Привет! Ответь одним словом.") + QUESTIONS
        try {
            questions.forEachIndexed { i, q ->
                val p = prompt("spike-$i") {
                    if (system.isNotBlank()) system(system)
                    user(q + suffix)
                }
                val t0 = SystemClock.elapsedRealtime()
                val answer = runCatching { client.execute(p, model, emptyList()).textContent() }.getOrElse { "ERROR: ${it::class.simpleName}: ${it.message}" }
                val ms = SystemClock.elapsedRealtime() - t0
                log("\n## Q${i}: $q\n- time: $ms ms, chars: ${answer.length}, VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}\n\n$answer\n")
            }
        } finally {
            client.close()
            log("after close: VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}")
            File(context.filesDir, "spike-${modelId.substringBefore('.')}.md").writeText(report.toString())
        }
    }

    companion object {
        const val TAG = "EcuSpike"
        val QUESTIONS = listOf(
            "Объясни код ошибки P0133.",
            "Что означают коды P0171 и P0174 вместе и что проверить?",
            "Долгосрочная коррекция топлива +12% на обоих банках на прогретом двигателе — это плохо?",
            "Напряжение 13,0 В на работающем двигателе — это норма?",
            "Как часто менять масло в двигателе, если много коротких поездок по городу?",
        )
    }
}
