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
                "digest" -> {
                    // Как чат приложения с моделью на телефоне: SystemPrompts.chatWithDigest(сводка) без инструментов.
                    val client = LiteRTLLMClient(LiteRTClientConfig(defaultModel = model, modelsPath = "/data/local/tmp/llm", cacheDir = context.cacheDir.absolutePath, backend = backend()))
                    val system = APP_SYSTEM_PROMPT.substringBefore("У тебя есть инструменты") +
                        "Ниже — данные машины пользователя, их посчитало приложение (JSON по разделам). VIN и координат там нет.\n" +
                        "5. Все числа, даты и коды бери только отсюда, не придумывай. Если раздела нет или там no_data — так и скажи.\n" +
                        "6. Находки (findings) рассчитаны по правилам — объясняй их, не спорь с числами.\nДанные:\n" +
                        "carProfile: {\"name\":\"Тестовая\",\"make\":\"Demo\",\"fuel\":\"PETROL\",\"engineCc\":2000}\n" +
                        "dtcHistory: $DEMO_DTC\nfindings: $DEMO_FINDINGS"
                    try {
                        val t0 = SystemClock.elapsedRealtime()
                        val a = runCatching { client.execute(prompt("digest") { system(system); user("Объясни мои ошибки.") }, model, emptyList()).textContent() }
                            .getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" }
                        log("\n## Объясни мои ошибки. (сводка в промпте)\n- ${SystemClock.elapsedRealtime() - t0} ms VmHWM=${status("VmHWM")}\n\n$a")
                    } finally {
                        client.close()
                    }
                }
                "tool" -> {
                    // Полный круг с инструментами как в чате приложения: системный промпт SystemPrompts.chat(withTools = true),
                    // инструменты dtcHistory/findings, их ответ — данные демо-адаптера (P0133, P0300 сохранены, P0171 ожидает).
                    val client = LiteRTLLMClient(LiteRTClientConfig(defaultModel = model, modelsPath = "/data/local/tmp/llm", cacheDir = context.cacheDir.absolutePath, backend = backend()))
                    val tools = listOf(
                        ToolDescriptor("dtcHistory", "История кодов ошибок (DTC) машины пользователя: живые снимки и импортированные события, с описаниями.", optionalParameters = listOf(ToolParameterDescriptor("limit", "Сколько последних кодов вернуть", ToolParameterType.Integer))),
                        ToolDescriptor("findings", "Находки анализа приложения по активной машине: правило, важность, доказательства."),
                    )
                    val results = mapOf(
                        "dtcHistory" to DEMO_DTC,
                        "findings" to DEMO_FINDINGS,
                    )
                    val system = APP_SYSTEM_PROMPT
                    var history = prompt("tool") {
                        system(system)
                        user("Объясни мои ошибки.")
                    }
                    try {
                        for (round in 0..3) {
                            val t0 = SystemClock.elapsedRealtime()
                            val r = runCatching { client.execute(history, model, if (round < 3) tools else emptyList()) }
                            val ms = SystemClock.elapsedRealtime() - t0
                            val a = r.getOrElse {
                                log("round $round ERROR ${it::class.simpleName}: ${it.message}")
                                null
                            } ?: break
                            val calls = a.parts.filterIsInstance<MessagePart.Tool.Call>()
                            log("\n## round $round — $ms ms VmHWM=${status("VmHWM")}\n" + a.parts.joinToString(" | ") { p ->
                                when (p) {
                                    is MessagePart.Text -> p.text
                                    is MessagePart.Tool.Call -> "TOOL_CALL ${p.tool}(${p.args})"
                                    else -> p::class.simpleName.orEmpty()
                                }
                            })
                            if (calls.isEmpty()) break
                            history = prompt(history) {
                                message(a)
                                user { calls.forEach { c -> toolResult(MessagePart.Tool.Result(c.id, c.tool, listOf(MessagePart.Text(results[c.tool] ?: "{\"no_data\":true}")))) } }
                            }
                        }
                    } finally {
                        client.close()
                    }
                }
                else -> {
                    val client = LiteRTLLMClient(LiteRTClientConfig(defaultModel = model, modelsPath = "/data/local/tmp/llm", cacheDir = context.cacheDir.absolutePath, backend = backend()))
                    val tool = ToolDescriptor(
                        name = "dtcHistory",
                        description = "История кодов ошибок (DTC) машины пользователя: код, описание, сколько раз, когда последний раз.",
                        requiredParameters = emptyList(),
                        optionalParameters = listOf(ToolParameterDescriptor("limit", "Сколько последних кодов вернуть", ToolParameterType.Integer)),
                    )
                    val questions = listOf("Привет! Ответь одним словом.") + OnDeviceLlmSpikeTest.QUESTIONS
                    try {
                        for ((i, q) in questions.withIndex()) {
                            val p = prompt("k-$i") {
                                system("Ты — помощник автодиагноста. Отвечай по-русски, кратко (до 6 предложений), без выдумок.")
                                user(q)
                            }
                            val t0 = SystemClock.elapsedRealtime()
                            val r = runCatching { client.execute(p, model, emptyList()) }
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

    private companion object {
        /** Ответы инструментов на данных демо-адаптера (:core:demo: P0133, P0300 сохранены, P0171 ожидает). */
        const val DEMO_DTC = """{"car":"Тестовая","codes":[{"code":"P0133","kind":"stored","description":"O2 Sensor Circuit Slow Response (Bank 1 Sensor 1)","times":1,"last":"сегодня"},{"code":"P0300","kind":"stored","description":"Random/Multiple Cylinder Misfire Detected","times":1,"last":"сегодня"},{"code":"P0171","kind":"pending","description":"System Too Lean (Bank 1)","times":1,"last":"сегодня"}]}"""
        const val DEMO_FINDINGS = """{"findings":[{"rule":"dtc_repeat","severity":"INFO","text":"Коды P0133, P0300 сохранены, P0171 ожидает подтверждения","sessions":1}]}"""

        /** = SystemPrompts.chat(withTools = true) из :core:ai (спайк не зависит от модулей приложения). */
        val APP_SYSTEM_PROMPT = """
            Ты — помощник автодиагноста в приложении ECULense. Отвечай по-русски, кратко и по делу.
            Правила:
            1. Не выдумывай значения датчиков и историю машины. Опирайся только на переданные данные; если данных нет — скажи, что проверить.
            2. Структура ответа: вероятные причины (от частых к редким) → что проверить самому → когда ехать в сервис.
            3. Если ситуация опасна (перегрев, давление масла, тормоза, запах топлива, мигающий Check Engine) — первой строкой предупреди и посоветуй не продолжать движение.
            4. Сброс ошибки — не ремонт. Не предлагай его как решение.
            У тебя есть инструменты только для чтения данных машины пользователя (профиль, поездки, записи датчиков, находки анализа, история кодов ошибок, план ТО и пробег).
            5. Перед ответом о машине вызови нужные инструменты: ошибки — dtcHistory и findings; последняя поездка — listSessions(limit=1), затем sessionStats; поездки за период — tripStats; конкретный датчик — sensorSummary; марка и двигатель — carProfile; ТО, замена масла, пробег — maintenanceDue.
            6. Все числа, даты и коды бери только из ответов инструментов. Не пересчитывай и не придумывай их; если инструмент вернул пустой результат или no_data — прямо скажи, что данных нет.
            7. Находки (findings) рассчитаны приложением по правилам — объясняй их, не спорь с числами. Отмечай, если замеров мало.
            8. VIN и координат у тебя нет, они не нужны.
        """.trimIndent()
    }
}
