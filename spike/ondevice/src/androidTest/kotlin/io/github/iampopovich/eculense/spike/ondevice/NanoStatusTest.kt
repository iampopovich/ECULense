package io.github.iampopovich.eculense.spike.ondevice

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 3.10: доступен ли Gemini Nano (ML Kit GenAI Prompt API через AICore) и что он умеет. -e ask true — пробный вопрос. */
@RunWith(AndroidJUnit4::class)
class NanoStatusTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun status() = runBlocking {
        val out = StringBuilder("# Gemini Nano\n")
        fun log(s: String) {
            Log.i(OnDeviceLlmSpikeTest.TAG, s)
            out.append(s).append('\n')
        }
        val model = Generation.getClient()
        try {
            log("checkStatus=${runCatching { model.checkStatus() }.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }}")
            log("baseModel=${runCatching { model.getBaseModelName() }.getOrElse { "ERROR ${it.message}" }}")
            log("features=${runCatching { model.getAvailableFeatures() }.getOrElse { "ERROR ${it.message}" }}")
            log("systemPrompt=${runCatching { model.isSystemPromptAvailable() }.getOrElse { "ERROR ${it.message}" }}")
            log("tokenLimit=${runCatching { model.getTokenLimit() }.getOrElse { "ERROR ${it.message}" }}")
            if (args.getString("ask") == "true") {
                val t0 = SystemClock.elapsedRealtime()
                val r = runCatching { model.generateContent(args.getString("q") ?: "Объясни код ошибки P0133.").candidates.firstOrNull()?.text }
                log("answer ${SystemClock.elapsedRealtime() - t0} ms: ${r.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }}")
            }
        } finally {
            model.close()
            File(context.filesDir, "nano-status.md").writeText(out.toString())
        }
    }
}
