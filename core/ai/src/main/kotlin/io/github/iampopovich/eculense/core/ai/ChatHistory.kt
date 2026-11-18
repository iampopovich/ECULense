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
 * История чата ИИ — своя маленькая база ai_chat.db внутри :core:ai: модуль ИИ выпиливается вместе с ней,
 * основная база (поездки, 1,3 млн значений) ничего о чате не знает. Только тексты вопросов и ответов.
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

/** Хранилище истории для ChatMemory Koog + полный текст диалога для экрана. */
internal interface ChatHistoryStore : ChatHistoryProvider {
    suspend fun transcript(conversationId: String): List<ChatMessage>
    suspend fun delete(conversationId: String)

    companion object {
        /** Сколько последних сообщений уходит модели как контекст (≈10 вопросов-ответов): дальше — лишние токены. */
        const val WINDOW = 20
    }
}

/**
 * ChatMemory сохраняет весь промпт хода: загруженное окно истории + новые сообщения. Чтобы не терять то, что
 * старше окна, дописываем только хвост, которого ещё нет: ищем самый длинный конец сохранённого, совпадающий
 * с началом пришедшего.
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

/** Для тестов и как запасной вариант: то же поведение в памяти. */
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
