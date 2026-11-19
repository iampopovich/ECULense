package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI chat history — its own small database ai_chat.db inside :core:ai: the AI module is removed together with it,
 * and the main database (trips, 1.3M values) knows nothing about the chat. Only question and answer texts.
 */
@Entity(tableName = "chat_messages", indices = [Index(value = ["conversationId", "position"], unique = true)])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val position: Int,
    val role: String,
    val text: String,
    val createdAtMs: Long,
)

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY position")
    suspend fun messages(conversationId: String): List<ChatMessageEntity>

    @Insert suspend fun insertAll(items: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)
}

@Database(entities = [ChatMessageEntity::class], version = 1)
abstract class AiChatDatabase : RoomDatabase() {
    abstract fun messages(): ChatMessageDao
}

/** History storage for Koog ChatMemory + full dialogue text for the screen. */
internal interface ChatHistoryStore : ChatHistoryProvider {
    suspend fun transcript(conversationId: String): List<ChatMessage>
    suspend fun delete(conversationId: String)

    companion object {
        /** How many recent messages go to the model as context (≈10 question-answer pairs): beyond that it's wasted tokens. */
        const val WINDOW = 20
    }
}

/**
 * ChatMemory saves the whole turn prompt: the loaded history window + new messages. To avoid losing what is
 * older than the window, we append only the tail that isn't there yet: find the longest end of the stored list
 * that matches the start of the incoming one.
 */
internal object HistoryMerge {
    fun newTail(stored: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        for (k in minOf(stored.size, incoming.size) downTo 1) {
            if (stored.takeLast(k) == incoming.take(k)) return incoming.drop(k)
        }
        return incoming
    }
}

internal fun Message.toChatMessage(): ChatMessage? = when (this) {
    is Message.User -> ChatMessage(ChatMessage.Role.USER, textContent())
    is Message.Assistant -> ChatMessage(ChatMessage.Role.ASSISTANT, textContent())
    else -> null
}?.takeIf { it.text.isNotBlank() }

internal fun ChatMessage.toKoog(): Message = when (role) {
    ChatMessage.Role.USER -> Message.User(text, RequestMetaInfo.Empty)
    ChatMessage.Role.ASSISTANT -> Message.Assistant(text, ResponseMetaInfo.Empty)
}

internal class RoomChatHistory(private val dao: ChatMessageDao, private val clock: () -> Long = System::currentTimeMillis) : ChatHistoryStore {
    private val mutex = Mutex()

    override suspend fun store(conversationId: String, messages: List<Message>) = mutex.withLock {
        val stored = dao.messages(conversationId)
        val tail = HistoryMerge.newTail(stored.map { ChatMessage(ChatMessage.Role.valueOf(it.role), it.text) }, messages.mapNotNull { it.toChatMessage() })
        val now = clock()
        dao.insertAll(tail.mapIndexed { i, m -> ChatMessageEntity(conversationId = conversationId, position = stored.size + i, role = m.role.name, text = m.text, createdAtMs = now) })
    }

    override suspend fun load(conversationId: String): List<Message> =
        transcript(conversationId).takeLast(ChatHistoryStore.WINDOW).map { it.toKoog() }

    override suspend fun transcript(conversationId: String): List<ChatMessage> =
        dao.messages(conversationId).mapNotNull { e -> runCatching { ChatMessage(ChatMessage.Role.valueOf(e.role), e.text) }.getOrNull() }

    override suspend fun delete(conversationId: String) = dao.delete(conversationId)
}

/** For tests and as a fallback: the same behavior in memory. */
internal class InMemoryChatHistory : ChatHistoryStore {
    private val mutex = Mutex()
    private val all = mutableMapOf<String, List<ChatMessage>>()

    override suspend fun store(conversationId: String, messages: List<Message>) = mutex.withLock {
        val stored = all[conversationId].orEmpty()
        all[conversationId] = stored + HistoryMerge.newTail(stored, messages.mapNotNull { it.toChatMessage() })
    }

    override suspend fun load(conversationId: String): List<Message> =
        transcript(conversationId).takeLast(ChatHistoryStore.WINDOW).map { it.toKoog() }

    override suspend fun transcript(conversationId: String): List<ChatMessage> = mutex.withLock { all[conversationId].orEmpty() }

    override suspend fun delete(conversationId: String) {
        mutex.withLock { all.remove(conversationId) }
    }
}
