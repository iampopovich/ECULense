package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Оркестратор экспертов на подставном исполнителе: исполнитель узнаёт роль по системному промпту («Ты — Диагност»),
 * первым кругом вызывает инструмент своей роли, вторым пишет заметку; редактор («Ты — Редактор») стримит ответ.
 */
class ExpertOrchestratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val plain = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(encoded: String) = encoded
    }

    @After fun tearDown() = scope.cancel()

    private suspend fun store(): LlmSettingsStore {
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s${System.nanoTime()}.preferences_pb") }
        return LlmSettingsStore(ds, plain).also { it.save(LlmProviderKind.DEEPSEEK, "deepseek-chat", null, "k") }
    }

    @Serializable
    data class Args(@property:LLMDescription("days") val days: Int? = null, @property:LLMDescription("limit") val limit: Int? = null)

    /** Все инструменты :core:aitools по имени; вызовы пишутся (имя, days). */
    private class Toolbox : VehicleToolbox {
        val calls: MutableList<Pair<String, Int?>> = Collections.synchronizedList(mutableListOf())
        val names = listOf("carProfile", "tripStats", "listSessions", "sessionStats", "sensorSummary", "findings", "dtcHistory", "maintenanceDue", "trends")
        override fun registry() = ToolRegistry {
            names.forEach { n ->
                tool(object : SimpleTool<Args>(typeToken<Args>(), n, "tool $n") {
                    override suspend fun execute(args: Args): String {
                        calls += n to args.days
                        delay(1)
                        return """{"tool":"$n","value":42}"""
                    }
                })
            }
        }
        override fun progressLabel(toolName: String) = if (toolName == "trends") "ИИ смотрит тренды по месяцам…" else "ИИ смотрит $toolName…"
    }

    /** Какой инструмент роль вызывает первым кругом. */
    private val firstTool = mapOf("usage" to "trends", "diagnostician" to "dtcHistory", "engine" to "trends", "fuel" to "tripStats")

    private class RoleExecutor(val firstTool: Map<String, String>, val failing: Set<String> = emptySet()) : PromptExecutor() {
        val systems: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val offered: MutableMap<String, MutableSet<String>> = Collections.synchronizedMap(mutableMapOf())
        val editorPrompts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private fun roleOf(system: String) = ExpertRoles.ALL.firstOrNull { system.contains("Ты — ${it.title}.") }

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("not used")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() = Unit

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            val system = prompt.messages.first().textContent()
            systems += system
            if (system.contains("Ты — Редактор.")) {
                editorPrompts += system
                return text("Коротко: машина в целом в порядке. ", "За 6 месяцев расход стабилен.")
            }
            val role = roleOf(system) ?: return text("Ответ без роли")
            offered.getOrPut(role.id) { Collections.synchronizedSet(mutableSetOf()) } += tools.map { it.name }
            if (role.id in failing) return flow { throw IllegalStateException("сеть упала у ${role.id}") }
            val hasResult = prompt.messages.any { m -> m.parts.any { it is MessagePart.Tool.Result } }
            if (!hasResult && tools.isNotEmpty()) {
                val tool = firstTool[role.id] ?: tools.first().name
                val args = if (tool == "trends" || tool == "tripStats") """{"days":180}""" else "{}"
                return flowOf(StreamFrame.ToolCallComplete("call_${role.id}", tool, args), StreamFrame.End("tool_calls"))
            }
            return text("- Заметка ${role.id}: значение 42 → норма\n", "Уверенность: средняя")
        }

        private fun text(vararg chunks: String) =
            flowOf(*(chunks.map { StreamFrame.TextDelta(it) } + StreamFrame.TextComplete(chunks.joinToString("")) + StreamFrame.End("stop")).toTypedArray())
    }

    private suspend fun gateway(exec: PromptExecutor, toolbox: VehicleToolbox, timeout: Long = 10_000) =
        KoogLlmGateway(store(), toolbox = toolbox, executorFactory = { _, _ -> exec }, expertTimeoutMs = timeout, expertLog = {})

    @Test
    fun `half a year question runs usage, diagnostician and engine in parallel with their own tools, editor gets the notes`() = runBlocking {
        val exec = RoleExecutor(firstTool)
        val toolbox = Toolbox()
        val gw = gateway(exec, toolbox)

        val events = gw.chat("c1", "как моя машина ведет себя последние полгода", useCarData = true).toList()

        assertEquals(setOf("usage", "diagnostician", "engine"), exec.offered.keys)
        // у каждого эксперта — только его инструменты
        exec.offered.forEach { (id, names) -> assertEquals(id, ExpertRoles.byId(id)!!.tools, names) }
        // период 180 дней — в промпте эксперта и в аргументах инструментов
        assertTrue(exec.systems.filter { it.contains("Ты — Аналитик эксплуатации.") }.all { it.contains("days=180") })
        assertEquals(2, toolbox.calls.count { it == ("trends" to 180) })
        assertTrue(toolbox.calls.contains("dtcHistory" to null))
        // редактор получил заметки всех трёх и форму ответа «обзор/тренд», а не «причины → проверить»
        val editor = exec.editorPrompts.single()
        listOf("usage", "diagnostician", "engine").forEach { assertTrue(editor, editor.contains("Заметка $it")) }
        assertTrue(editor, editor.contains("что изменилось за период"))
        assertFalse(editor, editor.contains("что проверить самому"))
        // статусы ролей с периодом, «Над ответом работали», ответ редактора
        val statuses = events.filterIsInstance<ChatEvent.Status>().map { it.label }
        assertTrue(statuses.toString(), statuses.contains("Аналитик эксплуатации смотрит поездки за 6 месяцев…"))
        assertTrue(events.filterIsInstance<ChatEvent.ToolCall>().map { it.label }.contains("Аналитик эксплуатации смотрит тренды по месяцам…"))
        val experts = events.filterIsInstance<ChatEvent.Experts>().single()
        assertEquals(listOf("Аналитик эксплуатации", "Диагност", "Моторист"), experts.roles)
        assertEquals(3, experts.notes.size)
        assertTrue(experts.notes.none { it.failed })
        val answer = events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta }
        assertEquals("Коротко: машина в целом в порядке. За 6 месяцев расход стабилен.", answer)
        // заметки экспертов пользователю не стримились, в истории — только вопрос и итоговый ответ
        assertFalse(answer.contains("Заметка"))
        assertEquals(listOf("как моя машина ведет себя последние полгода", answer), gw.history("c1").map { it.text })
    }

    @Test
    fun `a failing expert does not break the answer`() = runBlocking {
        val exec = RoleExecutor(firstTool, failing = setOf("engine"))
        val events = gateway(exec, Toolbox()).chat("c1", "как моя машина ведет себя последние полгода", useCarData = true).toList()
        val notes = events.filterIsInstance<ChatEvent.Experts>().single().notes
        assertTrue(notes.single { it.roleId == "engine" }.failed)
        assertTrue(exec.editorPrompts.single().contains("Эксперт «Моторист» недоступен"))
        assertTrue(events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta }.startsWith("Коротко"))
    }

    @Test
    fun `a slow expert times out and the editor still answers`() = runBlocking {
        val slow = object : PromptExecutor() {
            val inner = RoleExecutor(firstTool)
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("not used")
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
            override fun close() = Unit
            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
                val sys = prompt.messages.first().textContent()
                if (sys.contains("Ты — Диагност.")) return flow { delay(60_000) }
                return inner.executeStreaming(prompt, model, tools)
            }
        }
        val events = gateway(slow, Toolbox(), timeout = 300).chat("c1", "как моя машина ведет себя последние полгода", useCarData = true).toList()
        assertTrue(events.filterIsInstance<ChatEvent.Experts>().single().notes.single { it.roleId == "diagnostician" }.failed)
        assertTrue(slow.inner.editorPrompts.single().contains("Диагност"))
    }

    @Test
    fun `one expert answers by itself, streamed, without the editor`() = runBlocking {
        val exec = RoleExecutor(firstTool)
        val toolbox = Toolbox()
        val events = gateway(exec, toolbox).chat("c1", "сколько я трачу на бензин", useCarData = true).toList()
        assertTrue(exec.editorPrompts.isEmpty())
        assertEquals(setOf("fuel"), exec.offered.keys)
        assertEquals(ExpertRoles.FUEL.tools, exec.offered.getValue("fuel"))
        // прямой ответ роли — форма «расход и сравнение», не заметка для редактора
        val system = exec.systems.first()
        assertTrue(system, system.contains("цифры расхода и трат"))
        assertFalse(system, system.contains("заметку для редактора"))
        assertEquals(listOf("Экономист"), events.filterIsInstance<ChatEvent.Experts>().single().roles)
        assertTrue(events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta }.contains("Заметка fuel"))
        assertEquals(ChatEvent.Status("Экономист считает расход за 6 месяцев…"), events.first())
    }

    @Test
    fun `greeting goes without experts and without tools`() = runBlocking {
        val exec = RoleExecutor(firstTool)
        val events = gateway(exec, Toolbox()).chat("c1", "привет", useCarData = true).toList()
        assertTrue(exec.offered.isEmpty())
        assertTrue(events.none { it is ChatEvent.Experts || it is ChatEvent.ToolCall })
        assertEquals("Ответ без роли", events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta })
    }

    // --- Модель на телефоне: один вызов «сразу в ролях», сводка только из нужных разделов.
    private class WholeAnswerExecutor : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts += prompt
            return Message.Assistant("Ответ модели на телефоне", ai.koog.prompt.message.ResponseMetaInfo.Empty)
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("не стримит")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() = Unit
    }

    private class DigestToolbox : VehicleToolbox {
        val requests = mutableListOf<Pair<List<String>, Int>>()
        override fun registry() = ToolRegistry.EMPTY
        override fun progressLabel(toolName: String) = toolName
        override suspend fun digest(sections: List<String>, days: Int): String {
            requests += sections to days
            return sections.joinToString("\n") { "Раздел $it" }
        }
    }

    @Test
    fun `on-device trend question gets routed digest sections and the roles in one call`() = runBlocking {
        val exec = WholeAnswerExecutor()
        val toolbox = DigestToolbox()
        val info = ProviderInfo("on-device", "На устройстве", "Тестовая модель", onDevice = true)
        val model = LLModel(ai.koog.prompt.llm.LLMProvider.OpenAI, "test.litertlm", listOf(ai.koog.prompt.llm.LLMCapability.Completion), contextLength = 4096)
        val onDevice = object : OnDeviceLlm {
            override suspend fun session() = OnDeviceSession(info, model, exec, streaming = false, supportsTools = false)
            override val summary = flowOf<OnDeviceSummary?>(null)
            override suspend fun deactivate() = Unit
        }
        val gw = KoogLlmGateway(store(), toolbox = toolbox, onDevice = onDevice, executorFactory = { _, _ -> error("облако не нужно") })
        val events = gw.chat("c1", "how did my car behave over the last 6 months", useCarData = true).toList()

        assertEquals(1, exec.prompts.size)
        val (sections, days) = toolbox.requests.single()
        assertEquals(180, days)
        assertEquals(listOf("carProfile", "trends"), sections.take(2))
        val system = exec.prompts.single().messages.first().textContent()
        assertTrue(system, system.contains("Ты сразу в нескольких ролях") && system.contains("Аналитик эксплуатации"))
        assertTrue(system, system.contains("что изменилось за период"))
        assertFalse(system, system.contains("что проверить самому"))
        assertTrue(system, system.indexOf("Раздел trends") < system.indexOf("Раздел dtcHistory"))
        assertEquals(listOf("Аналитик эксплуатации", "Диагност", "Моторист"), events.filterIsInstance<ChatEvent.Experts>().single().roles)
    }
}
