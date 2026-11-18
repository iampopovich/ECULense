package io.github.iampopovich.eculense.spike.ondevice

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Тот же замер напрямую через LiteRT-LM (без Koog) на свежем рантайме (-Pecu.spike.litertlm=0.19.1): отделяет
 * «модель/телефон не тянут» от «клиент Koog 1.3.0-beta на LiteRT-LM 0.11 не работает с этой моделью».
 * Считает время до первого фрагмента (стриминг) и полное время ответа.
 */
@RunWith(AndroidJUnit4::class)
class DirectLiteRtSpikeTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun status(key: String): String =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim().orEmpty()

    @Test
    fun measureDirect() = runBlocking {
        val modelId = args.getString("model") ?: "Qwen3-0.6B.litertlm"
        val suffix = args.getString("suffix").orEmpty()
        val maxOut = args.getString("maxOut")?.toIntOrNull() ?: 256
        val report = StringBuilder("# Spike (direct LiteRT-LM): $modelId (CPU)\n\n")
        fun log(line: String) {
            Log.i(OnDeviceLlmSpikeTest.TAG, line)
            report.append(line).append('\n')
        }
        log("before: VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}")
        val engine = Engine(EngineConfig(modelPath = "/data/local/tmp/llm/$modelId", backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath))
        val t0 = SystemClock.elapsedRealtime()
        engine.initialize()
        log("init: ${SystemClock.elapsedRealtime() - t0} ms, VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}")
        val system = args.getString("system") ?: "Ты — помощник автодиагноста. Отвечай по-русски, кратко (до 6 предложений), без выдумок."
        val custom = args.getString("q")
        val questions = if (custom != null) listOf(custom) else OnDeviceLlmSpikeTest.QUESTIONS
        try {
            questions.forEachIndexed { i, q ->
                val conversation = engine.createConversation(
                    ConversationConfig(
                        systemInstruction = system.takeIf { it.isNotBlank() }?.let { Contents.of(it) }, maxOutputToken = maxOut,
                        thinkingConfig = ThinkingConfig(args.getString("think") == "true"),
                    ),
                )
                val start = SystemClock.elapsedRealtime()
                var first = -1L
                val text = StringBuilder()
                val thinking = StringBuilder()
                var chunks = 0
                runCatching {
                    conversation.sendMessageAsync(q + suffix).collect { m ->
                        if (first < 0) first = SystemClock.elapsedRealtime() - start
                        chunks++
                        text.append(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                        m.channels.values.forEach { thinking.append(it) }
                    }
                }.onFailure { text.append("ERROR: ${it::class.simpleName}: ${it.message}") }
                val total = SystemClock.elapsedRealtime() - start
                conversation.close()
                log("\n## Q${i + 1}: $q\n- first chunk: $first ms, total: $total ms, chunks: $chunks (~tokens), ~${"%.1f".format(chunks * 1000.0 / (total - first).coerceAtLeast(1))} tok/s, VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}, thinking chars: ${thinking.length}\n\n$text\n")
            }
        } finally {
            engine.close()
            log("after close: VmRSS=${status("VmRSS")} VmHWM=${status("VmHWM")}")
            File(context.filesDir, "spike-direct-${modelId.substringBefore('.')}.md").writeText(report.toString())
        }
    }
}
