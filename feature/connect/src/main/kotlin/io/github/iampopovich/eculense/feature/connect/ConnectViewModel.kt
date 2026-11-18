package io.github.iampopovich.eculense.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ConnectViewModel(private val controller: ConnectionController) : ViewModel() {
    private val _endpoints = MutableStateFlow<List<AdapterEndpoint>>(emptyList())
    val endpoints: StateFlow<List<AdapterEndpoint>> = _endpoints.asStateFlow()
    val state: StateFlow<ConnectionState> = controller.state

    fun refresh() {
        viewModelScope.launch { _endpoints.value = controller.discover() }
    }

    fun connect(endpoint: AdapterEndpoint) = controller.connect(endpoint)

    fun disconnect() = controller.disconnect()
}
