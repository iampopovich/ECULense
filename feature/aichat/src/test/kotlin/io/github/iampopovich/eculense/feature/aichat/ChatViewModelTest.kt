package io.github.iampopovich.eculense.feature.aichat

import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.LlmGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
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

private class FakeGateway(private val chunks: List<String>, private val fail: Boolean = false) : LlmGateway {
    val histories = mutableListOf<List<ChatMessage>>()
    override fun stream(system: String, history: List<ChatMessage>): Flow<String> {
        histories += history
        return if (fail) flow { throw IllegalStateException("ИИ не настроен") } else flowOf(*chunks.toTypedArray())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `streamed chunks build one assistant message`() = runTest {
        val vm = ChatViewModel(FakeGateway(listOf("Про", "верьте ", "датчик")), prefill = null)
        vm.send("P0133?")
        val messages = vm.uiState.value.messages
        assertEquals(ChatMessage(ChatMessage.Role.USER, "P0133?"), messages[0])
        assertEquals(ChatMessage(ChatMessage.Role.ASSISTANT, "Проверьте датчик"), messages[1])
        assertFalse(vm.uiState.value.streaming)
    }

    @Test
    fun `prefill is sent immediately`() = runTest {
        val vm = ChatViewModel(FakeGateway(listOf("ок")), prefill = "Объясни P0300")
        assertEquals("Объясни P0300", vm.uiState.value.messages.first().text)
    }

    @Test
    fun `error is shown`() = runTest {
        val vm = ChatViewModel(FakeGateway(emptyList(), fail = true), prefill = null)
        vm.send("привет")
        assertEquals("ИИ не настроен", vm.uiState.value.error)
        // Пустой пузырь ответа убирается, вопрос пользователя остаётся.
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "привет")), vm.uiState.value.messages)
    }

    @Test
    fun `next question sends the whole history and clears the old error`() = runTest {
        val gateway = FakeGateway(listOf("ответ"))
        val vm = ChatViewModel(gateway, prefill = null)
        vm.send("первый")
        vm.send("второй")
        assertEquals(
            listOf(
                ChatMessage(ChatMessage.Role.USER, "первый"),
                ChatMessage(ChatMessage.Role.ASSISTANT, "ответ"),
                ChatMessage(ChatMessage.Role.USER, "второй"),
            ),
            gateway.histories.last(),
        )
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `stop cancels the stream and keeps the received text`() = runTest {
        var cancelled = false
        val gateway = object : LlmGateway {
            override fun stream(system: String, history: List<ChatMessage>): Flow<String> = flow {
                emit("Частичный ")
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
        val gateway = object : LlmGateway {
            var calls = 0
            override fun stream(system: String, history: List<ChatMessage>): Flow<String> {
                calls++
                return if (calls == 1) flow { awaitCancellation() } else flowOf("ответ")
            }
        }
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
        val gateway = object : LlmGateway {
            var calls = 0
            val histories = mutableListOf<List<ChatMessage>>()
            override fun stream(system: String, history: List<ChatMessage>): Flow<String> {
                calls++
                histories += history
                return if (calls == 1) flow { throw IllegalStateException("Нет связи") } else flowOf("ок")
            }
        }
        val vm = ChatViewModel(gateway, prefill = null)
        vm.send("вопрос")
        assertTrue(vm.uiState.value.canRetry)
        vm.retry()
        assertNull(vm.uiState.value.error)
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "вопрос")), gateway.histories.last())
        assertEquals("ок", vm.uiState.value.messages.last().text)
        assertFalse(vm.uiState.value.canRetry)
    }
}
