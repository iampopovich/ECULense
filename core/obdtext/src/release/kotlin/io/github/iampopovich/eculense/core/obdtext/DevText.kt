package io.github.iampopovich.eculense.core.obdtext

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.ui.UiText

/**
 * R-I: release builds register no developer endpoints, so this is unreachable in practice; it shows the raw
 * fallbacks instead of shipping demo text (debug copy in src/debug).
 */
internal object DevText {
    fun label(endpoint: AdapterEndpoint): UiText = UiText.Raw(endpoint.label)

    fun detail(endpoint: AdapterEndpoint): UiText = UiText.Raw(endpoint.address)

    fun demoDisconnected(): UiText = UiText.Res(R.string.obd_error_adapter_closed)
}
