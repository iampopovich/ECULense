package io.github.iampopovich.eculense.feature.aichat

import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.LlmGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
