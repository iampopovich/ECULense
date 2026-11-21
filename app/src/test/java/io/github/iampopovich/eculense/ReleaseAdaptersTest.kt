package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ReplayTransportFactory
import io.github.iampopovich.eculense.core.obd.TransportFactory
import io.github.iampopovich.eculense.core.obd.WifiTransportFactory
import io.github.iampopovich.eculense.core.obd.isSimulated
import io.github.iampopovich.eculense.core.obd.obdModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.core.module.flatten
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import java.io.File

/**
 * R-I: simulated endpoints (demo adapters, PC emulator, replay) exist only in debug builds. The release graph = the
 * app modules without [devModules]; in the release unit-test variant [devModules] is the empty release copy itself.
 */
@OptIn(KoinInternalApi::class)
class ReleaseAdaptersTest {
    private val releaseModules = appModules.filterNot { it in devModules }

    /** Qualifiers of every definition bound to [TransportFactory] (the adapter list is getAll<TransportFactory>()). */
    private fun factoryQualifiers(modules: List<Module>): Set<String> = flatten(modules).flatMap { m ->
        m.mappings.values.map { it.beanDefinition }
            .filter { it.primaryType == TransportFactory::class || TransportFactory::class in it.secondaryTypes }
            .map { it.qualifier?.value ?: it.primaryType.simpleName.orEmpty() }
    }.toSet()

    @Test
    fun `release graph registers no simulated transport factories`() {
        val kinds = factoryQualifiers(releaseModules)
        assertTrue(kinds.toString(), WifiTransportFactory.KIND in kinds)
        assertTrue(kinds.toString(), kinds.none { it.startsWith("demo") || it == ReplayTransportFactory.KIND })
    }

    @Test
    fun `release wifi factory lists no simulated endpoint`() = runBlocking {
        val releaseWifi = if (BuildConfig.DEBUG) emptyList<AdapterEndpoint>() else devWifiEndpoints
        val koin = koinApplication { modules(obdModule(releaseWifi)) }.koin
        val listed = koin.get<TransportFactory>(named(WifiTransportFactory.KIND)).discover()
        assertTrue(listed.isNotEmpty())
        assertTrue(listed.toString(), listed.none { it.isSimulated })
    }

    @Test
    fun `developer endpoints are debug only and all simulated`() {
        if (BuildConfig.DEBUG) {
            val kinds = factoryQualifiers(devModules)
            assertEquals(setOf("demo", "demo-flaky", "demo-engine-off", ReplayTransportFactory.KIND), kinds)
            assertTrue(devWifiEndpoints.isNotEmpty())
            assertTrue(devWifiEndpoints.all { it.isSimulated })
        } else {
            assertTrue(devModules.isEmpty())
            assertTrue(devWifiEndpoints.isEmpty())
        }
    }

    @Test
    fun `main sources never reference the demo module`() {
        val root = File("..").canonicalFile
        val offenders = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".git", ".gradle", ".idea", ".hermes", "build-logic") }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { "/src/main/" in it && !it.startsWith("core/demo/") }
            .filter { File(root, it).readText().contains("eculense.core.demo") }
            .toList()
        assertFalse("found no sources at all", root.resolve("app/src/main").listFiles().isNullOrEmpty())
        assertEquals(emptyList<String>(), offenders)
    }
}
