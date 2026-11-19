package io.github.iampopovich.eculense.feature.aisettings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iampopovich.eculense.core.ai.ConnectionCheck
import io.github.iampopovich.eculense.core.ai.ConnectionChecker
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.LlmSettings
import io.github.iampopovich.eculense.core.ai.LlmSettingsStore
import io.github.iampopovich.eculense.core.ai.ModelInfo
import io.github.iampopovich.eculense.core.ai.OnDeviceLlm
import io.github.iampopovich.eculense.core.ai.OnDeviceRating
import io.github.iampopovich.eculense.core.ai.OnDeviceSession
import io.github.iampopovich.eculense.core.ai.OnDeviceSummary
import io.github.iampopovich.eculense.core.ai.SecretCipher
import io.github.iampopovich.eculense.core.ui.UiText
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Answers with the given result; [gate] lets the check "hang" so the Checking state can be observed. */
private class FakeChecker(var result: ConnectionCheck) : ConnectionChecker {
    val calls = mutableListOf<Triple<LlmProviderKind, String?, String?>>()
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun check(provider: LlmProviderKind, baseUrl: String?, apiKey: String?): ConnectionCheck {
        calls += Triple(provider, baseUrl, apiKey)
        gate?.await()
        return result
    }
}

private val catalog = listOf(
    ModelInfo("openai/gpt-4.1-mini", "OpenAI: GPT-4.1 Mini", 1_047_576, false, recommended = true),
    ModelInfo("meta-llama/llama-3.3-70b-instruct:free", "Meta: Llama 3.3 70B (free)", 131_072, true),
    ModelInfo("z-ai/glm-4.6", "Z.AI: GLM 4.6", 200_000, false),
)

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reversing = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(encoded: String) = encoded.reversed()
    }
    private lateinit var store: LlmSettingsStore

    // The real DataStore runs on IO, hence Main = Unconfined and waiting on state.
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

    private fun loadedVm(checker: ConnectionChecker): AiSettingsViewModel =
        AiSettingsViewModel(store, checker).also { vm -> waitUntil { vm.state.value.loaded } }

    private fun AiSettingsViewModel.checkAndWait() {
        check()
        waitUntil { state.value.check !is CheckState.Checking }
    }

    private class FakeOnDevice(initial: OnDeviceSummary?) : OnDeviceLlm {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
        var deactivated = 0
        override suspend fun session(): OnDeviceSession? = null
        override val summary = flow
        override suspend fun deactivate() {
            deactivated++
            flow.value = null
        }
    }

    @Test
    fun `on-device entry appears only with the module and an active local model shows as the summary`() {
        assertFalse(loadedVm(FakeChecker(ConnectionCheck.Ok(catalog))).state.value.onDeviceAvailable)
        val local = FakeOnDevice(OnDeviceSummary("Тестовая модель", "GPU", 13.2, OnDeviceRating.COMFORT))
        val vm = AiSettingsViewModel(store, FakeChecker(ConnectionCheck.Ok(catalog)), local, onDeviceRoute = true).also { v -> waitUntil { v.state.value.loaded } }
        assertTrue(vm.state.value.onDeviceAvailable)
        assertEquals("Тестовая модель", vm.state.value.onDevice?.model)
        assertFalse("сводка, а не мастер", vm.state.value.editing)
        vm.useCloud()
        waitUntil { vm.state.value.onDevice == null }
        assertEquals(1, local.deactivated)
        assertTrue("облако не настроено — мастер", vm.state.value.editing)
    }

    @Test
    fun `cloud build shows the other-build hint instead of the on-device entry, reset keeps it`() {
        val vm = AiSettingsViewModel(store, FakeChecker(ConnectionCheck.Ok(catalog)), onDeviceInOtherBuild = true).also { v -> waitUntil { v.state.value.loaded } }
        assertFalse(vm.state.value.onDeviceAvailable)
        assertTrue(vm.state.value.onDeviceInOtherBuild)
        vm.reset()
        waitUntil { vm.state.value.loaded }
        assertTrue(vm.state.value.onDeviceInOtherBuild)
        // local build and build without the flag do not show the hint
        assertFalse(loadedVm(FakeChecker(ConnectionCheck.Ok(catalog))).state.value.onDeviceInOtherBuild)
    }

    @Test
    fun `saving a cloud provider stops using the on-device model`() {
        val local = FakeOnDevice(OnDeviceSummary("Тестовая модель", "CPU"))
        val vm = AiSettingsViewModel(store, FakeChecker(ConnectionCheck.Ok(catalog)), local, onDeviceRoute = true).also { v -> waitUntil { v.state.value.loaded } }
        vm.startWizard()
        vm.setApiKey("sk-test")
        vm.checkAndWait()
        vm.selectModel("openai/gpt-4.1-mini")
        vm.save()
        waitUntil { local.deactivated == 1 }
    }

    @Test
    fun `fresh wizard starts on OpenRouter with the model step locked`() {
        val vm = loadedVm(FakeChecker(ConnectionCheck.Ok(catalog)))
        val s = vm.state.value
        assertTrue(s.editing)
        assertNull(s.saved)
        assertEquals(LlmProviderKind.OPENROUTER, s.provider)
        assertFalse(s.showUrlField)
        assertFalse(s.modelStepEnabled)
        assertFalse(s.canSave)
    }

    @Test
    fun `garbage url is rejected locally without a request`() {
        val checker = FakeChecker(ConnectionCheck.Ok(catalog))
        val vm = loadedVm(checker)
        vm.selectProvider(LlmProviderKind.OTHER)
        vm.setBaseUrl("openrouter.ai")
        vm.check()
        assertEquals(UiText.Res(R.string.aisettings_url_scheme), vm.state.value.urlError)
        assertTrue(checker.calls.isEmpty())
        assertFalse(vm.state.value.modelStepEnabled)
    }

    @Test
    fun `successful check unlocks the model step, choosing a model enables save`() {
        val checker = FakeChecker(ConnectionCheck.Ok(catalog)).apply { gate = CompletableDeferred() }
        val vm = loadedVm(checker)
        vm.setApiKey(" sk-or-test ")
        vm.check()
        waitUntil { vm.state.value.check is CheckState.Checking }
        assertFalse(vm.state.value.modelStepEnabled)
        checker.gate!!.complete(Unit)
        waitUntil { vm.state.value.check is CheckState.Passed }

        val s = vm.state.value
        assertEquals(UiText.Plural(R.plurals.aisettings_check_ok, 3), (s.check as CheckState.Passed).message)
        // Preset: the address is not passed — the Koog client defines it.
        assertEquals(listOf(Triple(LlmProviderKind.OPENROUTER, null, "sk-or-test")), checker.calls)
        assertTrue(s.modelStepEnabled)
        assertEquals(catalog, s.visibleModels)
        assertFalse(s.canSave)

        vm.selectModel("z-ai/glm-4.6")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `editing url or key after a successful check invalidates check and model`() {
        val vm = loadedVm(FakeChecker(ConnectionCheck.Ok(catalog)))
        vm.setApiKey("sk-or-test")
        vm.checkAndWait()
        vm.selectModel("z-ai/glm-4.6")

        vm.setApiKey("sk-or-test2")
        assertEquals(CheckState.Idle, vm.state.value.check)
        assertEquals("", vm.state.value.model)
        assertFalse(vm.state.value.modelStepEnabled)

        vm.checkAndWait()
        vm.selectModel("z-ai/glm-4.6")
        vm.setBaseUrl("https://openrouter.example.com")
        assertFalse(vm.state.value.modelStepEnabled)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `bad key and blocked network give distinct messages and keep the model step locked`() {
        val checker = FakeChecker(ConnectionCheck.Unauthorized("User not found."))
        val vm = loadedVm(checker)
        vm.setApiKey("sk-or-invalid")
        vm.checkAndWait()
        val unauthorized = (vm.state.value.check as CheckState.Failed).message
        assertEquals(
            UiText.Res(R.string.aisettings_with_detail, listOf(UiText.Res(R.string.aisettings_err_401), UiText.Raw("User not found."))),
            unauthorized,
        )
        assertFalse(vm.state.value.modelStepEnabled)

        checker.result = ConnectionCheck.Blocked("Access denied by security policy.")
        vm.checkAndWait()
        val blocked = (vm.state.value.check as CheckState.Failed).message
        assertEquals(
            UiText.Res(R.string.aisettings_with_detail, listOf(UiText.Res(R.string.aisettings_err_403), UiText.Raw("Access denied by security policy."))),
            blocked,
        )

        checker.result = ConnectionCheck.Unreachable("host not found (DNS)")
        vm.checkAndWait()
        assertEquals(
            UiText.Res(R.string.aisettings_err_unreachable, listOf(UiText.Raw("host not found (DNS)"))),
            (vm.state.value.check as CheckState.Failed).message,
        )
        checker.result = ConnectionCheck.Unreachable(null)
        vm.checkAndWait()
        assertEquals(
            UiText.Res(R.string.aisettings_err_unreachable, listOf(UiText.Res(R.string.aisettings_err_unreachable_hint))),
            (vm.state.value.check as CheckState.Failed).message,
        )

        checker.result = ConnectionCheck.NoModels
        vm.checkAndWait()
        assertEquals(UiText.Res(R.string.aisettings_err_empty_catalog), (vm.state.value.check as CheckState.Failed).message)
        assertFalse(vm.state.value.modelStepEnabled)
    }

    @Test
    fun `search filters by id and name, case-insensitive`() {
        val vm = loadedVm(FakeChecker(ConnectionCheck.Ok(catalog)))
        vm.setApiKey("k")
        vm.checkAndWait()
        vm.setQuery("LLAMA")
        assertEquals(listOf("meta-llama/llama-3.3-70b-instruct:free"), vm.state.value.visibleModels.map { it.id })
        vm.setQuery("glm 4")
        assertEquals(listOf("z-ai/glm-4.6"), vm.state.value.visibleModels.map { it.id })
    }

    @Test
    fun `save persists provider, url, model and key and shows the summary`() = runBlocking {
        val vm = loadedVm(FakeChecker(ConnectionCheck.Ok(catalog)))
        vm.setApiKey("sk-or-1")
        vm.checkAndWait()
        vm.selectModel("openai/gpt-4.1-mini")
        vm.save()
        waitUntil { !vm.state.value.editing }

        assertEquals(LlmSettings(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", LlmProviderKind.OPENROUTER.defaultBaseUrl, true), store.settings.first())
        assertEquals("sk-or-1", store.apiKey())
        assertEquals(SavedConfig(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", LlmProviderKind.OPENROUTER.defaultBaseUrl, true), vm.state.value.saved)
        assertEquals("", vm.state.value.apiKey)
    }

    @Test
    fun `save is ignored until the check passed and a model is chosen`() = runBlocking {
        val vm = loadedVm(FakeChecker(ConnectionCheck.Ok(catalog)))
        vm.setApiKey("sk-or-1")
        vm.save()
        Thread.sleep(100)
        assertNull(store.settings.first())
        assertTrue(vm.state.value.editing)
    }

    @Test
    fun `editing a saved config re-checks with the saved key when the field is left empty`() = runBlocking {
        store.save(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, "sk-or-saved")
        val checker = FakeChecker(ConnectionCheck.Ok(catalog))
        val vm = loadedVm(checker)
        assertFalse(vm.state.value.editing)
        assertNotNull(vm.state.value.saved)

        vm.edit()
        assertTrue(vm.state.value.editing)
        assertTrue(vm.state.value.canReuseSavedKey)
        assertFalse(vm.state.value.modelStepEnabled)
        vm.checkAndWait()
        assertEquals("sk-or-saved", checker.calls.single().third)
        // The saved model stays selected if the server returns it.
        assertEquals("openai/gpt-4.1-mini", vm.state.value.model)
        assertTrue(vm.state.value.canSave)

        // Different provider — don't fill in another provider's key.
        vm.selectProvider(LlmProviderKind.OPENAI)
        assertFalse(vm.state.value.canReuseSavedKey)
        assertFalse(vm.state.value.showUrlField)
        assertFalse(vm.state.value.modelStepEnabled)
    }

    @Test
    fun `custom server without a model list allows a typed model id`() = runBlocking {
        val checker = FakeChecker(ConnectionCheck.Ok(emptyList(), listed = false))
        val vm = loadedVm(checker)
        vm.selectProvider(LlmProviderKind.OTHER)
        vm.setBaseUrl("http://127.0.0.1:8089/v1")
        vm.checkAndWait()
        assertTrue(vm.state.value.manualModel)
        assertEquals(null, checker.calls.single().third)
        vm.selectModel("my-model")
        assertTrue(vm.state.value.canSave)
        vm.save()
        waitUntil { !vm.state.value.editing }
        assertEquals(LlmSettings(LlmProviderKind.OTHER, "my-model", "http://127.0.0.1:8089/v1", false), store.settings.first())
    }

    @Test
    fun `ollama has neither url nor key field and sends neither, Other shows both`() {
        val checker = FakeChecker(ConnectionCheck.Ok(listOf(ModelInfo("llama3.2:3b"))))
        val vm = loadedVm(checker)
        vm.selectProvider(LlmProviderKind.OLLAMA)
        assertFalse(vm.state.value.showUrlField)
        assertFalse(vm.state.value.showKeyField)
        vm.setApiKey("stray")
        vm.checkAndWait()
        assertEquals(Triple(LlmProviderKind.OLLAMA, null, null), checker.calls.single())

        vm.selectProvider(LlmProviderKind.OTHER)
        assertEquals("", vm.state.value.baseUrl)
        assertTrue(vm.state.value.showUrlField)
        assertTrue(vm.state.value.showKeyField)
    }

    @Test
    fun `public http address is rejected locally with the https hint`() {
        val checker = FakeChecker(ConnectionCheck.Ok(catalog))
        val vm = loadedVm(checker)
        vm.selectProvider(LlmProviderKind.OTHER)
        vm.setBaseUrl("http://example.com/v1")
        vm.check()
        assertEquals(UiText.Res(R.string.aisettings_url_public_http), vm.state.value.urlError)
        assertTrue(checker.calls.isEmpty())
    }

    @Test
    fun `empty ollama catalog suggests pulling a model`() {
        val vm = loadedVm(FakeChecker(ConnectionCheck.NoModels))
        vm.selectProvider(LlmProviderKind.OLLAMA)
        vm.checkAndWait()
        assertEquals(UiText.Res(R.string.aisettings_err_ollama_empty), (vm.state.value.check as CheckState.Failed).message)
    }

    @Test
    fun `remove key and reset`() = runBlocking {
        store.save(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, "sk-or-saved")
        val vm = loadedVm(FakeChecker(ConnectionCheck.Ok(catalog)))
        vm.clearKey()
        waitUntil { vm.state.value.saved?.hasKey == false }
        assertNull(store.apiKey())

        vm.reset()
        waitUntil { vm.state.value.saved == null }
        assertNull(store.settings.first())
        assertTrue(vm.state.value.editing)
        assertEquals(LlmProviderKind.OPENROUTER, vm.state.value.provider)
    }
}
