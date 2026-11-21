package io.github.iampopovich.eculense.core.obdtext

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.ui.UiText

/**
 * R-I: text of the developer endpoints (demo adapters, PC emulator, replay). Debug builds only; the release copy of
 * this object (src/release) has no resources, since release builds register none of these endpoints.
 */
internal object DevText {
    fun label(endpoint: AdapterEndpoint): UiText = when (endpoint.labelKey) {
        EndpointLabel.DEMO -> UiText.Res(R.string.obd_endpoint_demo)
        EndpointLabel.DEMO_FLAKY -> UiText.Res(R.string.obd_endpoint_demo_flaky)
        EndpointLabel.DEMO_ENGINE_OFF -> UiText.Res(R.string.obd_endpoint_demo_engine_off)
        EndpointLabel.PC_EMULATOR -> UiText.Res(R.string.obd_endpoint_pc_emulator)
        EndpointLabel.REPLAY -> UiText.Res(R.string.obd_endpoint_replay)
        else -> UiText.Raw(endpoint.label)
    }

    fun detail(endpoint: AdapterEndpoint): UiText = when (endpoint.labelKey) {
        EndpointLabel.DEMO -> UiText.Res(R.string.obd_endpoint_demo_detail)
        EndpointLabel.DEMO_FLAKY -> UiText.Res(R.string.obd_endpoint_demo_flaky_detail)
        EndpointLabel.DEMO_ENGINE_OFF -> UiText.Res(R.string.obd_endpoint_demo_engine_off_detail)
        EndpointLabel.REPLAY -> UiText.Res(R.string.obd_endpoint_replay_detail)
        else -> UiText.Raw(endpoint.address)
    }

    fun demoDisconnected(): UiText = UiText.Res(R.string.obd_error_demo_disconnected)
}
