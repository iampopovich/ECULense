package io.github.iampopovich.eculense.feature.dtc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.obd.ModuleDtc
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleDtcService
import io.github.iampopovich.eculense.core.obd.ModuleDtcStatus
import io.github.iampopovich.eculense.core.obd.ModuleKind
import io.github.iampopovich.eculense.core.obdtext.ObdText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** R-D «Vehicle modules» screen on fake states (library module test: no app data involved). */
@RunWith(AndroidJUnit4::class)
class VehicleModulesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private val rows = listOf(
        ModuleRow("7E8", ModuleKind.ENGINE, emission = true, ModuleDtcRead.Codes(ModuleDtcService.MODE_03, emptyList()), emptyList()),
        ModuleRow(
            "7D9", ModuleKind.ABS_ESC, emission = false,
            ModuleDtcRead.Codes(ModuleDtcService.UDS_19_02, listOf(ModuleDtc("C1234", setOf(ModuleDtcStatus.CONFIRMED)))),
            listOf(ModuleDtcItem(ModuleDtc("C1234", setOf(ModuleDtcStatus.CONFIRMED)), DtcInfo("C1234", "Test wheel speed sensor", DtcSource.OBDEX))),
        ),
        ModuleRow("7E9", ModuleKind.TRANSMISSION, emission = false, ModuleDtcRead.NotSupported, emptyList()),
    )

    @Test
    fun disabledScanAlwaysSaysWhy() {
        var state by mutableStateOf(ModulesUiState())
        rule.setContent { VehicleModulesScreen(state, onScan = {}, onOpen = {}, onClose = {}) }
        rule.onNodeWithText(str(R.string.modules_scan)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.modules_need_connection)).assertExists()
        rule.onNodeWithText(str(R.string.modules_srs_note)).assertExists()
        state = ModulesUiState(connected = true, speed = SpeedGate.MOVING)
        rule.onNodeWithText(str(R.string.modules_stop_car)).assertExists()
        rule.onNodeWithText(str(R.string.modules_scan)).assertIsNotEnabled()
        state = ModulesUiState(connected = true, speed = SpeedGate.UNKNOWN)
        rule.onNodeWithText(str(R.string.modules_waiting_speed)).assertExists()
        state = ModulesUiState(connected = true, speed = SpeedGate.STOPPED, checked = true)
        rule.onNodeWithText(str(R.string.modules_scan)).assertIsEnabled()
        rule.onNodeWithText(str(R.string.modules_none)).assertExists()
    }

    @Test
    fun rowsOpenTheirCodesWithTheClearingNote() {
        var opened = -1
        var state by mutableStateOf(ModulesUiState(connected = true, speed = SpeedGate.STOPPED, rows = rows, checked = true, limited = true))
        rule.setContent { VehicleModulesScreen(state, onScan = {}, onOpen = { opened = it; state = state.copy(selected = it) }, onClose = { state = state.copy(selected = null) }) }
        rule.onNodeWithText(str(R.string.modules_limited)).assertExists()
        rule.onNodeWithText(ObdText.moduleName(ModuleKind.ABS_ESC, "7D9").asString(ctx)).performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("C1234").assertExists()
        rule.onNodeWithText("Test wheel speed sensor").assertExists()
        rule.onNodeWithText(str(R.string.modules_status_confirmed)).assertExists()
        rule.onNodeWithText(str(R.string.modules_clear_not_supported)).assertExists()
        rule.onNodeWithText(str(R.string.modules_back)).performClick()
        rule.onNodeWithText(ObdText.moduleName(ModuleKind.TRANSMISSION, "7E9").asString(ctx)).performClick()
        rule.onNodeWithText(str(R.string.modules_not_supported)).assertExists()
    }
}
