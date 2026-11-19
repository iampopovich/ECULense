package io.github.iampopovich.eculense.feature.aichat

import io.github.iampopovich.eculense.core.ai.ChatEvent
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.ChatPrefs
import io.github.iampopovich.eculense.core.ai.ChatStatus
import io.github.iampopovich.eculense.core.ai.LlmGateway
import io.github.iampopovich.eculense.core.ai.ProviderInfo
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal data class ChatCall(val conversationId: String, val text: String, val useCarData: Boolean)

/** Fake gateway: the n-th chat() call returns the flow from responder(n). */
internal class FakeGateway(
    override val hasVehicleTools: Boolean = true,
    private val provider: ProviderInfo? = null,
    private val history: Map<String, List<ChatMessage>> = emptyMap(),
    private val responder: (Int) -> Flow<ChatEvent> = { flowOf(ChatEvent.Text("ок")) },
) : LlmGateway {
    val calls = mutableListOf<ChatCall>()
    val deleted = mutableListOf<String>()
    override fun stream(system: String, history: List<ChatMessage>): Flow<String> = error("not used")
    override suspend fun provider(): ProviderInfo? = provider
    override suspend fun history(conversationId: String) = history[conversationId].orEmpty()
    override suspend fun deleteConversation(conversationId: String) { deleted += conversationId }
    override fun chat(conversationId: String, userText: String, useCarData: Boolean): Flow<ChatEvent> {
        calls += ChatCall(conversationId, userText, useCarData)
        return responder(calls.size)
    }
}

internal class FakePrefs(var useCar: Boolean? = null, val consents: MutableSet<String> = mutableSetOf(), var conversation: String? = null) : ChatPrefs {
    override suspend fun hasConsent(providerKey: String) = providerKey in consents
    override suspend fun grantConsent(providerKey: String) { consents += providerKey }
    override suspend fun revokeConsents() = consents.clear()
    override suspend fun useCarData() = useCar
    override suspend fun setUseCarData(enabled: Boolean) { useCar = enabled }
    override suspend fun conversationId() = conversation
    override suspend fun setConversationId(id: String) { conversation = id }
}

private val DEEPSEEK = ProviderInfo("DEEPSEEK", "DeepSeek", "deepseek-chat")

internal fun texts(vararg chunks: String): Flow<ChatEvent> = flowOf(*chunks.map { ChatEvent.Text(it) }.toTypedArray())

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `on-device model needs no consent and shows its thinking status`() = runTest {
        val events = kotlinx.coroutines.channels.Channel<ChatEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val local = ProviderInfo("on-device", "На устройстве", "Тестовая", onDevice = true)
        val gateway = FakeGateway(provider = local) { events.consumeAsFlow() }
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        vm.send("Объясни мои ошибки")
        assertNull(vm.uiState.value.consent)
        assertTrue(vm.uiState.value.onDevice)
        assertTrue(gateway.calls.single().useCarData)
        events.trySend(ChatEvent.Status("Модель на телефоне думает…", ChatStatus.PhoneThinking))
        assertEquals(UiText.Res(R.string.aichat_status_phone_thinking), vm.uiState.value.toolStatus)
        events.trySend(ChatEvent.Text("Ответ целиком"))
        events.close()
        assertNull(vm.uiState.value.toolStatus)
        assertEquals("Ответ целиком", vm.uiState.value.messages.last().text)
    }

    @Test
    fun `streamed chunks build one assistant message`() = runTest {
        val vm = ChatViewModel(FakeGateway { texts("Про", "верьте ", "датчик") }, FakePrefs(), prefill = null)
        vm.send("P0133?")
        val messages = vm.uiState.value.messages
        assertEquals(ChatMessage(ChatMessage.Role.USER, "P0133?"), messages[0])
        assertEquals(ChatMessage(ChatMessage.Role.ASSISTANT, "Проверьте датчик"), messages[1])
        assertFalse(vm.uiState.value.streaming)
    }

    @Test
    fun `tool calls show what the AI is looking at until text arrives`() = runTest {
        val events = kotlinx.coroutines.channels.Channel<ChatEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val vm = ChatViewModel(FakeGateway { events.consumeAsFlow() }, FakePrefs(), prefill = null)
        vm.send("Объясни мои ошибки")
        events.send(ChatEvent.ToolCall("dtcHistory", "ИИ смотрит ошибки…"))
        assertEquals(UiText.Res(R.string.aichat_tool_dtc_history, listOf(UiText.Res(R.string.aichat_subject_ai))), vm.uiState.value.toolStatus)
        assertTrue(vm.uiState.value.awaitingFirstToken)
        events.send(ChatEvent.ToolCall("findings", "ИИ смотрит находки анализа…"))
        assertEquals(UiText.Res(R.string.aichat_tool_findings, listOf(UiText.Res(R.string.aichat_subject_ai))), vm.uiState.value.toolStatus)
        events.send(ChatEvent.Text("P0171"))
        assertNull(vm.uiState.value.toolStatus)
        events.close()
        assertFalse(vm.uiState.value.streaming)
        assertEquals("P0171", vm.uiState.value.messages.last().text)
    }

    @Test
    fun `car data toggle is on when tools exist and is passed to the gateway`() = runTest {
        val gateway = FakeGateway()
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        assertTrue(vm.uiState.value.carDataAvailable && vm.uiState.value.useCarData)
        vm.send("первый")
        vm.setUseCarData(false)
        vm.send("второй")
        assertEquals(listOf(true, false), gateway.calls.map { it.useCarData })
        // one dialog = one conversationId (the agent keeps the history)
        assertEquals(1, gateway.calls.map { it.conversationId }.distinct().size)
    }

    @Test
    fun `without tools module there is no toggle and no car data`() = runTest {
        val gateway = FakeGateway(hasVehicleTools = false)
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        assertFalse(vm.uiState.value.carDataAvailable)
        vm.setUseCarData(true)
        vm.send("вопрос")
        assertFalse(gateway.calls.single().useCarData)
    }

    @Test
    fun `first question with car data asks for consent and sends nothing until accepted`() = runTest {
        val gateway = FakeGateway(provider = DEEPSEEK)
        val prefs = FakePrefs()
        val vm = ChatViewModel(gateway, prefs, prefill = null)
        vm.send("Объясни мои ошибки")
        assertEquals(ConsentRequest(DEEPSEEK, "Объясни мои ошибки"), vm.uiState.value.consent)
        assertTrue(gateway.calls.isEmpty())
        assertTrue(vm.uiState.value.messages.isEmpty())

        vm.acceptConsent()
        assertEquals(setOf("DEEPSEEK"), prefs.consents)
        assertNull(vm.uiState.value.consent)
        assertEquals(listOf(ChatCall(gateway.calls.single().conversationId, "Объясни мои ошибки", true)), gateway.calls)

        // consent remembered: the second question is sent immediately
        vm.send("Как прошла последняя поездка?")
        assertNull(vm.uiState.value.consent)
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun `declining sends without car data and switches the toggle off for next time`() = runTest {
        val gateway = FakeGateway(provider = DEEPSEEK)
        val prefs = FakePrefs()
        val vm = ChatViewModel(gateway, prefs, prefill = null)
        vm.send("вопрос")
        vm.declineConsent()
        assertFalse(gateway.calls.single().useCarData)
        assertFalse(vm.uiState.value.useCarData)
        assertEquals(false, prefs.useCar)
        assertTrue(prefs.consents.isEmpty())
    }

    @Test
    fun `cancel returns the question to the input`() = runTest {
        val vm = ChatViewModel(FakeGateway(provider = DEEPSEEK), FakePrefs(), prefill = null)
        vm.send("вопрос")
        vm.cancelConsent()
        assertNull(vm.uiState.value.consent)
        assertEquals("вопрос", vm.uiState.value.draft)
        vm.draftConsumed()
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun `prefill from a finding also waits for consent, other provider asks again`() = runTest {
        val prefs = FakePrefs(consents = mutableSetOf("OPENROUTER"))
        val vm = ChatViewModel(FakeGateway(provider = DEEPSEEK), prefs, prefill = "Объясни находку")
        assertEquals("Объясни находку", vm.uiState.value.consent?.text)
    }

    @Test
    fun `saved toggle choice is restored and no consent without car data`() = runTest {
        val gateway = FakeGateway(provider = DEEPSEEK)
        val vm = ChatViewModel(gateway, FakePrefs(useCar = false), prefill = null)
        assertFalse(vm.uiState.value.useCarData)
        vm.send("Что такое P0300?")
        assertNull(vm.uiState.value.consent)
        assertFalse(gateway.calls.single().useCarData)
    }

    @Test
    fun `saved conversation is shown after restart and continues with the same id`() = runTest {
        val saved = listOf(ChatMessage(ChatMessage.Role.USER, "старый вопрос"), ChatMessage(ChatMessage.Role.ASSISTANT, "старый ответ"))
        val gateway = FakeGateway(history = mapOf("c-old" to saved))
        val vm = ChatViewModel(gateway, FakePrefs(conversation = "c-old"), prefill = null)
        assertEquals(saved, vm.uiState.value.messages)
        vm.send("новый")
        assertEquals("c-old", gateway.calls.single().conversationId)
        assertEquals(4, vm.uiState.value.messages.size)
    }

    @Test
    fun `new dialog clears the screen, deletes the old one and uses a new id`() = runTest {
        val gateway = FakeGateway(history = mapOf("c-old" to listOf(ChatMessage(ChatMessage.Role.USER, "q"))))
        val prefs = FakePrefs(conversation = "c-old")
        val vm = ChatViewModel(gateway, prefs, prefill = null)
        vm.newDialog()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals(listOf("c-old"), gateway.deleted)
        assertTrue(prefs.conversation != "c-old" && prefs.conversation != null)
        vm.send("привет")
        assertEquals(prefs.conversation, gateway.calls.single().conversationId)
    }

    @Test
    fun `first start saves a conversation id`() = runTest {
        val prefs = FakePrefs()
        ChatViewModel(FakeGateway(), prefs, prefill = null)
        assertTrue(prefs.conversation != null)
    }

    @Test
    fun `experts and their notes are attached to the answer they worked on`() = runTest {
        val notes = listOf(
            io.github.iampopovich.eculense.core.ai.ExpertNote("usage", "Аналитик эксплуатации", "- поездок 12"),
            io.github.iampopovich.eculense.core.ai.ExpertNote("engine", "Моторист", "недоступен", failed = true),
        )
        val gateway = FakeGateway(hasVehicleTools = false) { n ->
            if (n == 1) {
                flowOf(
                    ChatEvent.Status("Аналитик эксплуатации смотрит поездки за 6 месяцев…", ChatStatus.Role("usage", 180)),
                    ChatEvent.Experts(listOf("Аналитик эксплуатации", "Моторист"), notes, listOf("usage", "engine")),
                    ChatEvent.Text("Коротко: всё ровно"),
                )
            } else {
                texts("второй")
            }
        }
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        vm.send("как машина вела себя за полгода")
        val s = vm.uiState.value
        assertEquals(ExpertsInfo(listOf(UiText.Res(R.string.aichat_role_usage), UiText.Res(R.string.aichat_role_engine)), notes), s.experts[1])
        // next answer has no experts: it gets no label of its own, the first one keeps its label
        vm.send("привет")
        assertEquals(setOf(1), vm.uiState.value.experts.keys)
        vm.newDialog()
        assertTrue(vm.uiState.value.experts.isEmpty())
    }

    @Test
    fun `prefill is sent immediately`() = runTest {
        val vm = ChatViewModel(FakeGateway(), FakePrefs(), prefill = "Объясни P0300")
        assertEquals("Объясни P0300", vm.uiState.value.messages.first().text)
    }

    @Test
    fun `error is shown`() = runTest {
        val vm = ChatViewModel(FakeGateway { flow { throw IllegalStateException("ИИ не настроен") } }, FakePrefs(), prefill = null)
        vm.send("привет")
        assertEquals(UiText.Raw("ИИ не настроен"), vm.uiState.value.error)
        // The empty answer bubble is removed, the user's question stays.
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "привет")), vm.uiState.value.messages)
    }

    @Test
    fun `next question sends only the new text and clears the old error`() = runTest {
        val gateway = FakeGateway { n -> if (n == 1) flow { throw IllegalStateException("x") } else texts("ответ") }
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        vm.send("первый")
        vm.send("второй")
        assertEquals(listOf("первый", "второй"), gateway.calls.map { it.text })
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `stop cancels the stream and keeps the received text`() = runTest {
        var cancelled = false
        val gateway = FakeGateway {
            flow {
                emit(ChatEvent.Text("Частичный "))
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        vm.send("вопрос")
        assertTrue(vm.uiState.value.streaming)
        assertFalse(vm.uiState.value.awaitingFirstToken)

        vm.stop()

        assertTrue(cancelled)
        assertFalse(vm.uiState.value.streaming)
        assertEquals("Частичный ", vm.uiState.value.messages.last().text)
        assertEquals(1, vm.uiState.value.stoppedIndex)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `stop before the first token removes the empty answer and allows a new question`() = runTest {
        val gateway = FakeGateway { n -> if (n == 1) flow { awaitCancellation() } else texts("ответ") }
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        vm.send("первый")
        assertTrue(vm.uiState.value.awaitingFirstToken)
        vm.stop()
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "первый")), vm.uiState.value.messages)
        assertNull(vm.uiState.value.stoppedIndex)

        vm.send("второй")
        assertFalse(vm.uiState.value.streaming)
        assertEquals("ответ", vm.uiState.value.messages.last().text)
    }

    @Test
    fun `retry resends the unanswered question after an error`() = runTest {
        val gateway = FakeGateway { n -> if (n == 1) flow { throw IllegalStateException("Нет связи") } else texts("ок") }
        val vm = ChatViewModel(gateway, FakePrefs(), prefill = null)
        vm.send("вопрос")
        assertTrue(vm.uiState.value.canRetry)
        vm.retry()
        assertNull(vm.uiState.value.error)
        assertEquals(listOf("вопрос", "вопрос"), gateway.calls.map { it.text })
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "вопрос"), ChatMessage(ChatMessage.Role.ASSISTANT, "ок")), vm.uiState.value.messages)
        assertFalse(vm.uiState.value.canRetry)
    }
}

