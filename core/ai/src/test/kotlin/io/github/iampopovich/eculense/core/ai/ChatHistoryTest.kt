package io.github.iampopovich.eculense.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryTest {
    private fun u(t: String) = ChatMessage(ChatMessage.Role.USER, t)
    private fun a(t: String) = ChatMessage(ChatMessage.Role.ASSISTANT, t)
    private fun ku(t: String) = Message.User(t, RequestMetaInfo.Empty)
    private fun ka(t: String) = Message.Assistant(t, ResponseMetaInfo.Empty)

    @Test
    fun `merge appends only the new tail`() {
        assertEquals(listOf(u("q1"), a("a1")), HistoryMerge.newTail(emptyList(), listOf(u("q1"), a("a1"))))
        assertEquals(listOf(u("q2"), a("a2")), HistoryMerge.newTail(listOf(u("q1"), a("a1")), listOf(u("q1"), a("a1"), u("q2"), a("a2"))))
        // repeating the same question is also a new turn
        assertEquals(listOf(u("q"), a("a")), HistoryMerge.newTail(listOf(u("q"), a("a")), listOf(u("q"), a("a"), u("q"), a("a"))))
        // a window from the end of a long history is loaded
        val stored = (1..30).flatMap { listOf(u("q$it"), a("a$it")) }
        val window = stored.takeLast(ChatHistoryStore.WINDOW)
        assertEquals(listOf(u("new"), a("ans")), HistoryMerge.newTail(stored, window + listOf(u("new"), a("ans"))))
    }

    @Test
    fun `store keeps the whole conversation, load gives the last window`() = runBlocking {
        val h = InMemoryChatHistory()
        val all = (1..15).flatMap { listOf(ku("q$it"), ka("a$it")) }
        h.store("c", all.take(2))
        // next turn: ChatMemory saves the window + the new part
        h.store("c", h.load("c") + all.drop(2))
        assertEquals(30, h.transcript("c").size)
        assertEquals(ChatHistoryStore.WINDOW, h.load("c").size)
        assertEquals("a15", h.load("c").last().textContent())
        h.store("c", h.load("c") + listOf(ku("q16"), ka("a16")))
        assertEquals(32, h.transcript("c").size)
        assertEquals(emptyList<ChatMessage>(), h.transcript("other"))
        h.delete("c")
        assertEquals(emptyList<ChatMessage>(), h.transcript("c"))
    }
}
