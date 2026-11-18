package io.github.iampopovich.eculense.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Живой тест, только по переменным окружения (ключ не коммитим и не печатаем):
 * LLM_IT_PROVIDER=OPENROUTER LLM_IT_MODEL=openai/gpt-4.1-mini LLM_IT_KEY=… ./gradlew :core:ai:testDebugUnitTest --tests '*LlmLiveTest*' --rerun -i
 */
class LlmLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `streams an answer from a real provider`() = runBlocking<Unit> {
        val provider = System.getenv("LLM_IT_PROVIDER")
        assumeTrue("задайте LLM_IT_PROVIDER", provider != null)
        val plain = object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(encoded: String) = encoded
        }
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(tmp.root, "it.preferences_pb") }
        val store = LlmSettingsStore(dataStore, plain)
        store.save(LlmProviderKind.valueOf(provider!!), System.getenv("LLM_IT_MODEL"), System.getenv("LLM_IT_BASEURL"), System.getenv("LLM_IT_KEY"))
        val answer = KoogLlmGateway(store).stream(SystemPrompts.MECHANIC, listOf(ChatMessage(ChatMessage.Role.USER, "Что значит P0133? Одно предложение."))).toList().joinToString("")
        println("answer=$answer")
        assertTrue(answer.isNotBlank())
    }
}
