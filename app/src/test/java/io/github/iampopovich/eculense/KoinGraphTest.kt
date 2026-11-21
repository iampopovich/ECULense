package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.ai.aiModule
import io.github.iampopovich.eculense.core.aitools.aiToolsModule
import io.github.iampopovich.eculense.core.database.databaseModule
import io.github.iampopovich.eculense.feature.aichat.aiChatModule
import io.github.iampopovich.eculense.feature.aisettings.aiSettingsModule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.MissingKoinDefinitionException
import org.koin.test.verify.verify

/**
 * R1.4: every definition of the app graph finds its dependencies — with all modules and with the AI modules removed
 * (the removability promise: the app must work without AI). [extraTypes] = types created outside Koin: Android
 * framework objects, lambdas, primitives and parametersOf() arguments.
 */
@OptIn(KoinExperimentalAPI::class)
class KoinGraphTest {
    private val aiModules = listOf(aiModule, aiToolsModule, aiSettingsModule, aiChatModule) + onDeviceModules

    @Test
    fun `full module graph verifies`() {
        module { includes(appModules) }.verify(extraTypes = KoinExtraTypes.all)
    }

    @Test
    fun `graph without AI modules verifies`() {
        val withoutAi = appModules.filterNot { it in aiModules }
        assertEquals(appModules.size - aiModules.size, withoutAi.size)
        module { includes(withoutAi) }.verify(extraTypes = KoinExtraTypes.all)
    }

    @Test
    fun `release graph without developer modules verifies`() {
        // R-I: release builds have no demo/replay modules (DevModules.kt is empty there).
        val release = appModules.filterNot { it in devModules }
        assertEquals(appModules.size - devModules.size, release.size)
        module { includes(release) }.verify(extraTypes = KoinExtraTypes.all)
    }

    @Test(expected = MissingKoinDefinitionException::class)
    fun `a missing module is detected`() {
        // Negative check: without the database module the DAOs are missing.
        module { includes(appModules.filterNot { it === databaseModule }) }.verify(extraTypes = KoinExtraTypes.all)
    }
}
