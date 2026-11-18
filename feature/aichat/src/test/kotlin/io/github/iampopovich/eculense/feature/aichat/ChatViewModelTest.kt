package io.github.iampopovich.eculense.feature.aichat

import io.github.iampopovich.eculense.core.ai.ChatEvent
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.LlmGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.consumeAsFlow
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

/** Поддельный шлюз: n-й вызов chat() отдаёт поток из responder(n). */
internal class FakeGateway(
    override val hasVehicleTools: Boolean = true,
    private val responder: (Int) -> Flow<ChatEvent> = { flowOf(ChatEvent.Text("ок")) },
) : LlmGateway {
    val calls = mutableListOf<ChatCall>()
    override fun stream(system: String, history: List<ChatMessage>): Flow<String> = error("not used")
    override fun chat(conversationId: String, userText: String, useCarData: Boolean): Flow<ChatEvent> {
        calls += ChatCall(conversationId, userText, useCarData)
        return responder(calls.size)
    }
}

internal fun texts(vararg chunks: String): Flow<ChatEvent> = flowOf(*chunks.map { ChatEvent.Text(it) }.toTypedArray())

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `streamed chunks build one assistant message`() = runTest {
        val vm = ChatViewModel(FakeGateway { texts("Про", "верьте ", "датчик") }, prefill = null)
        vm.send("P0133?")
        val messages = vm.uiState.value.messages
        assertEquals(ChatMessage(ChatMessage.Role.USER, "P0133?"), messages[0])
        assertEquals(ChatMessage(ChatMessage.Role.ASSISTANT, "Проверьте датчик"), messages[1])
        assertFalse(vm.uiState.value.streaming)
    }

    @Test
    fun `tool calls show what the AI is looking at until text arrives`() = runTest {
        val events = kotlinx.coroutines.channels.Channel<ChatEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val vm = ChatViewModel(FakeGateway { events.consumeAsFlow() }, prefill = null)
        vm.send("Объясни мои ошибки")
        events.send(ChatEvent.ToolCall("dtcHistory", "ИИ смотрит ошибки…"))
        assertEquals("ИИ смотрит ошибки…", vm.uiState.value.toolStatus)
        assertTrue(vm.uiState.value.awaitingFirstToken)
        events.send(ChatEvent.ToolCall("findings", "ИИ смотрит находки анализа…"))
        assertEquals("ИИ смотрит находки анализа…", vm.uiState.value.toolStatus)
        events.send(ChatEvent.Text("P0171"))
        assertNull(vm.uiState.value.toolStatus)
        events.close()
        assertFalse(vm.uiState.value.streaming)
        assertEquals("P0171", vm.uiState.value.messages.last().text)
    }

    @Test
    fun `car data toggle is on when tools exist and is passed to the gateway`() = runTest {
        val gateway = FakeGateway()
        val vm = ChatViewModel(gateway, prefill = null)
        assertTrue(vm.uiState.value.carDataAvailable && vm.uiState.value.useCarData)
        vm.send("первый")
        vm.setUseCarData(false)
        vm.send("второй")
        assertEquals(listOf(true, false), gateway.calls.map { it.useCarData })
        // один диалог — один conversationId (историю ведёт агент)
        assertEquals(1, gateway.calls.map { it.conversationId }.distinct().size)
    }

    @Test
    fun `without tools module there is no toggle and no car data`() = runTest {
        val gateway = FakeGateway(hasVehicleTools = false)
        val vm = ChatViewModel(gateway, prefill = null)
        assertFalse(vm.uiState.value.carDataAvailable)
        vm.setUseCarData(true)
        vm.send("вопрос")
        assertFalse(gateway.calls.single().useCarData)
    }

    @Test
    fun `prefill is sent immediately`() = runTest {
        val vm = ChatViewModel(FakeGateway(), prefill = "Объясни P0300")
        assertEquals("Объясни P0300", vm.uiState.value.messages.first().text)
    }

    @Test
    fun `error is shown`() = runTest {
        val vm = ChatViewModel(FakeGateway { flow { throw IllegalStateException("ИИ не настроен") } }, prefill = null)
        vm.send("привет")
        assertEquals("ИИ не настроен", vm.uiState.value.error)
        // Пустой пузырь ответа убирается, вопрос пользователя остаётся.
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "привет")), vm.uiState.value.messages)
    }

    @Test
    fun `next question sends only the new text and clears the old error`() = runTest {
        val gateway = FakeGateway { n -> if (n == 1) flow { throw IllegalStateException("x") } else texts("ответ") }
        val vm = ChatViewModel(gateway, prefill = null)
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
        val vm = ChatViewModel(gateway, prefill = null)
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
        val vm = ChatViewModel(gateway, prefill = null)
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
        val vm = ChatViewModel(gateway, prefill = null)
        vm.send("вопрос")
        assertTrue(vm.uiState.value.canRetry)
        vm.retry()
        assertNull(vm.uiState.value.error)
        assertEquals(listOf("вопрос", "вопрос"), gateway.calls.map { it.text })
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "вопрос"), ChatMessage(ChatMessage.Role.ASSISTANT, "ок")), vm.uiState.value.messages)
        assertFalse(vm.uiState.value.canRetry)
    }
}

