package io.github.iampopovich.eculense.core.obdtext

import androidx.annotation.StringRes
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.AdapterKind
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.ui.UiText

/** UI text for :core:obd types in the app language. Adapter replies and device names stay raw. */
object ObdText {
    fun error(e: ObdError): UiText = when (e) {
        is ObdError.Timeout -> UiText.Res(R.string.obd_error_timeout, listOf(e.command))
        is ObdError.NoTransport -> UiText.Res(R.string.obd_error_no_transport, listOf(e.kind))
        is ObdError.NoProtocol -> UiText.Res(R.string.obd_error_no_protocol, listOf(e.detail))
        ObdError.EcuSilent -> UiText.Res(R.string.obd_error_ecu_silent)
        ObdError.BusInitFailed -> UiText.Res(R.string.obd_error_bus_init_failed)
        ObdError.AdapterNotResponding -> UiText.Res(R.string.obd_error_adapter_not_responding)
        is ObdError.AdapterNoPower -> UiText.Res(R.string.obd_error_adapter_no_power, listOf(e.voltage))
        is ObdError.IgnitionOff -> UiText.Res(R.string.obd_error_ignition_off, listOf(e.voltage))
        is ObdError.CommandFailed -> UiText.Res(R.string.obd_error_command_failed, listOf(e.command, e.detail))
        ObdError.AdapterClosed -> UiText.Res(R.string.obd_error_adapter_closed)
        ObdError.NotOpen -> UiText.Res(R.string.obd_error_not_open)
        is ObdError.NoBaudRate -> UiText.Res(R.string.obd_error_no_baud_rate, listOf(e.tried.joinToString()))
        ObdError.DemoDisconnected -> UiText.Res(R.string.obd_error_demo_disconnected)
        ObdError.NoBluetooth -> UiText.Res(R.string.obd_error_no_bluetooth)
        ObdError.UsbNoAccess -> UiText.Res(R.string.obd_error_usb_no_access)
        ObdError.UsbPermissionDenied -> UiText.Res(R.string.obd_error_usb_permission_denied)
        ObdError.UsbNotOpen -> UiText.Res(R.string.obd_error_usb_not_open)
        ObdError.UsbDetached -> UiText.Res(R.string.obd_error_usb_detached)
        is ObdError.LinkLost -> UiText.Res(R.string.obd_error_link_lost, listOf(error(e.cause)))
        is ObdError.Other -> UiText.Raw(e.detail)
    }

    /** Error of any exception thrown by an OBD operation (typed when it came from :core:obd, raw otherwise). */
    fun error(e: Throwable): UiText = error(ObdError.of(e))

    fun endpointLabel(endpoint: AdapterEndpoint): UiText = when (endpoint.labelKey) {
        EndpointLabel.DEMO -> UiText.Res(R.string.obd_endpoint_demo)
        EndpointLabel.DEMO_FLAKY -> UiText.Res(R.string.obd_endpoint_demo_flaky)
        EndpointLabel.DEMO_ENGINE_OFF -> UiText.Res(R.string.obd_endpoint_demo_engine_off)
        EndpointLabel.PC_EMULATOR -> UiText.Res(R.string.obd_endpoint_pc_emulator)
        EndpointLabel.REPLAY -> UiText.Res(R.string.obd_endpoint_replay)
        null -> UiText.Raw(endpoint.label)
    }

    /** Second line of an adapter row: the address, or a description for built-in endpoints. */
    fun endpointDetail(endpoint: AdapterEndpoint): UiText = when (endpoint.labelKey) {
        EndpointLabel.DEMO -> UiText.Res(R.string.obd_endpoint_demo_detail)
        EndpointLabel.DEMO_FLAKY -> UiText.Res(R.string.obd_endpoint_demo_flaky_detail)
        EndpointLabel.DEMO_ENGINE_OFF -> UiText.Res(R.string.obd_endpoint_demo_engine_off_detail)
        EndpointLabel.REPLAY -> UiText.Res(R.string.obd_endpoint_replay_detail)
        EndpointLabel.PC_EMULATOR, null -> UiText.Raw(endpoint.address)
    }

    /** Protocol in plain words, e.g. "ISO 15765-4 CAN (11 bit, 500 kbaud)"; null = automatic. */
    fun protocolName(protocol: Char?): UiText = PROTOCOL_NAMES[protocol]?.let { UiText.Res(it) }
        ?: if (protocol == null) UiText.Res(R.string.obd_protocol_auto) else UiText.Raw(protocol.toString())

    fun adapterKind(kind: AdapterKind): UiText = UiText.Res(
        when (kind) {
            AdapterKind.GENUINE_ELM -> R.string.obd_adapter_kind_genuine
            AdapterKind.CLONE -> R.string.obd_adapter_kind_clone
            AdapterKind.STN -> R.string.obd_adapter_kind_stn
            AdapterKind.VLINKER -> R.string.obd_adapter_kind_vlinker
            AdapterKind.UNKNOWN -> R.string.obd_adapter_kind_unknown
        },
    )

    /** One status line for connection states beyond connected/failed (reconnecting, engine off). */
    fun sessionState(state: ConnectionState): UiText? = when (state) {
        is ConnectionState.Reconnecting -> UiText.Res(
            R.string.obd_state_reconnecting,
            listOf(state.attempt, ((state.nextInMs + 999) / 1000).toInt()),
        )
        is ConnectionState.EngineOff -> if (state.voltage != null) {
            UiText.Res(R.string.obd_state_engine_off_voltage, listOf(state.voltage!!))
        } else {
            UiText.Res(R.string.obd_state_engine_off)
        }
        else -> null
    }

    /**
     * C10: what to check for a failed connection, by the error and the adapter type (kind of the endpoint:
     * "bt", "wifi", "usb"). null when there is nothing concrete to suggest.
     */
    fun hint(error: ObdError, endpointKind: String): UiText? {
        val cause = (error as? ObdError.LinkLost)?.cause ?: error
        return when (cause) {
            ObdError.AdapterNotResponding, is ObdError.Timeout, ObdError.AdapterClosed, is ObdError.Other, ObdError.NotOpen ->
                when (endpointKind) {
                    "bt" -> UiText.Res(R.string.obd_hint_bt)
                    "wifi" -> UiText.Res(R.string.obd_hint_wifi)
                    "usb" -> UiText.Res(R.string.obd_hint_usb)
                    else -> null
                }
            is ObdError.NoBaudRate -> UiText.Res(R.string.obd_hint_usb)
            ObdError.EcuSilent, ObdError.BusInitFailed, is ObdError.IgnitionOff, is ObdError.NoProtocol -> UiText.Res(R.string.obd_hint_ignition)
            is ObdError.AdapterNoPower -> UiText.Res(R.string.obd_hint_no_power)
            else -> null
        }
    }

    /** ELM version string; blank when the adapter did not report one. */
    fun adapterVersion(version: String): UiText =
        if (version.isBlank()) UiText.Res(R.string.obd_adapter_version_unknown) else UiText.Raw(version)

    /** Localized name of a service 01 PID, null for PIDs without one (the UI then shows the sensor id). */
    @StringRes
    fun pidNameRes(code: Int): Int? = PID_NAMES[code]

    /** Name for a sensor id like "01.0C"; unknown ids are shown as is. */
    fun sensorName(sensorId: String): UiText =
        sensorId.removePrefix("01.").toIntOrNull(16)?.takeIf { sensorId.startsWith("01.") }?.let { pidNameRes(it) }
            ?.let { UiText.Res(it) } ?: UiText.Raw(sensorId)

    internal val PROTOCOL_NAMES: Map<Char?, Int> = mapOf(
        '1' to R.string.obd_protocol_1,
        '2' to R.string.obd_protocol_2,
        '3' to R.string.obd_protocol_3,
        '4' to R.string.obd_protocol_4,
        '5' to R.string.obd_protocol_5,
        '6' to R.string.obd_protocol_6,
        '7' to R.string.obd_protocol_7,
        '8' to R.string.obd_protocol_8,
        '9' to R.string.obd_protocol_9,
        'A' to R.string.obd_protocol_a,
        'B' to R.string.obd_protocol_b,
        'C' to R.string.obd_protocol_c,
    )

    internal val PID_NAMES: Map<Int, Int> = mapOf(
        0x04 to R.string.obd_pid_04,
        0x05 to R.string.obd_pid_05,
        0x06 to R.string.obd_pid_06,
        0x07 to R.string.obd_pid_07,
        0x0B to R.string.obd_pid_0b,
        0x0C to R.string.obd_pid_0c,
        0x0D to R.string.obd_pid_0d,
        0x0E to R.string.obd_pid_0e,
        0x0F to R.string.obd_pid_0f,
        0x10 to R.string.obd_pid_10,
        0x11 to R.string.obd_pid_11,
        0x1F to R.string.obd_pid_1f,
        0x21 to R.string.obd_pid_21,
        0x2F to R.string.obd_pid_2f,
        0x31 to R.string.obd_pid_31,
        0x33 to R.string.obd_pid_33,
        0x42 to R.string.obd_pid_42,
        0x46 to R.string.obd_pid_46,
        0x5C to R.string.obd_pid_5c,
        0x5E to R.string.obd_pid_5e,
    )
}
