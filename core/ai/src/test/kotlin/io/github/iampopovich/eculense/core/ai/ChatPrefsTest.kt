package io.github.iampopovich.eculense.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatPrefsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() = scope.cancel()

    @Test
    fun `consent per provider, toggle and conversation survive`() = runBlocking {
        val prefs = DataStoreChatPrefs(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "chat.preferences_pb") })
        assertFalse(prefs.hasConsent("DEEPSEEK"))
        assertNull(prefs.useCarData())
        prefs.grantConsent("DEEPSEEK")
        assertTrue(prefs.hasConsent("DEEPSEEK"))
        assertFalse(prefs.hasConsent("OPENROUTER"))
        prefs.setUseCarData(false)
        assertEquals(false, prefs.useCarData())
        prefs.setConversationId("c1")
        assertEquals("c1", prefs.conversationId())
        prefs.revokeConsents()
        assertFalse(prefs.hasConsent("DEEPSEEK"))
    }
}
