package io.github.iampopovich.eculense.feature.aisettings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.LlmException
import io.github.iampopovich.eculense.core.ai.LlmGateway
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.LlmSettings
import io.github.iampopovich.eculense.core.ai.LlmSettingsStore
import io.github.iampopovich.eculense.core.ai.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakeGateway(private val result: Result<String>) : LlmGateway {
    var pings = 0
    override fun stream(system: String, history: List<ChatMessage>): Flow<String> = emptyFlow()
    override suspend fun ping(): Result<String> = result.also { pings++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reversing = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(encoded: String) = encoded.reversed()
    }
    private lateinit var store: LlmSettingsStore

    // Реальный DataStore работает на IO, поэтому Main = Unconfined и ожидание по состоянию.
    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        store = LlmSettingsStore(PreferenceDataStoreFactory.create(scope = io) { File(tmp.root, "s.preferences_pb") }, reversing)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        io.cancel()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timeout" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `defaults to OpenRouter when nothing is saved`() {
        val vm = AiSettingsViewModel(store, FakeGateway(Result.success("ок")))
        assertEquals(LlmProviderKind.OPENROUTER, vm.form.value.provider)
        assertFalse(vm.form.value.hasSavedKey)
    }

    @Test
    fun `save stores key, clears the field and reports success`() = runBlocking {
        val gateway = FakeGateway(Result.success("ок"))
        val vm = AiSettingsViewModel(store, gateway)
        vm.update { it.copy(apiKey = "sk-or-1") }
        vm.saveAndTest()
        waitUntil { vm.form.value.status?.startsWith("Работает") == true }

        assertEquals("Работает: ок", vm.form.value.status)
        assertEquals("", vm.form.value.apiKey)
        assertTrue(vm.form.value.hasSavedKey)
        assertEquals("sk-or-1", store.apiKey())
        // Пустая модель = подсказка провайдера, иначе OpenRouter ответит 400.
        assertEquals(LlmSettings(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, true), store.settings.first())
        assertEquals(1, gateway.pings)
    }

    @Test
    fun `failed check shows the readable error`() {
        val vm = AiSettingsViewModel(store, FakeGateway(Result.failure(LlmException("Ошибка авторизации (401): неверный ключ", 401))))
        vm.update { it.copy(apiKey = "sk-or-invalid") }
        vm.saveAndTest()
        waitUntil { vm.form.value.status?.startsWith("Ошибка") == true }
        assertEquals("Ошибка: Ошибка авторизации (401): неверный ключ", vm.form.value.status)
    }

    @Test
    fun `saved settings are loaded and key can be removed`() = runBlocking {
        store.save(LlmProviderKind.OPENAI, "gpt-4.1-mini", null, "sk-1")
        val vm = AiSettingsViewModel(store, FakeGateway(Result.success("ок")))
        waitUntil { vm.form.value.hasSavedKey }
        assertEquals(LlmProviderKind.OPENAI, vm.form.value.provider)
        assertEquals("gpt-4.1-mini", vm.form.value.model)

        vm.clearKey()
        waitUntil { !vm.form.value.hasSavedKey }
        assertEquals(null, store.apiKey())
    }
}
