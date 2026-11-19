package io.github.iampopovich.eculense.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Chat history in Room (ai_chat.db) survives a "restart": a new instance on top of the same database. */
@RunWith(AndroidJUnit4::class)
class RoomChatHistoryTest {
    @Test
    fun conversationSurvivesReopenAndLoadsLastWindow() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("ai-chat-test.db")
        fun open() = Room.databaseBuilder(context, AiChatDatabase::class.java, "ai-chat-test.db").build()
        val turns = (1..12).flatMap { listOf(Message.User("q$it", RequestMetaInfo.Empty), Message.Assistant("a$it", ResponseMetaInfo.Empty)) }

        val db1 = open()
        val h1 = RoomChatHistory(db1.messages())
        h1.store("c", turns.take(2))
        h1.store("c", h1.load("c") + turns.drop(2))
        db1.close()

        val db2 = open()
        try {
            val h2 = RoomChatHistory(db2.messages())
            assertEquals(24, h2.transcript("c").size)
            assertEquals(ChatMessage(ChatMessage.Role.USER, "q1"), h2.transcript("c").first())
            assertEquals(ChatHistoryStore.WINDOW, h2.load("c").size)
            assertEquals("a12", h2.load("c").last().textContent())
            h2.delete("c")
            assertEquals(0, h2.transcript("c").size)
        } finally {
            db2.close()
            context.deleteDatabase("ai-chat-test.db")
        }
    }
}
