package io.github.iampopovich.eculense.feature.mqtt

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iampopovich.eculense.core.mqtt.ConnectResult
import io.github.iampopovich.eculense.core.mqtt.ConnackCode
import io.github.iampopovich.eculense.core.mqtt.MqttConfig
import io.github.iampopovich.eculense.core.secrets.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MqttSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val reversing = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:").reversed()
    }

    @Test
    fun `store is off by default, encrypts the password, keeps one install id, cleans prefix and period`() = runTest {
        val file = File(tmp.root, "mqtt.preferences_pb")
        val store = DataStoreMqttSettings(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }), reversing, random = { "0a1b2c3d4e5f" })
        val defaults = store.settings.first()
        assertFalse(defaults.enabled)
        assertEquals(1883, defaults.port)
        assertEquals(15, defaults.periodSec)
        store.update { it.copy(host = " 192.168.1.20 ", prefix = "/home/#car/", periodSec = 1) }
        store.setPassword("test-pass")
        val s = store.settings.first()
        assertEquals("192.168.1.20", s.host)
        assertEquals("home/car", s.prefix)
        assertEquals(MqttSettings.MIN_PERIOD_SEC, s.periodSec)
        assertTrue(s.hasPassword)
        assertEquals("test-pass", store.password())
        assertEquals("0a1b2c3d4e5f", store.installId())
        assertEquals("0a1b2c3d4e5f", store.installId())
        assertFalse("test-pass" in file.readBytes().decodeToString())
        store.setPassword("")
        assertNull(store.password())
        assertEquals(12, randomId().length)
    }

    private fun vm(store: InMemoryMqttSettings, result: ConnectResult, seen: MutableList<MqttConfig> = mutableListOf()) =
        MqttSettingsViewModel(store, probe = { seen += it; result }, debounceMs = 0)

    @Test
    fun `switch stays locked until the broker accepted exactly these settings`() = runTest {
        val store = InMemoryMqttSettings(storedPassword = null)
        val seen = mutableListOf<MqttConfig>()
        val vm = vm(store, ConnectResult.Accepted, seen)
        vm.setEnabled(true)
        assertFalse(vm.state.value.enabled)
        vm.setHost("192.168.1.20")
        vm.setUsername("ha")
        vm.setPassword("test-pass")
        vm.check()
        advanceUntilIdle()
        assertEquals(CheckState.Done(ConnectResult.Accepted), vm.state.value.check)
        val probed = seen.single()
        assertEquals("ha", probed.username)
        assertEquals("test-pass", probed.password)
        assertTrue(probed.clientId.endsWith("c")) // not the bridge's client id
        assertTrue(store.settings.value.hasPassword)
        // a broker field change invalidates the check
        vm.setPort("1884")
        assertFalse(vm.state.value.canEnable)
        vm.check()
        advanceUntilIdle()
        vm.setEnabled(true)
        advanceUntilIdle()
        assertTrue(store.settings.first().enabled)
        assertEquals(1884, store.settings.first().port)
        // switching off is always possible
        vm.setHost("192.168.1.21")
        vm.setEnabled(false)
        advanceUntilIdle()
        assertFalse(store.settings.first().enabled)
    }

    @Test
    fun `plain TCP to an internet host cannot be checked, TLS swaps the default port, failures stay typed`() = runTest {
        val vm = vm(InMemoryMqttSettings(), ConnectResult.Refused(ConnackCode.BAD_USERNAME_OR_PASSWORD))
        vm.setHost("broker.example.com")
        assertTrue(vm.state.value.plainNotAllowed)
        assertFalse(vm.state.value.canCheck)
        vm.setTls(true)
        assertEquals("8883", vm.state.value.port)
        assertTrue(vm.state.value.canCheck)
        vm.check()
        advanceUntilIdle()
        val done = vm.state.value.check as CheckState.Done
        assertTrue(done.result.badCredentials)
        assertFalse(vm.state.value.canEnable)
        vm.setPeriod("3")
        assertNull(vm.state.value.periodValue)
    }
}
