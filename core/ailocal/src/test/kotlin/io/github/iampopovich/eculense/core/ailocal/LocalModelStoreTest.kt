package io.github.iampopovich.eculense.core.ailocal

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.ai.SecretCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val reversing = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(encoded: String) = encoded.reversed()
    }

    private fun report(id: String) = BenchReport(
        id,
        listOf(
            BackendRun(BackendKind.CPU, ok = true, initSeconds = 2.6, ttftSeconds = 11.3, prefillTokensPerSecond = 22.7, decodeTokensPerSecond = 11.4, peakRssBytes = 1_956_515_840),
            BackendRun(BackendKind.NPU, ok = false, error = "процесс теста завершился аварийно"),
        ),
        finishedAtMillis = 1_000L,
    )

    @Test
    fun `active model, backend and reports survive a restart`() = runTest {
        val file = File(tmp.root, "ailocal.preferences_pb")
        val first = LocalModelStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, reversing)
        assertNull(first.active.first())
        first.saveReport(report("model-a"))
        first.activate("model-a", BackendKind.GPU)
        assertEquals(ActiveModel("model-a", BackendKind.GPU), first.active.first())
        assertEquals(report("model-a"), first.reports.first()["model-a"])
    }

    @Test
    fun `deactivate keeps reports, forget removes the model's report and selection`() = runTest {
        val store = LocalModelStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "b.preferences_pb") }, reversing)
        store.saveReport(report("model-a"))
        store.saveReport(report("model-b"))
        store.activate("model-a", BackendKind.CPU)
        store.deactivate()
        assertNull(store.active.first())
        assertEquals(setOf("model-a", "model-b"), store.reports.first().keys)
        store.activate("model-a", BackendKind.CPU)
        store.forget("model-a")
        assertNull(store.active.first())
        assertEquals(setOf("model-b"), store.reports.first().keys)
    }

    @Test
    fun `hugging face token is stored encrypted and can be removed`() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "c.preferences_pb") }
        val store = LocalModelStore(ds, reversing)
        assertFalse(store.hasToken.first())
        store.setToken("  hf_Testovy123  ")
        assertTrue(store.hasToken.first())
        assertEquals("hf_Testovy123", store.token())
        assertEquals("321yvotseT_fh", ds.data.first()[stringPreferencesKey("hf_token")])
        store.setToken(null)
        assertNull(store.token())
    }

    @Test
    fun `custom models are kept in order without duplicates`() = runTest {
        val store = LocalModelStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "d.preferences_pb") }, reversing)
        val m = LocalModel("model-custom-x-1", "x.litertlm", "Тестовая", "", "https://huggingface.co/o/r/resolve/main/x.litertlm", 10, null, false, ModelOrigin.CUSTOM_LINK, "", "https://huggingface.co/o/r", "main")
        store.addCustom(m)
        store.addCustom(m.copy(title = "Тестовая 2"))
        assertEquals(listOf("Тестовая 2"), store.custom.first().map { it.title })
        store.removeCustom(m.packId)
        assertEquals(emptyList<LocalModel>(), store.custom.first())
    }
}
