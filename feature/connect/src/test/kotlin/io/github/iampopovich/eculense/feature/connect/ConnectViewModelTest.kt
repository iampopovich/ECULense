package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val obd = AdapterEndpoint("bt", "AA:BB", "OBDII")

    @Test
    fun `refresh loads endpoints`() = runTest {
        val vm = ConnectViewModel(FakeConnectionController(endpoints = listOf(obd)))
        vm.refresh()
        assertEquals(listOf(obd), vm.endpoints.value)
    }

    @Test
    fun `paired bluetooth first, demo adapter last`() = runTest {
        val demo = AdapterEndpoint("demo", "demo", "Демо-режим (симуляция)")
        val wifi = AdapterEndpoint("wifi", "192.168.0.10:35000", "Wi-Fi ELM327")
        val vm = ConnectViewModel(FakeConnectionController(endpoints = listOf(demo, wifi, obd)))
        vm.refresh()
        assertEquals(listOf(obd, wifi, demo), vm.endpoints.value)
    }

    @Test
    fun `connect is delegated to controller`() = runTest {
        val controller = FakeConnectionController()
        ConnectViewModel(controller).connect(obd)
        assertEquals(listOf(obd), controller.connectCalls)
    }
}
